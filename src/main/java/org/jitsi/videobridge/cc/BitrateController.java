/*
 * Copyright @ 2015 - Present, 8x8 Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.videobridge.cc;

import com.google.common.collect.Sets;
import edu.umd.cs.findbugs.annotations.*;
import org.jetbrains.annotations.*;
import org.jitsi.nlj.*;
import org.jitsi.nlj.format.*;
import org.jitsi.nlj.rtp.*;
import org.jitsi.rtp.rtcp.*;
import org.jitsi.utils.*;
import org.jitsi.utils.logging.*;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.videobridge.*;
import org.jitsi_modified.impl.neomedia.rtp.*;
import org.json.simple.*;

import java.lang.*;
import java.lang.SuppressWarnings;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.*;

import static org.jitsi.videobridge.cc.config.BitrateControllerConfig.*;

/**
 * The {@link BitrateController} is attached to a destination {@link
 * Endpoint} and its purpose is 1st to selectively drop incoming packets
 * based on their source {@link Endpoint} and 2nd to rewrite the accepted
 * packets so that they form a correct RTP stream without gaps in their
 * sequence numbers nor in their timestamps.
 *
 * In the Selective Forwarding Middlebox (SFM) topology [RFC7667], senders are
 * sending multiple bitstreams of (they multistream) the same video source
 * (using either with simulcast or scalable video coding) and the SFM decides
 * which bitstream to send to which receiver.
 *
 * For example, suppose we're in a 3-way simulcast-enabled video call (so 3
 * source {@link Endpoint}s) where the endpoints are sending two
 * non-scalable RTP streams of the same video source. The bitrate controller,
 * will chose to forward only one RTP stream at a time to a specific destination
 * endpoint.
 *
 * With scalable video coding (SVC) a bitstream can be broken down into multiple
 * sub-bitstreams of lower frame rate (that we call temporal layers or TL)
 * and/or lower resolution (that we call spatial layers or SL). The exact
 * dependencies between the different layers of an SVC bitstream are codec
 * specific but, as a general rule of thumb higher temporal/spatial layers
 * depend on lower temporal/spatial layers creating a dependency graph.
 *
 * For example, a 720p@30fps VP8 scalable bitstream can be broken down into 3
 * sub-bitstreams: one 720p@30fps layer (the highest temporal layer), that
 * depends on a 720p@15fps layer that depends on a 720p7.5fps layer (the lowest
 * temporal layer). In order for the decoder to be able decode the highest
 * temporal layer, it needs to get all the packets of its direct and transitive
 * dependencies, so of both the other two layers. In this simple case we have a
 * linked list of dependencies with the the lowest temporal layer as root and
 * the highest temporal layer as a leaf.
 *
 * In order for the SFM to be able to filter the incoming packets we need to be
 * able to assign incoming packets to "flows" with properties that allow us to
 * define filtering rules. In this implementation a flow is represented by an
 * {@link RTPEncodingDesc} and its properties are bitrate and subjective quality
 * index. Specifically, the incoming packets belong to some {@link FrameDesc},
 * which belongs to some {@link RTPEncodingDesc}, which belongs to some {@link
 * MediaStreamTrackDesc}, which belongs to the source {@link Endpoint}.
 * This hierarchy allows for fine-grained filtering up to the {@link FrameDesc}
 * level.
 *
 * The decision of whether to project or drop a specific RTP packet of a
 * specific {@link FrameDesc} of a specific {@link MediaStreamTrackDesc}
 * depends on the bitrate allocation of the specific {@link
 * MediaStreamTrackDesc} and on the state of the bitstream that is produced by
 * this filter. For example, if we have a {@link MediaStreamTrackDesc} with 2
 * {@link RTPEncodingDesc} in a simulcast configuration, then we can switch
 * between the two {@link RTPEncodingDesc}s if it is mandated by the bitrate
 * allocation and only if we see a refresh point.
 *
 * @author George Politis
 */
@SuppressWarnings("JavadocReference")
public class BitrateController
{
    /**
     * An reusable empty array of {@link RateSnapshot} to reduce allocations.
     */
    private static final RateSnapshot[] EMPTY_RATE_SNAPSHOT_ARRAY
        = new RateSnapshot[0];

    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    private final Logger logger;

    /**
     * The {@link TimeSeriesLogger} to be used by this instance to print time
     * series.
     */
    private static final TimeSeriesLogger timeSeriesLogger
        = TimeSeriesLogger.getTimeSeriesLogger(BitrateController.class);

    /**
     * An empty set of {@link String}s instance.
     */
    private static final Set<String> INITIAL_EMPTY_SET
        = Collections.unmodifiableSet(new HashSet<>(0));

    /**
     * The {@link AdaptiveTrackProjection}s that this instance is managing, keyed
     * by the SSRCs of the associated {@link MediaStreamTrackDesc}.
     */
    private final Map<Long, AdaptiveTrackProjection>
        adaptiveTrackProjectionMap = new ConcurrentHashMap<>();

    /**
     * A boolean that indicates whether to enable or disable the video quality
     * tracing.
     */
    private final boolean enableVideoQualityTracing;

    /**
     * The time (in ms) when this instance first transformed any media. This
     * allows to ignore the CC during the early stages of the call and ramp up
     * the send rate faster.
     *
     * NOTE This is only meant to be as a temporary hack and ideally this should
     * be fixed in the CC.
     */
    private long firstMediaMs = -1;

    /**
     * The last bandwidth estimation that we got. This is used to limit the
     * resolution changes due to bandwidth changes. We react to bandwidth
     * changes greater than BWE_CHANGE_THRESHOLD_PCT/100 of the last bandwidth
     * estimation.
     */
    private long lastBwe = -1;

    /**
     * The main result of the bitrate allocation algorithm computation.
     */
    private List<AdaptiveTrackProjection> adaptiveTrackProjections
            = Collections.emptyList();

    /**
     * The maximum frame height, in pixels, the endpoint with which this
     * {@link BitrateController} is willing to receive.  -1 means there
     * is no maximum.
     */
    private int maxRxFrameHeightPx = -1;

    private int maxRxFrameTemporalLayerId = -1;

    /**
     * The IDs of the endpoints which have been pinned by the endpoint to which
     * this {@link BitrateController} belongs.
     */
    private Set<String> pinnedEndpointIds = Collections.emptySet();

    /**
     * The ID of the endpoint to which this {@link BitrateController} belongs
     */
    private final Endpoint destinationEndpoint;

    private final DiagnosticContext diagnosticContext;

    // NOTE(george): this flag acts as an approximation for determining whether
    // or not adaptivity/probing is supported. Eventually we need to scrap this
    // and implement something cleaner, i.e. disable adaptivity if the endpoint
    // hasn't signaled `goog-remb` nor `transport-cc`.
    //
    // Unfortunately the channel iq from jicofo lists `goog-remb` and
    // `transport-cc` support, even tho the jingle from firefox doesn't (which
    // is the main use case for wanting to disable adaptivity).
    private boolean supportsRtx = false;

    private final Map<Byte, PayloadType> payloadTypes =
        new ConcurrentHashMap<>();

    private final AtomicInteger numDroppedPacketsUnknownSsrc =
        new AtomicInteger(0);

    /**
     * Initializes a new {@link BitrateController} instance which is to
     * belong to a particular {@link Endpoint}.
     */
    public BitrateController(
            Endpoint destinationEndpoint,
            @NotNull DiagnosticContext diagnosticContext,
            Logger parentLogger
    )
    {
        this.destinationEndpoint = destinationEndpoint;
        this.diagnosticContext = diagnosticContext;
        this.logger = parentLogger.createChildLogger(BitrateController.class.getName());

        enableVideoQualityTracing = timeSeriesLogger.isTraceEnabled();
    }

    /**
     * Returns a boolean that indicates whether or not the current bandwidth
     * estimation (in bps) has changed above the configured threshold (in
     * percent) {@link #BWE_CHANGE_THRESHOLD_PCT} with respect to the previous
     * bandwidth estimation.
     *
     * @param previousBwe the previous bandwidth estimation (in bps).
     * @param currentBwe the current bandwidth estimation (in bps).
     *
     * @return true if the bandwidth has changed above the configured threshold,
     * false otherwise.
     */
    private static boolean changeIsLargerThanThreshold(
        long previousBwe, long currentBwe)
    {
        if (previousBwe == -1 || currentBwe == -1)
        {
            return true;
        }

        long deltaBwe = currentBwe - previousBwe;

        // if the bwe has increased, we should act upon it, otherwise
        // we may end up in this broken situation: Suppose that the target
        // bitrate is 2.5Mbps, and that the last bitrate allocation was
        // performed with a 2.4Mbps bandwidth estimate.  The bridge keeps
        // probing and, suppose that, eventually the bandwidth estimate reaches
        // 2.6Mbps, which is plenty to accomodate the target bitrate; but the
        // minimum bandwidth estimate that would trigger a new bitrate
        // allocation is 2.4Mbps + 2.4Mbps * 15% = 2.76Mbps.
        //
        // if, on the other hand, the bwe has decreased, we require a 15%
        // (configurable) drop at last in order to update the bitrate
        // allocation. This is an ugly hack to prevent too many resolution/UI
        // changes in case the bridge produces too low bandwidth estimate, at
        // the risk of clogging the receiver's pipe.

        return deltaBwe > 0
            ||  deltaBwe < -1 * previousBwe * Config.bweChangeThresholdPct() / 100;
    }

    /**
     * Defines a packet filter that controls which RTP packets to be written
     * into the {@link Endpoint} that owns this {@link BitrateController}.
     *
     * @param videoRtpPacket that packet for which to decide whether to accept
     * @return <tt>true</tt> to allow the specified packet to be
     * written into the {@link Endpoint} that owns this {@link BitrateController}
     * ; otherwise, <tt>false</tt>
     */
    public boolean accept(@NotNull PacketInfo packetInfo)
    {
        if(packetInfo.getPacket() instanceof AudioRtpPacket) {
            return pinnedEndpointIds.contains(packetInfo.getEndpointId());
        }

        VideoRtpPacket videoRtpPacket = packetInfo.packetAs();
        long ssrc = videoRtpPacket.getSsrc();

        AdaptiveTrackProjection adaptiveTrackProjection
                = adaptiveTrackProjectionMap.get(ssrc);

        if (adaptiveTrackProjection == null)
        {
            logger.debug(() ->
                    "Dropping an RTP packet, because the SSRC has not " +
                            "been signaled:" + ssrc);
            numDroppedPacketsUnknownSsrc.incrementAndGet();
            return false;
        }

        return adaptiveTrackProjection.accept(packetInfo);
    }

    /**
     * Defines a packet filter that controls which RTCP Sender Report
     * packets to be written into the {@link Endpoint} that owns this
     * {@link BitrateController}.
     * </p>
     * Filters out packets that match one of the streams that this
     * {@code BitrateController} manages, but don't match the target SSRC.
     * Allows packets for streams not managed by this {@link BitrateController}.
     *
     * @param rtcpSrPacket that packet for which to decide whether to accept
     * @return <tt>true</tt> to allow the specified packet to be
     * written into the {@link Endpoint} that owns this {@link BitrateController}
     * ; otherwise, <tt>false</tt>
     */
    public boolean accept(RtcpSrPacket rtcpSrPacket)
    {
        long ssrc = rtcpSrPacket.getSenderSsrc();

        AdaptiveTrackProjection adaptiveTrackProjection
                = adaptiveTrackProjectionMap.get(ssrc);

        if (adaptiveTrackProjection == null)
        {
            // This is probably for an audio stream. In any case, if it's for a
            // stream which we are not forwarding it will be stripped off at
            // a later stage (in RtcpSrUpdater).
            return true;
        }

        // We only accept SRs for the SSRC that we're forwarding with.
        return ssrc == adaptiveTrackProjection.getTargetSsrc();
    }


    public boolean transformRtcp(RtcpSrPacket rtcpSrPacket)
    {
        long ssrc = rtcpSrPacket.getSenderSsrc();

        AdaptiveTrackProjection adaptiveTrackProjection
                = adaptiveTrackProjectionMap.get(ssrc);

        return adaptiveTrackProjection != null
                && adaptiveTrackProjection.rewriteRtcp(rtcpSrPacket);
    }

    /**
     * Gets a JSON representation of the parts of this object's state that
     * are deemed useful for debugging.
     */
    @SuppressWarnings("unchecked")
    @SuppressFBWarnings(
            value = "IS2_INCONSISTENT_SYNC",
            justification = "We intentionally avoid synchronizing while reading" +
                    " fields only used in debug output.")
    public JSONObject getDebugState()
    {
        JSONObject debugState = new JSONObject();
        debugState.put("trustBwe", Config.trustBwe());
        debugState.put("lastBwe", lastBwe);
        debugState.put("maxRxFrameHeightPx", maxRxFrameHeightPx);
        debugState.put("pinnedEndpointIds", pinnedEndpointIds.toString());
        debugState.put("supportsRtx", supportsRtx);
        JSONObject adaptiveTrackProjectionsJson = new JSONObject();
        for (Map.Entry<Long, AdaptiveTrackProjection> entry
                : adaptiveTrackProjectionMap.entrySet())
        {
            adaptiveTrackProjectionsJson.put(
                    entry.getKey(),
                    entry.getValue().getDebugState());
        }
        debugState.put(
                "adaptiveTrackProjectionMap",
                adaptiveTrackProjectionsJson);
        debugState.put(
            "numDroppedPacketsUnknownSsrc",
            numDroppedPacketsUnknownSsrc.intValue());
        return debugState;
    }

    /**
     * TODO Document
     */
    static class StatusSnapshot
    {
        final long currentTargetBps;
        final long currentIdealBps;
        final Collection<Long> activeSsrcs;

        StatusSnapshot()
        {
            currentTargetBps = -1L;
            currentIdealBps = -1L;
            activeSsrcs = Collections.emptyList();
        }
        StatusSnapshot(
                Long currentTargetBps,
                Long currentIdealBps,
                Collection<Long> activeSsrcs)
        {
            this.currentTargetBps = currentTargetBps;
            this.currentIdealBps = currentIdealBps;
            this.activeSsrcs = activeSsrcs;
        }
    }

    /**
     * Get a snapshot of the following data:
     * 1) The current target bitrate we're trying to send across our tracks
     * 2) The idea bitrate we could possibly send, given our tracks
     * 3) The ssrcs we're currently forwarding
     * @return the snapshot containing that info
     */
    StatusSnapshot getStatusSnapshot()
    {
        if (adaptiveTrackProjections == null
            || adaptiveTrackProjections.isEmpty())
        {
            return new StatusSnapshot();
        }
        List<Long> activeSsrcs = new ArrayList<>();
        long totalTargetBps = 0, totalIdealBps = 0;
        long nowMs = System.currentTimeMillis();
        for (MediaStreamTrackDesc sourceTrack : destinationEndpoint
            .getConference().getEndpoints().stream()
            .filter(e -> !destinationEndpoint.equals(e))
            .map(AbstractEndpoint::getMediaStreamTracks)
            .flatMap(Arrays::stream)
            .filter(t -> t.getRTPEncodings().length > 0)
            .collect(Collectors.toList()))
        {

            long primarySsrc = sourceTrack.getRTPEncodings()[0].getPrimarySSRC();
            AdaptiveTrackProjection adaptiveTrackProjection
                = adaptiveTrackProjectionMap.getOrDefault(primarySsrc, null);

            if (adaptiveTrackProjection == null)
            {
                logger.debug(destinationEndpoint.getID() + " is missing " +
                    "an adaptive track projection for endpoint=" +
                    sourceTrack.getOwner() + ", ssrc=" + primarySsrc);
                continue;
            }

            long targetBps = sourceTrack.getBitrateBps(nowMs,
                    adaptiveTrackProjection.getTargetIndex());
            if (targetBps > 0)
            {
                long ssrc = adaptiveTrackProjection.getTargetSsrc();
                if (ssrc > -1)
                {
                    activeSsrcs.add(ssrc);
                }
            }

            totalTargetBps += targetBps;
            // the sum of the bitrates (in bps) of the encodings that are the
            // closest to the ideal and has a bitrate. this is similar to how
            // we compute the ideal bitrate bellow in
            // {@link TrackBitrateAllocation#idealBitrate} and the logic should
            // be extracted in a utility method somehow.
            totalIdealBps += sourceTrack.getBitrateBps(nowMs,
                    adaptiveTrackProjection.getIdealIndex());
        }
        return new StatusSnapshot(totalTargetBps, totalIdealBps, activeSsrcs);
    }

    /**
     * We can't just use {@link #lastBwe} to get the most recent bandwidth
     * estimate as we must take into account the initial join period where
     * we don't 'trust' the bwe anyway, as well as whether or not we'll
     * trust it at all (we ignore it always if the endpoint doesn't support
     * RTX, which is our way of filtering out endpoints that don't do
     * probing)
     * @return the estimated bandwidth, in bps, based on the last update
     * we received and taking into account whether or not we 'trust' the
     * bandwidth estimate.
     */
    private long getAvailableBandwidth(long nowMs)
    {
        boolean trustBwe = Config.trustBwe();
        if (trustBwe)
        {
            // Ignore the bandwidth estimations in the first 10 seconds because
            // the REMBs don't ramp up fast enough. This needs to go but it's
            // related to our GCC implementation that needs to be brought up to
            // speed.
            if (firstMediaMs == -1 || nowMs - firstMediaMs < 10000)
            {
                trustBwe = false;
            }
        }

        if (!trustBwe || !supportsRtx)
        {
            return Long.MAX_VALUE;
        }
        else
        {
            return lastBwe;
        }
    }

    /**
     * Called when the estimated bandwidth for the endpoint to which this
     * BitrateController belongs has changed (which may therefore result in a
     * different set of streams being forwarded)
     * @param newBandwidthBps the newly estimated bandwidth in bps
     */
    public void bandwidthChanged(long newBandwidthBps)
    {
        if (timeSeriesLogger.isTraceEnabled())
        {
            timeSeriesLogger.trace(diagnosticContext
                .makeTimeSeriesPoint("new_bwe")
                .addField("bwe_bps", newBandwidthBps));
        }

        if (!changeIsLargerThanThreshold(lastBwe, newBandwidthBps))
        {
            logger.debug(() -> "New bandwidth (" + newBandwidthBps
                + ") is not significantly " +
                "changed from previous estimate (" + lastBwe + "), ignoring");
            // If this is a "negligible" change in the bandwidth estimation
            // wrt the last bandwidth estimation that we reacted to, then
            // do not update the bitrate allocation. The goal is to limit
            // the resolution changes due to bandwidth estimation changes,
            // as often resolution changes can negatively impact user
            // experience, at the risk of clogging the receiver pipe.
        }
        else
        {
            logger.debug(() -> "bandwidth has changed, updating");

            lastBwe = newBandwidthBps;
            update();
        }
    }

    /**
     * Called when the ordering of endpoints has changed in some way. This could
     * be due to an endpoint joining or leaving, or a
     * change in which endpoints are selected.
     *
     * @param conferenceEndpoints the endpoints of the conference sorted in
     * dominant speaker order (NOTE this does NOT take into account orderings
     * specific to this particular endpoint, e.g. selected or pinned, though
     * this method SHOULD be invoked when those things change; they will be
     * taken into account in this flow)
     */
    public synchronized void endpointOrderingChanged(List<String> conferenceEndpoints)
    {
        logger.debug(() -> " endpoint ordering has changed, updating");

        update();
    }

    /**
     * Computes a new bitrate allocation for every endpoint in the conference,
     * and updates the state of this instance so that bitrate allocation is
     * eventually met.
     *
     * @param conferenceEndpoints the ordered list of {@link Endpoint}s
     * participating in the multipoint conference with the dominant (speaker)
     * {@link Endpoint} at the beginning of the list i.e. the dominant speaker
     * history. This parameter is optional but it can be used for performance;
     * if it's omitted it will be fetched from the
     * {@link ConferenceSpeechActivity}.
     * @param bweBps the current bandwidth estimation (in bps)
     */
    private synchronized void update()
    {
        long nowMs = System.currentTimeMillis();

        long bweBps = getAvailableBandwidth(nowMs);

        // Compute the bitrate allocation.
        TrackBitrateAllocation[]
            trackBitrateAllocations = allocate(bweBps, destinationEndpoint.getConference().getEndpoints());

        Set<String> conferenceEndpointIds = new HashSet<>();

        // Accumulators used for tracing purposes.
        long totalIdealBps = 0, totalTargetBps = 0;
        int totalIdealIdx = 0, totalTargetIdx = 0;

        List<AdaptiveTrackProjection> adaptiveTrackProjections
                = new ArrayList<>();
        if (!ArrayUtils.isNullOrEmpty(trackBitrateAllocations))
        {
            for (TrackBitrateAllocation
                trackBitrateAllocation : trackBitrateAllocations)
            {
                conferenceEndpointIds.add(trackBitrateAllocation.endpointID);

                int trackTargetIdx = trackBitrateAllocation.getTargetIndex(),
                    trackIdealIdx = trackBitrateAllocation.getIdealIndex();

                // Review this.
                AdaptiveTrackProjection adaptiveTrackProjection
                    = lookupOrCreateAdaptiveTrackProjection(trackBitrateAllocation);

                if (adaptiveTrackProjection != null)
                {
                    adaptiveTrackProjections.add(adaptiveTrackProjection);
                    adaptiveTrackProjection.setTargetIndex(trackTargetIdx);
                    adaptiveTrackProjection.setIdealIndex(trackIdealIdx);

                    if (trackBitrateAllocation.track != null
                            && enableVideoQualityTracing)
                    {
                        long trackTargetBps
                            = trackBitrateAllocation.getTargetBitrate();
                        long trackIdealBps
                            = trackBitrateAllocation.getIdealBitrate();
                        totalTargetBps += trackTargetBps;
                        totalIdealBps += trackIdealBps;
                        totalTargetIdx += trackTargetIdx;
                        totalIdealIdx += trackIdealIdx;
                        // time series that tracks how a media stream track
                        // gets forwarded to a specific receiver.
                        timeSeriesLogger.trace(diagnosticContext
                            .makeTimeSeriesPoint("track_quality", nowMs)
                            .addField("track_id",
                                trackBitrateAllocation.track.hashCode())
                            .addField("target_idx", trackTargetIdx)
                            .addField("ideal_idx", trackIdealIdx)
                            .addField("target_bps", trackTargetBps)
                            .addField("oversending",
                                trackBitrateAllocation.oversending)
                            .addField("preferred_idx",
                                trackBitrateAllocation.getPreferredIndex())
                            .addField("remote_endpoint_id",
                                trackBitrateAllocation.endpointID)
                            .addField("ideal_bps", trackIdealBps));
                    }
                }
            }
        }
        else
        {
            for (AdaptiveTrackProjection adaptiveTrackProjection
                : adaptiveTrackProjectionMap.values())
            {
                if (enableVideoQualityTracing)
                {
                    totalIdealIdx--;
                    totalTargetIdx--;
                }
                adaptiveTrackProjection
                    .setTargetIndex(RTPEncodingDesc.SUSPENDED_INDEX);
                adaptiveTrackProjection
                    .setIdealIndex(RTPEncodingDesc.SUSPENDED_INDEX);
            }
        }

        if (enableVideoQualityTracing)
        {
            timeSeriesLogger.trace(diagnosticContext
                    .makeTimeSeriesPoint("did_update", nowMs)
                    .addField("total_target_idx", totalTargetIdx)
                    .addField("total_ideal_idx", totalIdealIdx)
                    .addField("bwe_bps", bweBps)
                    .addField("total_target_bps", totalTargetBps)
                    .addField("total_ideal_bps", totalIdealBps));
        }

        // The BandwidthProber will pick this up.
        this.adaptiveTrackProjections
            = Collections.unmodifiableList(adaptiveTrackProjections);
    }

    /**
     * Utility method that looks-up or creates the adaptive track projection of
     * a track.
     *
     * @param trackBitrateAllocation the track bitrate allocation
     * @return the adaptive track projection for the track bitrate allocation
     * that is specified as an argument.
     */
    private AdaptiveTrackProjection
    lookupOrCreateAdaptiveTrackProjection(
        TrackBitrateAllocation trackBitrateAllocation)
    {
        synchronized (adaptiveTrackProjectionMap)
        {
            AdaptiveTrackProjection adaptiveTrackProjection
                = adaptiveTrackProjectionMap.get(
                        trackBitrateAllocation.targetSSRC);

            if (adaptiveTrackProjection != null
                || trackBitrateAllocation.track == null)
            {
                return adaptiveTrackProjection;
            }

            RTPEncodingDesc[] rtpEncodings
                = trackBitrateAllocation.track.getRTPEncodings();

            if (ArrayUtils.isNullOrEmpty(rtpEncodings))
            {
                return null;
            }

            // XXX the lambda keeps a reference to the trackBitrateAllocation
            // (a short lived object under normal circumstances) which keeps
            // a reference to the Endpoint object that it refers to. That
            // can cause excessive object retention (i.e. the endpoint is expired
            // but a reference persists in the adaptiveTrackProjectionMap). We're
            // creating local final variables and pass that to the lambda function
            // in order to avoid that.
            final String endpointID = trackBitrateAllocation.endpointID;
            final long targetSSRC = trackBitrateAllocation.targetSSRC;
            adaptiveTrackProjection
                = new AdaptiveTrackProjection(
                    diagnosticContext,
                    trackBitrateAllocation.track, () ->
                        destinationEndpoint.getConference().requestKeyframe(
                            endpointID, targetSSRC),
                    payloadTypes,
                    logger);

            logger.debug(() -> "new track projection for " + trackBitrateAllocation.track);

            // Route all encodings to the specified bitrate controller.
            for (RTPEncodingDesc rtpEncoding : rtpEncodings)
            {
                adaptiveTrackProjectionMap.put(
                    rtpEncoding.getPrimarySSRC(), adaptiveTrackProjection);
            }

            return adaptiveTrackProjection;
        }
    }

    /**
     * Computes the ideal and the target bitrate, limiting the target to be
     * less than bandwidth estimation specified as an argument.
     *
     * @param maxBandwidth the max bandwidth estimation that the target bitrate
     * must not exceed.
     * @param conferenceEndpoints the ordered list of {@link Endpoint}s
     * participating in the multipoint conference with the dominant (speaker)
     * {@link Endpoint} at the beginning of the list i.e. the dominant speaker
     * history. This parameter is optional but it can be used for performance;
     * if it's omitted it will be fetched from the
     * {@link ConferenceSpeechActivity}.
     * @return an array of {@link TrackBitrateAllocation}.
     */
    private TrackBitrateAllocation[] allocate(
        long maxBandwidth, List<AbstractEndpoint> conferenceEndpoints)
    {
        TrackBitrateAllocation[] trackBitrateAllocations
                = prioritize(conferenceEndpoints);

        if (ArrayUtils.isNullOrEmpty(trackBitrateAllocations))
        {
            return trackBitrateAllocations;
        }

        long oldMaxBandwidth = 0;

        int oldStateLen = 0;
        int[] oldRatedTargetIndices = new int[trackBitrateAllocations.length];
        int[] newRatedTargetIndices = new int[trackBitrateAllocations.length];
        Arrays.fill(newRatedTargetIndices, -1);

        while (oldMaxBandwidth != maxBandwidth)
        {
            oldMaxBandwidth = maxBandwidth;
            System.arraycopy(newRatedTargetIndices, 0,
                oldRatedTargetIndices, 0, oldRatedTargetIndices.length);

            int newStateLen = 0;
            for (int i = 0; i < trackBitrateAllocations.length; i++)
            {
                TrackBitrateAllocation trackBitrateAllocation
                    = trackBitrateAllocations[i];

                maxBandwidth += trackBitrateAllocation.getTargetBitrate();
                trackBitrateAllocation.improve(maxBandwidth);
                maxBandwidth -= trackBitrateAllocation.getTargetBitrate();

                newRatedTargetIndices[i]
                    = trackBitrateAllocation.ratedTargetIdx;
                if (trackBitrateAllocation.getTargetIndex() > -1)
                {
                    newStateLen++;
                }

                if (trackBitrateAllocation.ratedTargetIdx
                    < trackBitrateAllocation.ratedPreferredIdx)
                {
                    break;
                }
            }

            if (oldStateLen > newStateLen)
            {
                // rollback state to prevent jumps in the number of forwarded
                // participants.
                for (int i = 0; i < trackBitrateAllocations.length; i++)
                {
                    trackBitrateAllocations[i].ratedTargetIdx
                        = oldRatedTargetIndices[i];
                }

                break;
            }

            oldStateLen = newStateLen;
        }

        // at this point, maxBandwidth is what we failed to allocate.

        return trackBitrateAllocations;
    }

    /**
     * Returns a prioritized {@link TrackBitrateAllocation} array where
     * selected endpoint are at the top of the array, followed by the pinned
     * endpoints, finally followed by any other remaining endpoints. The
     * priority respects the order induced by the <tt>conferenceEndpoints</tt>
     * parameter.
     *
     * @param conferenceEndpoints the ordered list of {@link Endpoint}s
     * participating in the multipoint conference with the dominant (speaker)
     * {@link Endpoint} at the beginning of the list i.e. the dominant speaker
     * history. This parameter is optional but it can be used for performance;
     * if it's omitted it will be fetched from the
     * {@link ConferenceSpeechActivity}.
     * @return a prioritized {@link TrackBitrateAllocation} array where
     * selected endpoint are at the top of the array, followed by the pinned
     * endpoints, finally followed by any other remaining endpoints.
     */
    private TrackBitrateAllocation[] prioritize(
        List<AbstractEndpoint> conferenceEndpoints)
    {
        // Init.
        List<TrackBitrateAllocation> trackBitrateAllocations
            = new ArrayList<>();

        if (logger.isDebugEnabled())
        {
            logger.debug("Prioritizing endpoints" +
                ", sorted endpoint list: " +
                conferenceEndpoints.stream().map(AbstractEndpoint::getID).collect(Collectors.joining(", ")) +
                ". Pinned endpoints: " + String.join(", ", pinnedEndpointIds));
        }

        int endpointPriority = 0;

        // Then, bubble-up the pinned endpoints.
        if (!pinnedEndpointIds.isEmpty())
        {
            for (Iterator<AbstractEndpoint> it = conferenceEndpoints.iterator();
                 it.hasNext();)
            {
                AbstractEndpoint sourceEndpoint = it.next();
                if (sourceEndpoint.isExpired()
                    || sourceEndpoint.getID().equals(destinationEndpoint.getID())
                    || !pinnedEndpointIds.contains(sourceEndpoint.getID()))
                {
                    logger.trace(() -> "Endpoint " + sourceEndpoint.getID() + " is expired, is this destination, or " +
                        "is not pinned; ignoring");
                    continue;
                }

                MediaStreamTrackDesc[] tracks
                    = sourceEndpoint.getMediaStreamTracks();

                if (!ArrayUtils.isNullOrEmpty(tracks))
                {
                    for (MediaStreamTrackDesc track : tracks)
                    {
                        trackBitrateAllocations.add(
                            endpointPriority, new TrackBitrateAllocation(
                                sourceEndpoint,
                                track,
                                maxRxFrameHeightPx,
                                maxRxFrameTemporalLayerId));
                    }

                    logger.trace(() -> "Adding pinned endpoint " + sourceEndpoint.getID() + " to allocations");
                    endpointPriority++;
                }

                it.remove();
            }
        }

        return trackBitrateAllocations.toArray(new TrackBitrateAllocation[0]);
    }

    /**
     * Set the max receive frame height, in pixels, the endpoint to which this
     * {@link BitrateController} belongs is willing to receive
     * @param maxRxFrameHeightPx the max frame height, in pixels
     */
    public void setMaxRxFrameHeightPx(int maxRxFrameHeightPx)
    {
        if (this.maxRxFrameHeightPx != maxRxFrameHeightPx)
        {
            this.maxRxFrameHeightPx = maxRxFrameHeightPx;

            logger.debug(() -> "setting max receive frame height to " +
                    + maxRxFrameHeightPx + "px, updating");

            update();
        }
    }

    public void setMaxRxFrameTemporalLayerId(int maxRxFrameTemporalLayerId)
    {
        if (this.maxRxFrameTemporalLayerId != maxRxFrameTemporalLayerId)
        {
            this.maxRxFrameTemporalLayerId = maxRxFrameTemporalLayerId;

            logger.debug(() -> "setting max receive frame temporal layer id to " +
                    + maxRxFrameTemporalLayerId + "fps, updating");

            update();
        }
    }

    /**
     * Set the endpoint IDs the endpoint to which this
     * {@link BitrateController} belongs has pinned
     * @param pinnedEndpointIds the endpoint IDs the endpoint to which this
     *                            {@link BitrateController} belongs has pinned
     */
    public void setPinnedEndpointIds(Set<String> pinnedEndpointIds)
    {
        if (!this.pinnedEndpointIds.equals(pinnedEndpointIds))
        {
            this.pinnedEndpointIds = new HashSet<>(pinnedEndpointIds);
            update();
        }
    }

    /**
     * Adds a payload type.
     */
    public void addPayloadType(PayloadType payloadType)
    {
        payloadTypes.put(payloadType.getPt(), payloadType);

        if (payloadType.getEncoding() == PayloadTypeEncoding.RTX)
        {
            supportsRtx = true;
        }
    }

    /**
     * Transforms a video RTP packet.
     * @param packetInfo the video rtp packet
     * @return true if the packet was successfully transformed in place; false if
     * if the given packet is not accepted and should
     * be dropped.
     */
    public boolean transformRtp(@NotNull PacketInfo packetInfo)
    {
        VideoRtpPacket videoPacket = (VideoRtpPacket)packetInfo.getPacket();
        if (firstMediaMs == -1)
        {
            firstMediaMs = System.currentTimeMillis();
        }

        Long ssrc = videoPacket.getSsrc();
        AdaptiveTrackProjection adaptiveTrackProjection
                = adaptiveTrackProjectionMap.get(ssrc);

        if (adaptiveTrackProjection == null)
        {
            return false;
        }

        try
        {
            adaptiveTrackProjection.rewriteRtp(packetInfo);

            // The rewriteRtp operation must not modify the VP8 payload.
            if (PacketInfo.Companion.getENABLE_PAYLOAD_VERIFICATION())
            {
                String expected = packetInfo.getPayloadVerification();
                String actual = videoPacket.getPayloadVerification();
                if (!"".equals(expected) && !expected.equals(actual))
                {
                    logger.warn(
                        "Payload unexpectedly modified! Expected: " + expected
                            + ", actual: " + actual);
                }
            }

            return true;
        }
        catch (RewriteException e)
        {
            logger.warn("Failed to rewrite a packet.", e);
            return false;
        }
    }


    /**
     * A snapshot of the bitrate for a given {@link RTPEncodingDesc}.
     */
    static class RateSnapshot
    {
        /**
         * The bitrate (in bps) of the associated {@link #encoding}.
         */
        final long bps;

        /**
         * The {@link RTPEncodingDesc}.
         */
        final RTPEncodingDesc encoding;

        /**
         * Ctor.
         *
         * @param bps The bitrate (in bps) of the associated {@link #encoding}.
         * @param encoding the {@link RTPEncodingDesc}.
         */
        private RateSnapshot(long bps, RTPEncodingDesc encoding)
        {
            this.bps = bps;
            this.encoding = encoding;
        }
    }

    /**
     * A bitrate allocation that pertains to a specific {@link Endpoint}.
     *
     * @author George Politis
     */
    private class TrackBitrateAllocation
    {
        /**
         * The ID of the {@link Endpoint} that this instance pertains to.
         */
        private final String endpointID;

        /**
         * Helper field that keeps the SSRC of the target stream.
         */
        private final long targetSSRC;

        /**
         * The first {@link MediaStreamTrackDesc} of the {@link Endpoint} that
         * this instance pertains to.
         */
        private final MediaStreamTrackDesc track;

        /**
         * An array that holds the stable bitrate snapshots of the
         * {@link RTPEncodingDesc}s that this {@link #track} offers.
         *
         * {@link RTPEncodingDesc} of {@link #track}.
         */
        private final RateSnapshot[] ratedIndices;

        /**
         * The rated quality that needs to be achieved before allocating
         * bandwidth for any of the other subsequent tracks in this allocation
         * decision. The rated quality is not necessarily equal to the encoding
         * quality. For example, for the on-stage participant we consider 5
         * rated qualities:
         *
         * 0 -> 180p7.5, 1 -> 180p15, 2 -> 180p30, 3 -> 360p30, 4 -> 720p30.
         *
         * The encoding quality of the 4th rated quality is 8.
         */
        private final int   ratedPreferredIdx;

        /**
         * The current rated quality target for this track. It can potentially
         * be improved in the improve step, provided there is enough bandwidth.
         */
        private int ratedTargetIdx = -1;

        /**
         * A boolean that indicates whether or not we're force pushing through
         * the bottleneck this track.
         */
        private boolean oversending = false;

        /**
         * the bitrate (in bps) of the encoding that is the closest to the ideal
         * and has a bitrate, or 0 if there are no encodings with a bitrate (for
         * example, the endpoint is video muted).
         */
        private final long idealBitrate;

        /**
         * Ctor.
         *
         * @param endpoint the {@link Endpoint} that this bitrate allocation
         * pertains to.
         * @param track the {@link MediaStreamTrackDesc} that this bitrate
         * allocation pertains to.
         */
        private TrackBitrateAllocation(
            AbstractEndpoint endpoint, MediaStreamTrackDesc track,
            int maxFrameHeight, int maxFrameTemporalLayerId)
        {
            this.endpointID = endpoint.getID();
            this.track = track;

            RTPEncodingDesc[] encodings;
            if (track == null)
            {
                this.targetSSRC = -1;
                encodings = null;
            }
            else
            {
                encodings = track.getRTPEncodings();

                if (ArrayUtils.isNullOrEmpty(encodings))
                {
                    this.targetSSRC = -1;
                }
                else
                {
                    this.targetSSRC = encodings[0].getPrimarySSRC();
                }
            }

            if (targetSSRC == -1)
            {
                ratedPreferredIdx = -1;
                idealBitrate = 0;
                ratedIndices = EMPTY_RATE_SNAPSHOT_ARRAY;
                return;
            }

            long nowMs = System.currentTimeMillis();
            List<RateSnapshot> ratesList = new ArrayList<>();
            int ratedPreferredIdx = 0;
            long idealBps = 0;
            for (RTPEncodingDesc encoding : encodings)
            {
                if (maxFrameHeight >= 0 && encoding.getHeight() > maxFrameHeight)
                {
                    continue;
                }
                if (maxFrameTemporalLayerId >= 0 && encoding.getTid() > maxFrameTemporalLayerId)
                {
                    continue;
                }
                long encodingBitrateBps = encoding.getBitrateBps(nowMs);
                if (encodingBitrateBps > 0)
                {
                    idealBps = encodingBitrateBps;
                }
                ratesList.add(
                        new RateSnapshot(encodingBitrateBps, encoding));
            }

            this.idealBitrate = idealBps;

            ratedPreferredIdx = ratesList.size() - 1;

            if (timeSeriesLogger.isTraceEnabled())
            {
                DiagnosticContext.TimeSeriesPoint ratesTimeSeriesPoint
                    = diagnosticContext.makeTimeSeriesPoint("calculated_rates")
                    .addField("remote_endpoint_id", endpointID);
                for (RateSnapshot rateSnapshot : ratesList) {
                    ratesTimeSeriesPoint.addField(
                        Integer.toString(rateSnapshot.encoding.getIndex()),
                        rateSnapshot.bps);
                }
                timeSeriesLogger.trace(ratesTimeSeriesPoint);
            }

            this.ratedPreferredIdx = ratedPreferredIdx;
            ratedIndices = ratesList.toArray(new RateSnapshot[0]);
            // TODO Determining the rated ideal index needs some work.
            // The ideal rated quality is constrained by the viewport of the
            // endpoint. For example, on a mobile device we should probably not
            // send anything above 360p (not even the on-stage participant). On
            // a laptop computer 720p seems reasonable and on a big screen 1080p
            // or above.
        }

        /**
         * Computes the ideal and the target bitrate, limiting the target to
         * be less than bandwidth estimation specified as an argument.
         *
         * @param maxBps the maximum bitrate (in bps) that the target subjective
         * quality can have.
         */
        private void improve(long maxBps)
        {
            if (ratedIndices.length == 0)
            {
                return;
            }

            if (ratedTargetIdx == -1)
            {
                if (!Config.enableOnstageVideoSuspend())
                {
                    ratedTargetIdx = 0;
                    oversending = ratedIndices[0].bps > maxBps;
                }

                // Boost on stage participant to 360p, if there's enough bw.
                for (int i = ratedTargetIdx + 1; i < ratedIndices.length; i++)
                {
                    if (i > ratedPreferredIdx || maxBps < ratedIndices[i].bps)
                    {
                        break;
                    }

                    ratedTargetIdx = i;
                }
            }
            else
            {
                // Try the next element in the ratedIndices array.
                if (ratedTargetIdx + 1 < ratedIndices.length
                    && ratedIndices[ratedTargetIdx + 1].bps < maxBps)
                {
                    ratedTargetIdx++;
                }
            }

            if (ratedTargetIdx > -1)
            {
                // if there's a better subjective quality with the same or less
                // bitrate than the current target quality, make it the target.
                // i.e. set the target to the next best available quality with
                // the least possible bitrate.
                //
                // For example, if 1080p@15fps is configured as a better
                // subjective quality than 720p@30fps (i.e. it sits on a higher
                // index in the ratedIndices array) and the bitrate that we
                // measure for the 1080p stream is less than the bitrate that we
                // measure for the 720p stream, then we "jump over" the 720p
                // stream and immediately select the 1080p stream.
                for (int i = ratedTargetIdx + 1; i < ratedIndices.length; i++)
                {
                    if (ratedIndices[i].bps > 0
                        && ratedIndices[i].bps <= ratedIndices[ratedTargetIdx].bps)
                    {
                        ratedTargetIdx = i;
                    }
                }
            }
        }

        /**
         * Gets the target bitrate (in bps) for this endpoint allocation.
         *
         * @return the target bitrate (in bps) for this endpoint allocation.
         */
        private long getTargetBitrate()
        {
            return ratedTargetIdx != -1 ? ratedIndices[ratedTargetIdx].bps : 0;
        }

        /**
         * @return the bitrate (in bps) of the encoding that is the closest to
         * the ideal and has a bitrate, or 0 if there are no encodings with a
         * bitrate (for example, the endpoint is video muted).
         */
        private long getIdealBitrate()
        {
            return idealBitrate;
        }

        /**
         * Gets the target quality for this track.
         *
         * @return the target quality for this track.
         */
        private int getTargetIndex()
        {
            // figures out the quality of the encoding of the target rated
            // quality.
            return ratedTargetIdx != -1
                ? ratedIndices[ratedTargetIdx].encoding.getIndex() : -1;
        }

        /**
         * Gets the preferred quality for this track.
         *
         * @return the preferred quality for this track.
         */
        private int getPreferredIndex()
        {
            // figures out the quality of the encoding of the target rated
            // quality.
            return ratedPreferredIdx != -1
                ? ratedIndices[ratedPreferredIdx].encoding.getIndex() : -1;
        }

        /**
         * Gets the ideal quality for this track.
         *
         * @return the ideal quality for this track.
         */
        private int getIdealIndex()
        {
            // figures out the quality of the encoding of the ideal rated
            // quality.
            return ratedIndices.length != 0
                ? ratedIndices[ratedIndices.length - 1].encoding.getIndex() : -1;
        }
    }
}

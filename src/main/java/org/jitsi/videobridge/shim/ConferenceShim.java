/*
 * Copyright @ 2019-Present 8x8, Inc
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
package org.jitsi.videobridge.shim;

import org.jetbrains.annotations.*;
import org.jitsi.nlj.format.*;
import org.jitsi.nlj.rtp.*;
import org.jitsi.utils.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.octo.*;
import org.jitsi.videobridge.rest.root.colibri.ColibriEndpointIQ;
import org.jitsi.videobridge.util.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.json.simple.JSONObject;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.*;

import static org.jitsi.videobridge.ConfAudioProcessorTransport.AUDIO_MIXER_EP_ID;

/**
 * Handles Colibri-related logic for a {@link Conference}, e.g.
 * creates/expires contents, describes the conference in XML.
 *
 * @author Brian Baldino
 * @author Boris Grozev
 */
public class ConferenceShim
{
    /**
     * The {@link Logger} used by the {@link ConferenceShim} class to print
     * debug information.
     */
    private final Logger logger;

    /**
     * The corresponding {@link Conference}.
     */
    public final Conference conference;

    /**
     * The list of contents in this conference.
     */
    private final Map<MediaType, ContentShim> contents = new HashMap<>();

    /**
     * Initializes a new {@link ConferenceShim} instance.
     *
     * @param conference the corresponding conference.
     */
    public ConferenceShim(Conference conference, Logger parentLogger)
    {
        this.logger = parentLogger.createChildLogger(ConferenceShim.class.getName());
        this.conference = conference;
    }

    /**
     * Gets the content of type {@code type}, creating it if necessary.
     *
     * @param type the media type of the content to add.
     *
     * @return the content.
     */
    public ContentShim getOrCreateContent(MediaType type)
    {
        synchronized (contents)
        {
            return contents.computeIfAbsent(type,
                    key -> new ContentShim(getConference(), type, logger));
        }
    }

    /**
     * @return the corresponding conference.
     */
    public Conference getConference()
    {
        return conference;
    }

    /**
     * Gets a copy of the list of contents.
     *
     * @return
     */
    public Collection<ContentShim> getContents()
    {
        synchronized (contents)
        {
            return new ArrayList<>(contents.values());
        }
    }

    /**
     * Describes the channel bundles of this conference in a Colibri IQ.
     * @param iq the IQ to describe in.
     */
    void describeChannelBundles(ColibriConferenceIQ iq, Set<String> endpoints)
    {
        for (AbstractEndpoint endpoint : conference.getEndpoints())
        {
            if (endpoints.size() > 0 && !endpoints.contains(endpoint.getID())) {
                continue;
            }
            String endpointId = endpoint.getID();
            ColibriConferenceIQ.ChannelBundle responseBundleIQ
                    = new ColibriConferenceIQ.ChannelBundle(endpointId);
            endpoint.describe(responseBundleIQ);

            iq.addChannelBundle(responseBundleIQ);
        }
    }

    /**
     * Adds the endpoint of this <tt>Conference</tt> as
     * <tt>ColibriConferenceIQ.Endpoint</tt> instances in <tt>iq</tt>.
     * @param iq the <tt>ColibriConferenceIQ</tt> in which to describe.
     */
    void describeEndpoints(ColibriConferenceIQ iq, Set<String> endpoints)
    {
        Predicate<AbstractEndpoint> predicate = x -> !AUDIO_MIXER_EP_ID.equals(x.getID());
        if(endpoints.size() > 0) {
            predicate = predicate.and(x -> x instanceof OctoEndpoint || endpoints.contains(x.getID()));
        }
        conference.getEndpoints().stream()
                .filter(predicate)
                .forEach(en -> iq.addEndpoint(new ColibriEndpointIQ(en.getID(), en.getUuid(), en.getStatsId(), en.getDisplayName())));
    }

    /**
     * Sets the attributes of this conference to an IQ.
     */
    public void describeShallow(ColibriConferenceIQ iq)
    {
        iq.setID(conference.getID());
        iq.setGID(conference.getGid());
        iq.setName(conference.getName());
    }

    /**
     * Gets the ID of the conference.
     */
    public String getId()
    {
        return conference.getID();
    }

    /**
     * Sets the values of the properties of a specific
     * <tt>ColibriConferenceIQ</tt> to the values of the respective
     * properties of this instance. Thus, the specified <tt>iq</tt> may be
     * thought of as a description of this instance.
     * <p>
     * <b>Note</b>: The copying of the values is deep i.e. the
     * <tt>Contents</tt>s of this instance are described in the specified
     * <tt>iq</tt>.
     * </p>
     *
     * @param iq the <tt>ColibriConferenceIQ</tt> to set the values of the
     * properties of this instance on
     */
    public void describeDeep(ColibriConferenceIQ iq, Set<String> endpoints)
    {
        describeShallow(iq);

        for (ContentShim contentShim : getContents())
        {
            ColibriConferenceIQ.Content contentIQ
                = iq.getOrCreateContent(contentShim.getMediaType().toString());

            for (ChannelShim channelShim : contentShim.getChannelShims())
            {
                if (endpoints.size() > 0 && !endpoints.contains(channelShim.getEndpoint().getID())) {
                    continue;
                }
                if (channelShim instanceof SctpConnectionShim)
                {
                    ColibriConferenceIQ.SctpConnection sctpConnectionIQ
                        = new ColibriConferenceIQ.SctpConnection();
                    channelShim.describe(sctpConnectionIQ);
                    contentIQ.addSctpConnection(sctpConnectionIQ);
                }
                else
                {
                    ColibriConferenceIQ.Channel channelIQ
                        = new ColibriConferenceIQ.Channel();

                    channelShim.describe(channelIQ);
                    contentIQ.addChannel(channelIQ);
                }
            }
        }
        describeEndpoints(iq, endpoints);
        describeChannelBundles(iq, endpoints);
    }

    /**
     * Processes the Octo channels from a Colibri request.
     */
    public void processOctoChannels(
                @NotNull ColibriConferenceIQ.Channel audioChannel,
                @NotNull ColibriConferenceIQ.Channel videoChannel)
    {
        ConfOctoTransport tentacle = conference.getTentacle();

        int expire
                = Math.min(audioChannel.getExpire(), videoChannel.getExpire());
        if (expire == 0)
        {
            tentacle.expire();
        }
        else if (audioChannel instanceof ColibriConferenceIQ.OctoChannel
            && videoChannel instanceof ColibriConferenceIQ.OctoChannel)
        {
            ColibriConferenceIQ.OctoChannel audioOctoChannel
                    = (ColibriConferenceIQ.OctoChannel) audioChannel;
            ColibriConferenceIQ.OctoChannel videoOctoChannel
                    = (ColibriConferenceIQ.OctoChannel) videoChannel;

            Set<String> relays = new HashSet<>(audioOctoChannel.getRelays());
            relays.addAll(videoOctoChannel.getRelays());
            tentacle.setRelays(relays);
        }

        Set<RTPHdrExtPacketExtension> headerExtensions
                = new HashSet<>(audioChannel.getRtpHeaderExtensions());
        headerExtensions.addAll(videoChannel.getRtpHeaderExtensions());

        // Like for payload types, we never clear the transceiver's list of RTP
        // header extensions. See the note in #addPayloadTypes.
        headerExtensions.forEach(ext -> {
                RtpExtension rtpExtension = ChannelShim.createRtpExtension(ext);
                if (rtpExtension != null)
                {
                    tentacle.addRtpExtension(rtpExtension);
                }
        });

        Map<PayloadTypePacketExtension, MediaType> payloadTypes
                = new HashMap<>();
        audioChannel.getPayloadTypes()
                .forEach(ext -> payloadTypes.put(ext, MediaType.AUDIO));
        videoChannel.getPayloadTypes()
                .forEach(ext -> payloadTypes.put(ext, MediaType.VIDEO));

        payloadTypes.forEach((ext, mediaType) -> {
            PayloadType pt = PayloadTypeUtil.create(ext, mediaType);
            if (pt == null)
            {
                logger.warn("Unrecognized payload type " + ext.toXML());
            }
            else
            {
                tentacle.addPayloadType(pt);
            }
        });

        tentacle.setSources(
                audioChannel.getSources(),
                videoChannel.getSources(),
                videoChannel.getSourceGroups());
    }

    public void processMixerAudioChannel(@NotNull ColibriConferenceIQ.Channel mixerAudioChannel) {
        ConfAudioProcessorTransport audioMixer = conference.getAudioProcessor();

        int expire = mixerAudioChannel.getExpire();
        if (expire == 0) {
            audioMixer.expire();
        }
        mixerAudioChannel.getPayloadTypes().forEach(ext -> {
            PayloadType pt = PayloadTypeUtil.create(ext, MediaType.AUDIO);
            if (pt == null)
            {
                logger.warn("Unrecognized payload type " + ext.toXML());
            }
            else
            {
                audioMixer.addPayloadType(pt);
            }
        });

    }

    /**
     * Process whole {@link ColibriConferenceIQ} and initialize all signaled
     * endpoints that have not been initialized before.
     * @param conferenceIQ conference IQ having endpoints
     */
    void initializeSignaledEndpoints(ColibriConferenceIQ conferenceIQ)
        throws VideobridgeShim.IqProcessingException
    {
        Map<String,List<ColibriConferenceIQ.ChannelCommon>> nonExpiredChannels
            = conferenceIQ.getContents().stream()
                .flatMap(content ->
                        Stream.concat(
                                content.getChannels().stream(),
                                content.getSctpConnections().stream()
                        )
                )
                .filter(c -> c.getEndpoint() != null && c.getExpire() != 0)
                .collect(Collectors.groupingBy(ColibriConferenceIQ.ChannelCommon::getEndpoint));

        for (String endpoint : nonExpiredChannels.keySet())
        {
            List<ColibriConferenceIQ.ChannelCommon> channels = nonExpiredChannels.get(endpoint);
            boolean iceControlling = channels.stream().anyMatch(x->Boolean.TRUE.equals(x.isInitiator()));
            boolean shadow = conferenceIQ.getEndpoints().stream()
                    .anyMatch(x -> Objects.equals(endpoint, x.getId()) && "shadow".equals(x.getDisplayName()));
            boolean allowIncomingMedia = channels.stream()
                    .filter(x -> x instanceof ColibriConferenceIQ.Channel)
                    .map(x -> (ColibriConferenceIQ.Channel)x)
                    .map(ColibriConferenceIQ.Channel::getDirection)
                    .anyMatch(x -> "sendrecv".equals(x) || "recvonly".equals(x));

            JSONObject notificationInfo = new JSONObject();
            notificationInfo.put("endpointAllowIncomingMedia", allowIncomingMedia);
            ensureEndpointCreated(endpoint, iceControlling, shadow, notificationInfo);
        }

        for (ColibriConferenceIQ.Endpoint endpoint : conferenceIQ.getEndpoints())
        {
            ensureEndpointCreated(endpoint.getId(), false, "shadow".equals(endpoint.getDisplayName()), null);
        }

        for (ColibriConferenceIQ.ChannelBundle channelBundle : conferenceIQ.getChannelBundles())
        {
            ensureEndpointCreated(channelBundle.getId(), false, false, null);
        }
    }

    /**
     * Checks if endpoint with specified ID is initialized, if endpoint does not
     * exist in a conference, it will be created and initialized.
     * @param endpointId identifier of endpoint to check and initialize
     * @param iceControlling ICE control role of transport of newly created
     * endpoint
     */
    private void ensureEndpointCreated(String endpointId, boolean iceControlling, boolean shadow, JSONObject infoForNotification)
    {
        if (conference.getLocalEndpoint(endpointId) != null)
        {
            return;
        }

        conference.createLocalEndpoint(endpointId, iceControlling, shadow, infoForNotification);
    }

    /**
     * Updates an <tt>Endpoint</tt> of this <tt>Conference</tt> with the
     * information contained in <tt>colibriEndpoint</tt>. The ID of
     * <tt>colibriEndpoint</tt> is used to select the <tt>Endpoint</tt> to
     * update.
     *
     * @param colibriEndpoint a <tt>ColibriConferenceIQ.Endpoint</tt> instance
     * that contains information to be set on an <tt>Endpoint</tt> instance of
     * this <tt>Conference</tt>.
     */
    void updateEndpoint(ColibriConferenceIQ.Endpoint colibriEndpoint)
    {
        String id = colibriEndpoint.getId();

        if (id != null)
        {
            AbstractEndpoint endpoint = conference.getEndpoint(id);

            if (endpoint != null)
            {
                endpoint.setDisplayName(colibriEndpoint.getDisplayName());
                endpoint.setStatsId(colibriEndpoint.getStatsId());
            }
        }
    }
}

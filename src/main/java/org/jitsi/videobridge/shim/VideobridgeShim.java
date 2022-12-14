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

import org.jitsi.utils.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.jitsi.xmpp.util.*;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;

import java.util.*;

/**
 * Handles Colibri-related logic for a {@link Videobridge}, e.g. handles
 * incoming Colibri requests.
 *
 * @author Brian Baldino
 * @author Boris Grozev
 */
public class VideobridgeShim
{
    /**
     * The {@link Logger} used by the {@link VideobridgeShim} class and its
     * instances to print debug information.
     */
    private static final Logger logger = new LoggerImpl(VideobridgeShim.class.getName());

    /**
     * Checks if a {@link ColibriConferenceIQ.Channel} refers to an Octo
     * channel.
     *
     * Some requests do not explicitly set the 'type' attribute to 'octo',
     * resulting in the element being parsed as a regular
     * {@link ColibriConferenceIQ.Channel}. To cover this case we check if the
     * ID is one of the IDs we use for Octo.
     *
     * @param channel the channel to check
     * @return {@code true} if the Channel is an Octo channel.
     */
    private static boolean isOctoChannel(ColibriConferenceIQ.Channel channel)
    {
        if (channel instanceof ColibriConferenceIQ.OctoChannel)
        {
            return true;
        }

        if (channel != null)
        {
            String id = channel.getID();
            return id != null &&
                    (id.equals(getOctoChannelId(MediaType.AUDIO))
                    || id.equals(getOctoChannelId(MediaType.VIDEO)));
        }

        return false;
    }

    private static boolean isMixerAudioChannel(ColibriConferenceIQ.Channel channel) {
        if (channel instanceof ColibriConferenceIQ.OctoChannel)
        {
            return false;
        }

        if (channel != null) {
            return channel.getRTPLevelRelayType() == RTPLevelRelayType.MIXER;
        }

        return false;
    }

    /**
     * Gets the channel ID we will use for the Octo channel with a given media
     * type.
     * @param mediaType
     * @return
     */
    private static String getOctoChannelId(MediaType mediaType)
    {
        return "octo-" + mediaType;
    }

    /**
     * Processes a list of {@code Channel} elements in a specific
     * {@link ContentShim}.
     *
     * @param channelIqs the list of channel elements.
     * @param contentShim the associated {@code ContentShim}
     * @return the list of channel elements that have been created and updated
     * (but haven't been expired).
     * @throws IqProcessingException
     */
    private static List<ColibriConferenceIQ.Channel> processChannels(
            List<ColibriConferenceIQ.Channel> channelIqs,
            ContentShim contentShim)
            throws IqProcessingException
    {
        List<ColibriConferenceIQ.Channel> createdOrUpdatedChannels
                = new ArrayList<>();

        for (ColibriConferenceIQ.Channel channelIq : channelIqs)
        {
            // Octo channels are handled separately.
            if (isOctoChannel(channelIq) || isMixerAudioChannel(channelIq))
            {
                continue;
            }

            ChannelShim channelShim
                    = contentShim.getOrCreateChannelShim(channelIq);
            if (channelShim == null)
            {
                // A channel expire request which was handled successfully.
                continue;
            }


            channelShim.setDirection(channelIq.getDirection());
            channelShim.addPayloadTypes(channelIq.getPayloadTypes());
            channelShim.addRtpHeaderExtensions(
                    channelIq.getRtpHeaderExtensions());

            List<SourcePacketExtension> channelSources = channelIq.getSources();
            channelShim.setSources(channelSources);
            channelShim.setSourceGroups(channelIq.getSourceGroups());

            // We only create tracks for video right now, because we don't have
            // audio tracks. So only trigger re-creation of the tracks when a
            // video channel is signaled.
            if (MediaType.VIDEO.equals(contentShim.getMediaType())
                    && !channelSources.isEmpty())
            {
                channelShim.getEndpoint().recreateMediaStreamTracks();
            }

            if (MediaType.AUDIO.equals(contentShim.getMediaType()) && !channelSources.isEmpty()) {
                Map<Long,String> ssrcToEndpoint = new HashMap<>();
                for(SourcePacketExtension sourcee : channelSources) {
                    ssrcToEndpoint.put(sourcee.getSSRC(), channelShim.getEndpoint().getID());
                }
                try {
                    channelShim.getEndpoint().getConference().getAudioProcessor().updatePipeline(ssrcToEndpoint);
                } catch (Exception e) {
                    logger.error("Can not update pipeline", e);
                }
            }

            ColibriConferenceIQ.Channel responseChannelIQ
                    = new ColibriConferenceIQ.Channel();
            channelShim.describe(responseChannelIQ);
            createdOrUpdatedChannels.add(responseChannelIQ);

            if (channelIq.getTransport() != null)
            {
                String message =
                        "Received a COLIBRI request with 'transport' inside " +
                        "'channel'. This legacy mode is no longer supported";
                logger.warn(message);
                throw new IqProcessingException(
                        XMPPError.Condition.bad_request,
                        message);
            }
        }

        return createdOrUpdatedChannels;
    }

    /**
     * Processes the list of {@link ColibriConferenceIQ.SctpConnection}s
     * present in a received {@link ColibriConferenceIQ}.  Returns a list of
     * {@link ColibriConferenceIQ.SctpConnection} elements that contain
     * descriptions of the created and/or updated SCTP connection instances.
     * @param sctpConnections
     * @param contentShim
     * @return
     * @throws IqProcessingException if there are any errors during the
     * processing of the incoming connections.
     */
    private static List<ColibriConferenceIQ.SctpConnection> processSctpConnections(
            List<ColibriConferenceIQ.SctpConnection> sctpConnections,
            ContentShim contentShim) throws IqProcessingException
    {
        List<ColibriConferenceIQ.SctpConnection> createdOrUpdatedSctpConnections
                = new ArrayList<>();
        for (ColibriConferenceIQ.SctpConnection sctpConnIq : sctpConnections)
        {
            SctpConnectionShim sctpConnectionShim
                    = contentShim.getOrCreateSctpConnectionShim(sctpConnIq);
            if (sctpConnectionShim == null)
            {
                // A channel expire request which was handled successfully.
                continue;
            }

            ColibriConferenceIQ.SctpConnection responseSctpIq
                    = new ColibriConferenceIQ.SctpConnection();

            sctpConnectionShim.describe(responseSctpIq);

            createdOrUpdatedSctpConnections.add(responseSctpIq);
        }

        return createdOrUpdatedSctpConnections;
    }

    /**
     * The associated {@link Videobridge}.
     */
    private final Videobridge videobridge;

    /**
     * Initializes a new {@link VideobridgeShim} instance.
     * @param videobridge
     */
    public VideobridgeShim(Videobridge videobridge)
    {
        this.videobridge = videobridge;
    }

    /**
     * Handles a <tt>ColibriConferenceIQ</tt> stanza which represents a request.
     *
     * @param conferenceIQ the <tt>ColibriConferenceIQ</tt> stanza represents
     * the request to handle
     * @param options
     * @return an <tt>org.jivesoftware.smack.packet.IQ</tt> stanza which
     * represents the response to the specified request or <tt>null</tt> to
     * reply with <tt>feature-not-implemented</tt>
     */
    public IQ handleColibriConferenceIQ(
            ColibriConferenceIQ conferenceIQ, int options, Set<String> endpoints)
    {
        logger.debug(() -> "Got ColibriConferenceIq:\n" + conferenceIQ.toXML());
        Jid focus = conferenceIQ.getFrom();

        if (!videobridge.accept(focus, options))
        {
            return IQUtils.createError(
                    conferenceIQ, XMPPError.Condition.not_authorized);
        }

        Conference conference;

        String conferenceId = conferenceIQ.getID();
        if (conferenceId == null)
        {
            if (videobridge.isShutdownInProgress())
            {
                return ColibriConferenceIQ
                        .createGracefulShutdownErrorResponse(conferenceIQ);
            }
            else
            {
                conference
                        = videobridge.createConference(
                                focus,
                                conferenceIQ.getName(),
                                conferenceIQ.getGID());
            }
        }
        else
        {
            conference = videobridge.getConference(conferenceId, focus);
            if (conference == null)
            {
                return IQUtils.createError(
                        conferenceIQ,
                        XMPPError.Condition.bad_request,
                        "Conference not found for ID: " + conferenceId);
            }
        }
        conference.setLastKnownFocus(focus);

        ConferenceShim conferenceShim = conference.getShim();
        ColibriConferenceIQ responseConferenceIQ = new ColibriConferenceIQ();
        conference.describeShallow(responseConferenceIQ);
        responseConferenceIQ.setGracefulShutdown(
                videobridge.isShutdownInProgress());

        try
        {
            conferenceShim.initializeSignaledEndpoints(conferenceIQ);
        }
        catch (IqProcessingException e)
        {
            return IQUtils.createError(
                conferenceIQ,
                XMPPError.Condition.internal_server_error,
                "Failed to init endpoints in conference: " + conferenceId);
        }

        ColibriConferenceIQ.Channel octoAudioChannel = null;
        ColibriConferenceIQ.Channel octoVideoChannel = null;

        ColibriConferenceIQ.Channel mixerAudioChannel = null;

        for (ColibriConferenceIQ.Content contentIQ : conferenceIQ.getContents())
        {
             // The content element springs into existence whenever it gets
             // mentioned, it does not need explicit creation (in contrast to
             // the conference and channel elements).
            MediaType contentType = MediaType.parseString(contentIQ.getName());
            ContentShim contentShim =
                    conferenceShim.getOrCreateContent(contentType);
            if (contentShim == null)
            {
                return IQUtils.createError(
                        conferenceIQ,
                        XMPPError.Condition.internal_server_error,
                        "Failed to create new content for type: "
                                + contentType);
            }

            ColibriConferenceIQ.Content responseContentIQ
                    = new ColibriConferenceIQ.Content(contentType.toString());

            responseConferenceIQ.addContent(responseContentIQ);

            try
            {
                processChannels(contentIQ.getChannels(), contentShim)
                        .forEach(responseContentIQ::addChannel);
            }
            catch (IqProcessingException e)
            {
                logger.error("Error processing channels: " + e.toString());
                return IQUtils.createError(
                        conferenceIQ, e.condition, e.errorMessage);
            }

            // We want to handle the two Octo channels together.
            ColibriConferenceIQ.Channel octoChannel
                    = findOctoChannel(contentIQ);
            if (octoChannel != null)
            {
                if (MediaType.VIDEO.equals(contentType))
                {
                    octoVideoChannel = octoChannel;
                }
                else
                {
                    octoAudioChannel = octoChannel;
                }

                ColibriConferenceIQ.OctoChannel octoChannelResponse
                        = new ColibriConferenceIQ.OctoChannel();
                octoChannelResponse.setID(getOctoChannelId(contentType));
                responseContentIQ.addChannel(octoChannelResponse);
            }

            if (MediaType.AUDIO.equals(contentType)) {
                mixerAudioChannel = findMixerAudioChannel(contentIQ);
            }

            try
            {
                processSctpConnections(contentIQ.getSctpConnections(), contentShim)
                        .forEach(responseContentIQ::addSctpConnection);
            }
            catch (IqProcessingException e)
            {
                logger.error(
                    "Error processing sctp connections in IQ: " + e.toString());
                return IQUtils.createError(
                        conferenceIQ, e.condition, e.errorMessage);
            }
        }

        if (mixerAudioChannel != null) {
            conferenceShim.processMixerAudioChannel(mixerAudioChannel);
        }

        if (octoAudioChannel != null && octoVideoChannel != null)
        {
            conferenceShim.processOctoChannels(
                    octoAudioChannel, octoVideoChannel);

        }
        else if (octoAudioChannel != null || octoVideoChannel != null)
        {
            logger.error("Octo must be enabled for audio and video together");
            return IQUtils.createError(
                    conferenceIQ,
                    XMPPError.Condition.bad_request,
                    "Octo only enabled for one media type");
        }

        for (ColibriConferenceIQ.ChannelBundle channelBundleIq
                : conferenceIQ.getChannelBundles())
        {
            IceUdpTransportPacketExtension transportIq
                    = channelBundleIq.getTransport();
            if (transportIq == null)
            {
                continue;
            }

            final String endpointId = channelBundleIq.getId();

            final Endpoint endpoint = conference.getLocalEndpoint(endpointId);
            if (endpoint == null)
            {
                // Endpoint is expired and removed as part of handling IQ
                continue;
            }

            endpoint.setTransportInfo(transportIq);
        }

        conferenceShim.describeChannelBundles(responseConferenceIQ, endpoints);

        // Update the endpoint information of Videobridge with the endpoint
        // information of the IQ.
        for (ColibriConferenceIQ.Endpoint colibriEndpoint
                : conferenceIQ.getEndpoints())
        {
            conferenceShim.updateEndpoint(colibriEndpoint);
        }

        conferenceShim.describeEndpoints(responseConferenceIQ, endpoints);

        responseConferenceIQ.setType(IQ.Type.result);

        return responseConferenceIQ;
    }

    /**
     * Gets the first {@code OctoChannel} in the given content, or null.
     */
    private static ColibriConferenceIQ.Channel findOctoChannel(
            ColibriConferenceIQ.Content content)
    {
        return
                content.getChannels().stream()
                        .filter(c -> isOctoChannel(c))
                        .findAny().orElse(null);
    }

    private static ColibriConferenceIQ.Channel findMixerAudioChannel(
            ColibriConferenceIQ.Content content
    ) {
        return content.getChannels().stream()
                .filter(VideobridgeShim::isMixerAudioChannel)
                .findAny().orElse(null);
    }

    static class IqProcessingException extends Exception
    {
        private final XMPPError.Condition condition;
        private final String errorMessage;

        /**
         * Initializes a new {@link IqProcessingException} with a specific
         * condition and error message.
         */
        public IqProcessingException(
                XMPPError.Condition condition, String errorMessage)
        {
            this.condition = condition;
            this.errorMessage = errorMessage;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String toString()
        {
            return condition.toString() + " " + errorMessage;
        }
    }

}

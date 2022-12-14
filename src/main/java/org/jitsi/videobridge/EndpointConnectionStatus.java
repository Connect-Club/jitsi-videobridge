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
package org.jitsi.videobridge;

import org.jitsi.eventadmin.Event;
import org.jitsi.nlj.util.ClockUtils;
import org.jitsi.osgi.EventHandlerActivator;
import org.jitsi.osgi.ServiceUtils2;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.utils.logging2.LoggerImpl;
import org.osgi.framework.BundleContext;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static org.jitsi.videobridge.ConfAudioProcessorTransport.AUDIO_MIXER_EP_ID;
import static org.jitsi.videobridge.EndpointConnectionStatusConfig.Config;
import static org.jitsi.videobridge.EndpointMessageBuilder.createEndpointConnectivityStatusChangeEvent;

/**
 * This module monitors all endpoints across all conferences currently hosted
 * on the bridge for their connectivity status and sends notifications through
 * the data channel.
 *
 * An endpoint's connectivity status is considered connected as long as there
 * is any traffic activity seen on any of its endpoints. When there is no
 * activity for longer than the value of {@link Config#getMaxInactivityLimit()}, it
 * will be assumed that the endpoint is having some connectivity issues. Those
 * may be temporary or permanent. When that happens there will be a Colibri
 * message broadcast to all conference endpoints. The Colibri class name of
 * the message is defined in
 * {@link EndpointMessageBuilder#COLIBRI_CLASS_ENDPOINT_CONNECTIVITY_STATUS}
 * and it will contain "active" attribute set to "false". If those problems turn
 * out to be temporary and the traffic is restored another message is sent with
 * "active" set to "true".
 *
 * @author Pawel Domas
 */
@SuppressWarnings("unused") // started by OSGi
public class EndpointConnectionStatus
    extends EventHandlerActivator
{
    /**
     * The logger instance used by this class.
     */
    private static final Logger logger
        = new LoggerImpl(EndpointConnectionStatus.class.getName());

    /**
     * How often connectivity status is being probed. Value in milliseconds.
     */
    private static final long PROBE_INTERVAL = 500L;

    /**
     * OSGi BC for this module.
     */
    private BundleContext bundleContext;

    /**
     * The map of <tt>Endpoint</tt>s and it connection status.
     */
    private final Map<AbstractEndpoint, Boolean> endpointConnectionStatusMap = new HashMap<>();

    /**
     * The timer which runs the periodical connection status probing operation.
     */
    private Timer timer;

    /**
     * The {@link Clock} used by this class
     */
    private final Clock clock;

    public EndpointConnectionStatus(Clock clock)
    {
        super(new String[] { EventFactory.MSG_TRANSPORT_READY_TOPIC});
        this.clock = clock;
    }

    /**
     * Creates new instance of <tt>EndpointConnectionStatus</tt>
     */
    public EndpointConnectionStatus()
    {
        this(Clock.systemUTC());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start(BundleContext bundleContext)
        throws Exception
    {
        this.bundleContext = bundleContext;

        if (timer == null)
        {
            timer = new Timer("EndpointConnectionStatusMonitoring", true);
            timer.schedule(new TimerTask()
            {
                @Override
                public void run()
                {
                    doMonitor();
                }
            }, PROBE_INTERVAL, PROBE_INTERVAL);
        }
        else
        {
            logger.error("Endpoint connection monitoring is already running");
        }

        if (Config.getFirstTransferTimeout().compareTo(Config.getMaxInactivityLimit()) <= 0)
        {
            throw new IllegalArgumentException(
                String.format("first transfer timeout(%s) must be greater"
                            + " than max inactivity limit(%s)",
                        Config.getFirstTransferTimeout(), Config.getMaxInactivityLimit()));
        }

        super.start(bundleContext);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop(BundleContext bundleContext)
        throws Exception
    {
        super.stop(bundleContext);

        if (timer != null)
        {
            timer.cancel();
            timer = null;
        }

        synchronized (endpointConnectionStatusMap)
        {
            endpointConnectionStatusMap.clear();
        }

        this.bundleContext = null;
    }

    /**
     * Periodic task which is executed in {@link #PROBE_INTERVAL} intervals.
     * Monitors endpoints connectivity status.
     */
    private void doMonitor()
    {
        BundleContext bundleContext = this.bundleContext;
        if (bundleContext != null)
        {
            Videobridge videobridge
                = ServiceUtils2.getService(bundleContext, Videobridge.class);
            cleanupExpiredEndpointsStatus();

            Conference[] conferences = videobridge.getConferences();
            Arrays.stream(conferences)
                .forEachOrdered(
                    conference ->
                        conference.getEndpoints()
                                .forEach(this::monitorEndpointActivity));
        }
    }

    /**
     * Checks the activity for a specific {@link Endpoint}.
     * @param abstractEndpoint the endpoint.
     */
    private void monitorEndpointActivity(AbstractEndpoint abstractEndpoint)
    {
        if (abstractEndpoint instanceof Endpoint)
        {
            Endpoint endpoint = (Endpoint) abstractEndpoint;
            if(endpoint.isShadow()) {
                return;
            }
        }

        String endpointId = abstractEndpoint.getID();

        Instant now = clock.instant();
        Instant timeCreated
                = abstractEndpoint.getTimeCreated();
        Instant lastActivity = abstractEndpoint.getLastIncomingActivity();

        // Transport not initialized yet
        if (lastActivity == ClockUtils.NEVER)
        {
            // Here we check if it's taking too long for the endpoint to connect
            // We're doing that by checking how much time has elapsed since
            // the first endpoint's channel has been created.
            Duration timeSinceCreated = Duration.between(timeCreated, now);
            if (timeSinceCreated.compareTo(Config.getFirstTransferTimeout()) > 0)
            {
                if (logger.isDebugEnabled())
                    logger.debug(
                            endpointId + " is having trouble establishing"
                            + " the connection and will be marked as inactive");
                // Let the logic below mark endpoint as inactive.
                // Beware that FIRST_TRANSFER_TIMEOUT constant MUST be greater
                // than MAX_INACTIVITY_LIMIT for this logic to work.
                lastActivity = timeCreated;
            }
            else
            {
                // Wait for the endpoint to connect...
                if (logger.isDebugEnabled())
                    logger.debug(
                            endpointId + " not ready for activity checks yet");
                return;
            }
        }

        Duration noActivityTime = Duration.between(lastActivity, now);
        boolean active = noActivityTime.compareTo(Config.getMaxInactivityLimit()) <= 0;
        boolean changed = false;
        synchronized (endpointConnectionStatusMap)
        {
            Boolean connectionStatus = endpointConnectionStatusMap.get(abstractEndpoint);
            if(connectionStatus == null || connectionStatus != active) {
                if(active) {
                    logger.debug(endpointId + " has reconnected or has just connected");
                } else {
                    logger.debug(endpointId + " is considered disconnected");
                }

                endpointConnectionStatusMap.put(abstractEndpoint, active);
                changed = true;
            }
        }

        if (changed)
        {
            // Broadcast connection "active/inactive" message over data channels
            sendEndpointConnectionStatus(abstractEndpoint, active, null);
        }

        if (!active && logger.isDebugEnabled())
        {
            logger.debug(String.format(
                    "No activity on %s for %s",
                    endpointId, noActivityTime));
        }
    }

    /**
     * Sends a Colibri message about the status of an endpoint to a specific
     * target endpoint or to all endpoints.
     * @param subjectEndpoint the endpoint which the message concerns
     * @param isConnected whether the subject endpoint is connected
     * @param msgReceiver the target endpoint, or {@code null} to broadcast
     * to all endpoints.
     */
    private void sendEndpointConnectionStatus(
        AbstractEndpoint subjectEndpoint, boolean isConnected, Endpoint msgReceiver)
    {
        if (AUDIO_MIXER_EP_ID.equals(subjectEndpoint.getID())) {
            return;
        }
        Conference conference = subjectEndpoint.getConference();
        if (conference != null)
        {
            String msg
                = createEndpointConnectivityStatusChangeEvent(
                        subjectEndpoint.getID(), isConnected);
            if (msgReceiver == null)
            {
                // We broadcast the message also to the endpoint itself for
                // debugging purposes, and we also broadcast it through Octo.
                conference.broadcastMessage(msg, true /* sendToOcto */);
            }
            else
            {
                // Send only to the receiver endpoint
                List<AbstractEndpoint> receivers
                    = Collections.singletonList(msgReceiver);
                conference.sendMessage(msg, receivers);
            }
        }
        else
        {
            logger.warn(
                    "Attempt to send connectivity status update for"
                        + " endpoint " + subjectEndpoint.getID()
                        + " without parent conference instance (expired?)");
        }
    }

    private void sendEndpointExpiredEvent(AbstractEndpoint subjectEndpoint) {
        Conference conference = subjectEndpoint.getConference();
        if (conference != null)
        {
            String msg = EndpointMessageBuilder.createEndpointExpiredEvent(subjectEndpoint.getID());
            List<AbstractEndpoint> endpoints = conference.getEndpoints().stream()
                    .filter(x -> !Objects.equals(x.getID(), subjectEndpoint.getID()))
                    .collect(Collectors.toList());
            conference.sendMessage(msg, endpoints, false);
        } else {
            logger.warn(
                    "Attempt to send endpoint expired event for"
                            + " endpoint " + subjectEndpoint.getID()
                            + " without parent conference instance (expired?)");
        }
    }

    /**
     * Prunes the {@link #endpointConnectionStatusMap} map.
     */
    private void cleanupExpiredEndpointsStatus()
    {
        Map<AbstractEndpoint, Boolean> removedOrReplacedEndpointsWithLastStatus = new HashMap<>();
        synchronized (endpointConnectionStatusMap)
        {
            endpointConnectionStatusMap.entrySet().removeIf(entry -> {
                AbstractEndpoint endpoint = entry.getKey();
                Conference conference = endpoint.getConference();
                AbstractEndpoint endpointFromConference =
                        conference.getEndpoint(endpoint.getID());
                boolean endpointRemovedOrReplaced =
                        endpointFromConference == null || endpointFromConference != endpoint;

                if (endpointRemovedOrReplaced && (endpointFromConference == null || endpointFromConference instanceof Endpoint))
                {
                    removedOrReplacedEndpointsWithLastStatus.put(endpoint, entry.getValue());
                }

                return conference.isExpired() || endpointRemovedOrReplaced;
            });
            if (logger.isDebugEnabled())
            {
                endpointConnectionStatusMap.keySet().stream()
                    .filter(AbstractEndpoint::isExpired)
                    .forEach(
                        e ->
                            logger.debug("Endpoint has expired: " + e.getID()
                                + ", but is still on the list"));
            }
        }

        for (Map.Entry<AbstractEndpoint, Boolean> endpointStatusEntry: removedOrReplacedEndpointsWithLastStatus.entrySet())
        {
            if(endpointStatusEntry.getValue()) {
                sendEndpointConnectionStatus(endpointStatusEntry.getKey(), false, null);
            }
            sendEndpointExpiredEvent(endpointStatusEntry.getKey());
        }
    }

    @Override
    public void handleEvent(Event event)
    {
        // Verify the topic just in case
        // FIXME eventually add this verification to the base class
        String topic = event.getTopic();
        if (!EventFactory.MSG_TRANSPORT_READY_TOPIC.equals(topic))
        {
            logger.warn("Received event for unexpected topic: " + topic);
            return;
        }

        Endpoint endpoint
            = (Endpoint) event.getProperty(EventFactory.EVENT_SOURCE);
        if (endpoint == null)
        {
            logger.error("Endpoint is null");
            return;
        }

        Conference conference = endpoint.getConference();
        if (conference == null || conference.isExpired())
        {
            // Received event for endpoint which is now expired - ignore
            return;
        }

        // Go over all endpoints in the conference and send notification about
        // those currently "inactive"
        //
        // Looping over all inactive endpoints of all conferences maybe is not
        // the most efficient, but it should not be extremely large number.
        Map<AbstractEndpoint,Boolean> endpoints;
        synchronized (endpointConnectionStatusMap)
        {
            endpoints = endpointConnectionStatusMap.entrySet().stream()
                .filter(e -> e.getKey().getConference() == conference)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }

        for (Map.Entry<AbstractEndpoint,Boolean> e: endpoints.entrySet())
        {
            sendEndpointConnectionStatus(e.getKey(), e.getValue(), endpoint);
        }
    }
}

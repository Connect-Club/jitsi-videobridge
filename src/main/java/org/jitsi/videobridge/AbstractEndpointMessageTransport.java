/*
 * Copyright @ 2017 - Present, 8x8 Inc
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

import org.jetbrains.annotations.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.octo.*;
import org.jitsi.videobridge.util.*;
import org.jitsi.videobridge.websocket.*;
import org.json.simple.*;
import org.json.simple.parser.*;

import java.util.*;
import java.util.stream.Collectors;

import static org.jitsi.videobridge.EndpointMessageBuilder.*;

/**
 * Handles the functionality related to sending and receiving COLIBRI messages
 * for an {@link Endpoint}. An abstract implementation.
 *
 * @author Boris Grozev
 */
public abstract class AbstractEndpointMessageTransport
{
    /**
     * The name of the JSON property that indicates the target Octo endpoint id
     * of a propagated JSON message.
     */
    public static final String PROP_TARGET_OCTO_ENDPOINT_ID = "targetOctoEndpointId";

    /**
     * The {@link Endpoint} associated with this
     * {@link EndpointMessageTransport}.
     */
    protected final AbstractEndpoint endpoint;

    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    protected final @NotNull Logger logger;

    /**
     * Initializes a new {@link AbstractEndpointMessageTransport} instance.
     * @param endpoint the endpoint to which this transport belongs
     */
    public AbstractEndpointMessageTransport(AbstractEndpoint endpoint, @NotNull Logger parentLogger)
    {
        this.endpoint = endpoint;
        this.logger = parentLogger.createChildLogger(getClass().getName());
    }

    /**
     *
     * @return true if this message transport is 'connected', false otherwise
     */
    public abstract boolean isConnected();

    /**
     * Fires the message transport ready event for the associated endpoint.
     */
    protected void notifyTransportChannelConnected()
    {
    }

    /**
     * Notifies this {@link AbstractEndpointMessageTransport} that a
     * {@code ClientHello} has been received.
     *
     * @param src the transport channel on which {@code jsonObject} has
     * been received
     * @param jsonObject the JSON object with {@link Videobridge#COLIBRI_CLASS}
     * {@code ClientHello} which has been received by the associated SCTP connection
     */
    protected void onClientHello(Object src, JSONObject jsonObject)
    {
    }

    /**
     * Notifies this {@code Endpoint} that a specific JSON object has been
     * received.
     *
     * @param src the transport channel by which the specified
     * {@code jsonObject} has been received.
     * @param jsonObject the JSON data received by {@code src}.
     * @param colibriClass the non-{@code null} value of the mandatory JSON
     * property {@link Videobridge#COLIBRI_CLASS} required of all JSON objects
     * received.
     */
    private void onJSONData(
        Object src,
        JSONObject jsonObject,
        String colibriClass)
    {
        TaskPools.IO_POOL.submit(() -> {
            switch (colibriClass)
            {
                case COLIBRI_CLASS_PINNED_ENDPOINT_CHANGED:
                    onPinnedEndpointChangedEvent(src, jsonObject);
                    break;
                case COLIBRI_CLASS_PINNED_ENDPOINTS_CHANGED:
                    onPinnedEndpointsChangedEvent(src, jsonObject);
                    break;
                case COLIBRI_CLASS_PINNED_UUID_ENDPOINTS_CHANGED:
                    onPinnedUUIDEndpointsChangedEvent(src, jsonObject);
                    break;
                case COLIBRI_CLASS_SUBSCRIPTION_TYPE_CHANGED:
                    onSubscriptionTypeChangedEvent(src, jsonObject);
                    break;
                case COLIBRI_CLASS_SUBSCRIBED_ENDPOINTS_CHANGED:
                    onSubscribedEndpointsChangedEvent(src, jsonObject);
                    break;
                case COLIBRI_CLASS_CLIENT_HELLO:
                    onClientHello(src, jsonObject);
                    break;
                case COLIBRI_CLASS_ENDPOINT_MESSAGE:
                    onClientEndpointMessage(src, jsonObject);
                    break;
                default:
                    logger.info(
                            "Received a message with unknown colibri class: "
                                    + colibriClass);
                    break;
            }
        });
    }

    /**
     * Handles an opaque message from this {@code Endpoint} that should be
     * forwarded to either: a) another client in this conference (1:1
     * message) or b) all other clients in this conference (broadcast message)
     *
     * @param src the transport channel on which {@code jsonObject} has
     * been received.
     * @param jsonObject the JSON object with {@link Videobridge#COLIBRI_CLASS}
     * {@code EndpointMessage} which has been received.
     *
     * EndpointMessage definition:
     * 'to': If the 'to' field contains the endpoint id of another endpoint
     * in this conference, the message will be treated as a 1:1 message and
     * forwarded just to that endpoint. If the 'to' field is an empty
     * string, the message will be treated as a broadcast and sent to all other
     * endpoints in this conference.
     * 'msgPayload': An opaque payload. The bridge does not need to know or
     * care what is contained in the 'msgPayload' field, it will just forward
     * it blindly.
     *
     * NOTE: This message is designed to allow endpoints to pass their own
     * application-specific messaging to one another without requiring the
     * bridge to know of or understand every message type. These messages
     * will be forwarded by the bridge using the same transport channel as other
     * jitsi messages (e.g. active speaker and last-n notifications).
     * It is not recommended to send high-volume message traffic on this
     * channel (e.g. file transfer), such that it may interfere with other
     * jitsi messages.
     */
    @SuppressWarnings("unchecked")
    protected void onClientEndpointMessage(
        @SuppressWarnings("unused") Object src,
        JSONObject jsonObject)
    {
        String to = (String)jsonObject.get("to");

        // First insert the "from" to prevent spoofing.
        String from = getId(jsonObject.get("from"));
        jsonObject.put("from", from);
        Conference conference = getConference();

        if (conference == null || conference.isExpired())
        {
            logger.warn(
                "Unable to send EndpointMessage, conference is null or expired");
            return;
        }

        AbstractEndpoint sourceEndpoint = conference.getEndpoint(from);

        if (sourceEndpoint == null)
        {
            logger.warn("Can not forward message, source endpoint not found.");
            // The source endpoint might have expired. If it was an Octo
            // endpoint and we forward the message, we may mistakenly forward
            // it back through Octo and cause a loop.
            return;
        }

        List<AbstractEndpoint> targets;
        if ("".equals(to))
        {
            // Broadcast message
            targets = new LinkedList<>(conference.getEndpoints());
            targets.removeIf(e -> e.getID().equalsIgnoreCase(getId()));
        }
        else
        {
            // 1:1 message
            AbstractEndpoint targetEndpoint = conference.getEndpoint(to);
            if (targetEndpoint != null)
            {
                targets = Collections.singletonList(targetEndpoint);
            }
            else
            {
                logger.warn(
                    "Unable to find endpoint " + to
                        + " to send EndpointMessage");
                return;
            }
        }

        boolean sendToOcto
            = !(sourceEndpoint instanceof OctoEndpoint)
              && targets.stream().anyMatch(e -> (e instanceof OctoEndpoint));

        conference.sendMessage(
                jsonObject.toString(),
                targets,
                sendToOcto);
    }

    /**
     * @return the associated {@link Conference} or {@code null}.
     */
    protected Conference getConference()
    {
        return endpoint != null ? endpoint.getConference() : null;
    }

    /**
     * @return the ID of the associated endpoint or {@code null}.
     */
    private String getId()
    {
        return getId(null);
    }

    /**
     * @return the ID of the associated endpoint or {@code null}.
     * @param id a suggested ID.
     */
    protected String getId(Object id)
    {
        return endpoint != null ? endpoint.getID() : null;
    }

    /**
     * Notifies this {@code Endpoint} that a {@code PinnedEndpointChangedEvent}
     * has been received.
     *
     * @param src the transport channel by which {@code jsonObject} has
     * been received.
     * @param jsonObject the JSON object with {@link Videobridge#COLIBRI_CLASS}
     * {@code PinnedEndpointChangedEvent} which has been received.
     */
    protected void onPinnedEndpointChangedEvent(
        @SuppressWarnings("unused") Object src,
        JSONObject jsonObject)
    {
        // Find the new pinned endpoint.
        String newPinnedEndpointID = (String) jsonObject.get("pinnedEndpoint");

        Set<String> newPinnedIDs = Collections.emptySet();
        if (newPinnedEndpointID != null && !"".equals(newPinnedEndpointID))
        {
            newPinnedIDs = Collections.singleton(newPinnedEndpointID);
        }

        onPinnedEndpointsChangedEvent(jsonObject, newPinnedIDs);
    }

    protected void onPinnedUUIDEndpointsChangedEvent(
            @SuppressWarnings("unused") Object src,
            JSONObject jsonObject
    ) {
        // Find the new pinned endpoint.
        Object o = jsonObject.get("pinnedUUIDEndpoints");
        if (!(o instanceof JSONArray))
        {
            logger.warn("Received invalid or unexpected JSON: " + jsonObject);
            return;
        }

        JSONArray jsonArray = (JSONArray) o;
        Set<UUID> newPinnedUUIDEndpoints = filterStringsToSet(jsonArray).stream()
                .map(UUID::fromString)
                .collect(Collectors.toSet());

        if (logger.isDebugEnabled())
        {
            logger.debug("Pinned UUID`s " + newPinnedUUIDEndpoints);
        }

        onPinnedUUIDEndpointsChangedEvent(jsonObject, newPinnedUUIDEndpoints);
    }

    protected void onSubscriptionTypeChangedEvent(
            @SuppressWarnings("unused") Object src,
            JSONObject jsonObject
    ) {
        Object o = jsonObject.get("value");

        onSubscriptionTypeChangedEvent(jsonObject, EndpointSubscriptionType.valueOf(o.toString()));
    }

    protected void onSubscribedEndpointsChangedEvent(
            @SuppressWarnings("unused") Object src,
            JSONObject jsonObject
    ) {
        // Find the new subscribed endpoints.
        Object o = jsonObject.get("subscribedEndpointsUUID");
        if (!(o instanceof JSONObject))
        {
            logger.warn("Received invalid or unexpected JSON: " + jsonObject);
            return;
        }
        Map<UUID,EndpointVideoConstraint> subscribedEndpointsUUID = new HashMap<>();
        JSONObject jsObject = (JSONObject) o;
        for(Object endpointUUID : jsObject.keySet()) {
            Object videoConstraint = jsObject.get(endpointUUID);
            if (!(videoConstraint instanceof JSONObject))
            {
                logger.warn("Received invalid or unexpected JSON: " + jsonObject);
                return;
            }
            JSONObject jsonVideoConstraint = (JSONObject) videoConstraint;
            boolean offVideo = (boolean) jsonVideoConstraint.get("offVideo");
            boolean lowRes = (boolean) jsonVideoConstraint.get("lowRes");
            boolean lowFps = (boolean) jsonVideoConstraint.get("lowFps");
            subscribedEndpointsUUID.put(
                    UUID.fromString(endpointUUID.toString()),
                    new EndpointVideoConstraint(offVideo, lowRes, lowFps)
            );
        }
        onSubscribedEndpointsUUIDChangedEvent(jsonObject, subscribedEndpointsUUID);
    }

    /**
     * Notifies this {@code Endpoint} that a {@code PinnedEndpointsChangedEvent}
     * has been received.
     *
     * @param src the transport channel by which {@code jsonObject} has
     * been received
     * @param jsonObject the JSON object with {@link Videobridge#COLIBRI_CLASS}
     * {@code PinnedEndpointChangedEvent} which has been received.
     */
    protected void onPinnedEndpointsChangedEvent(
        @SuppressWarnings("unused") Object src,
        JSONObject jsonObject)
    {
        // Find the new pinned endpoint.
        Object o = jsonObject.get("pinnedEndpoints");
        if (!(o instanceof JSONArray))
        {
            logger.warn("Received invalid or unexpected JSON: " + jsonObject);
            return;
        }

        JSONArray jsonArray = (JSONArray) o;
        Set<String> newPinnedEndpoints = filterStringsToSet(jsonArray);

        if (logger.isDebugEnabled())
        {
            logger.debug("Pinned " + newPinnedEndpoints);
        }

        onPinnedEndpointsChangedEvent(jsonObject, newPinnedEndpoints);
    }

    private Set<String> filterStringsToSet(JSONArray jsonArray)
    {
        Set<String> strings = new HashSet<>();
        for (Object element : jsonArray)
        {
            if (element instanceof String)
            {
                strings.add((String)element);
            }
        }
        return strings;
    }

    /**
     * Notifies local or remote endpoints that a pinned event has been received.
     * If it is a local endpoint that has received the message, then this method
     * propagates the message to all proxies of the local endpoint.
     *
     * @param jsonObject the JSON object with {@link Videobridge#COLIBRI_CLASS}
     * {@code PinnedEndpointChangedEvent} which has been received.
     * @param newPinnedEndpoints the new pinned endpoints
     */
    protected abstract void onPinnedEndpointsChangedEvent(
        JSONObject jsonObject,
        Set<String> newPinnedEndpoints);

    protected abstract void onPinnedUUIDEndpointsChangedEvent(
            JSONObject jsonObject,
            Set<UUID> newPinnedUUIDEndpoints
    );

    protected abstract void onSubscriptionTypeChangedEvent(
            JSONObject jsonObject,
            EndpointSubscriptionType subscriptionType
    );

    protected abstract void onSubscribedEndpointsUUIDChangedEvent(
            JSONObject jsonObject,
            Map<UUID,EndpointVideoConstraint> newSubscribedEndpointsUUID
    );

    /**
     * Notifies this {@link EndpointMessageTransport} that a specific message
     * has been received on a specific transport channel.
     * @param src the transport channel on which the message has been received.
     * @param msg the message which has been received.
     */
    public void onMessage(Object src, String msg)
    {
        logger.trace("Message received:\n" + msg);
        Object obj;
        JSONParser parser = new JSONParser(); // JSONParser is NOT thread-safe.

        try
        {
            obj = parser.parse(msg);
        }
        catch (ParseException ex)
        {
            logger.warn(
                    "Malformed JSON received from endpoint " + getId(),
                    ex);
            obj = null;
        }

        // We utilize JSONObjects only.
        if (obj instanceof JSONObject)
        {
            JSONObject jsonObject = (JSONObject) obj;
            // We utilize JSONObjects with colibriClass only.
            String colibriClass
                = (String)jsonObject.get(Videobridge.COLIBRI_CLASS);

            if (colibriClass != null)
            {
                onJSONData(src, jsonObject, colibriClass);
            }
            else
            {
                logger.warn(
                    "Malformed JSON received from endpoint " + getId()
                        + ". JSON object does not contain the colibriClass"
                        + " field.");
            }
        }
    }

    /**
     * Sends a specific message over the active transport channels of this
     * {@link EndpointMessageTransport}.
     *
     * @param msg message text to send.
     */
    protected void sendMessage(String msg)
    {
    }

    /**
     * Closes this {@link EndpointMessageTransport}.
     */
    protected void close()
    {
    }

    public JSONObject getDebugState() {
        return new JSONObject();
    }

    /**
     * Events generated by {@link AbstractEndpointMessageTransport} types which
     * are of interest to other entities.
     */
    interface EndpointMessageTransportEventHandler {
        void endpointMessageTransportConnected(@NotNull AbstractEndpoint endpoint);
    }
}

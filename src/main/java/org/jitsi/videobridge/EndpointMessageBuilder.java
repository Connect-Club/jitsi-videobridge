/*
 * Copyright @ 2017 Atlassian Pty Ltd
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

import com.google.common.collect.ImmutableMap;
import org.json.simple.*;

import java.util.*;

public class EndpointMessageBuilder
{
    /**
     * The {@link Videobridge#COLIBRI_CLASS} value indicating a
     * {@code ClientHello} message.
     */
    public static final String COLIBRI_CLASS_CLIENT_HELLO
        = "ClientHello";

    /**
     * The {@link Videobridge#COLIBRI_CLASS} value indicating a dominant speaker
     * change event.
     */
    public static final String COLIBRI_CLASS_ACTIVE_SPEAKERS_CHANGE
        = "ActiveSpeakersChangeEvent";

    /**
     * Constant value defines the name of "colibriClass" for connectivity status
     * notifications sent over the data channels.
     */
    public static final String COLIBRI_CLASS_ENDPOINT_CONNECTIVITY_STATUS
        = "EndpointConnectivityStatusChangeEvent";

    /**
     * The {@link Videobridge#COLIBRI_CLASS} value indicating a
     * {@code EndpointMessage}.
     */
    public static final String COLIBRI_CLASS_ENDPOINT_MESSAGE
        = "EndpointMessage";

    /**
     * The {@link Videobridge#COLIBRI_CLASS} value indicating a
     * {@code PinnedEndpointChangedEvent}.
     */
    public static final String COLIBRI_CLASS_PINNED_ENDPOINT_CHANGED
        = "PinnedEndpointChangedEvent";

    /**
     * The {@link Videobridge#COLIBRI_CLASS} value indicating a
     * {@code PinnedEndpointsChangedEvent}.
     */
    public static final String COLIBRI_CLASS_PINNED_ENDPOINTS_CHANGED
        = "PinnedEndpointsChangedEvent";

    public static final String COLIBRI_CLASS_PINNED_UUID_ENDPOINTS_CHANGED
        = "PinnedUUIDEndpointsChangedEvent";

    public static final String COLIBRI_CLASS_SUBSCRIPTION_TYPE_CHANGED
        = "SubscriptionTypeChangedEvent";

    /**
     * The {@link Videobridge#COLIBRI_CLASS} value indicating a
     * {@code ReceiverVideoConstraint} message.
     */
    public static final String COLIBRI_CLASS_RECEIVER_VIDEO_CONSTRAINT
        = "ReceiverVideoConstraint";

    /**
     * The {@link Videobridge#COLIBRI_CLASS} value indicating a
     * {@code SelectedUpdateEvent}.
     */
    public static final String COLIBRI_CLASS_SELECTED_UPDATE
        = "SelectedUpdateEvent";

    /**
     * The {@link Videobridge#COLIBRI_CLASS} value indicating a
     * {@code EndpointExpiredEvent}.
     */
    public static final String COLIBRI_CLASS_ENDPOINT_EXPIRED
            = "EndpointExpiredEvent";

    /**
     * The string which encodes a COLIBRI {@code ServerHello} message.
     */
    public static final String COLIBRI_CLASS_SERVER_HELLO = "ServerHello";

    /**
     * @param activeEndpoints the IDs of the active speakers endpoints in this
     * multipoint conference.
     *
     * @return a new <tt>String</tt> which represents a message to be sent
     * to an endpoint in order to notify it that the active speakers in its
     * multipoint conference has changed.
     */
    public static String createActiveSpeakersChangeEvent(
        List<String> activeEndpoints)
    {
        return JSONObject.toJSONString(ImmutableMap.of(
                "colibriClass", COLIBRI_CLASS_ACTIVE_SPEAKERS_CHANGE,
                "activeSpeakers", activeEndpoints
        ));
    }

    /**
     * Creates a string which represents a message of type
     * {@link #COLIBRI_CLASS_ENDPOINT_CONNECTIVITY_STATUS}.
     * in order to notify it
     * @param endpointId ?
     * @param connected ?
     */
    public static String createEndpointConnectivityStatusChangeEvent(
        String endpointId, boolean connected)
    {
        return JSONObject.toJSONString(ImmutableMap.of(
                "colibriClass", COLIBRI_CLASS_ENDPOINT_CONNECTIVITY_STATUS,
                "endpoint", endpointId,
                "active", connected
        ));
    }

    /**
     * Creates a Colibri ServerHello message.
     */
    public static String createServerHelloEvent()
    {
        return "{\"colibriClass\":\"" + COLIBRI_CLASS_SERVER_HELLO + "\"}";
    }

    public static String createEndpointExpiredEvent(String endpointId) {
        return JSONObject.toJSONString(ImmutableMap.of(
                "colibriClass", COLIBRI_CLASS_ENDPOINT_EXPIRED,
                "endpoint", endpointId
        ));
    }

    public static String createCustomMessage(String colibriClass, Map<String, String> msg) {
        if(msg.containsKey("colibriClass")) {
            throw new RuntimeException("Message already contains `colibriClass` field");
        }
        JSONObject jsonMessage = new JSONObject(Collections.singletonMap("colibriClass", colibriClass));
        jsonMessage.putAll(msg);
        return jsonMessage.toJSONString();
    }


    /**
     * Returns a JSON array representation of a collection of strings.
     */
    @SuppressWarnings("unchecked")
    private static String getJsonString(Collection<String> strings)
    {
        JSONArray array = new JSONArray();
        if (strings != null && !strings.isEmpty())
        {
            array.addAll(strings);
        }
        return array.toString();
    }
}

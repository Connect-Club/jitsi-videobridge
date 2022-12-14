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

import org.json.simple.*;
import org.json.simple.parser.*;
import org.junit.*;

import java.util.*;

import static org.junit.Assert.*;
import static org.jitsi.videobridge.EndpointMessageBuilder.*;

public class EndpointMessageBuilderTest
{
    @Test
    public void testServerHello()
        throws Exception
    {
        String str = createServerHelloEvent();
        JSONObject json = (JSONObject) new JSONParser().parse(str);
        assertEquals(COLIBRI_CLASS_SERVER_HELLO,
                     json.get(Videobridge.COLIBRI_CLASS));
    }

//    @Test
//    public void testDominantSpeakerEndpointChange()
//        throws Exception
//    {
//        String id = "abc123";
//        String str = createActiveSpeakersChangeEvent(id);
//
//        JSONObject json = (JSONObject) new JSONParser().parse(str);
//        assertEquals(COLIBRI_CLASS_ACTIVE_SPEAKERS_CHANGE,
//                     json.get(Videobridge.COLIBRI_CLASS));
//        assertEquals(id,
//                     json.get("dominantSpeakerEndpoint"));
//    }

    @Test
    public void testEndpointConnectivityStatusChangeEvent()
        throws Exception
    {
        String id = "abc123";
        boolean status = false;
        String str = createEndpointConnectivityStatusChangeEvent(id, status);

        JSONObject json = (JSONObject) new JSONParser().parse(str);
        assertEquals(COLIBRI_CLASS_ENDPOINT_CONNECTIVITY_STATUS,
                     json.get(Videobridge.COLIBRI_CLASS));
        assertEquals(id,
                     json.get("endpoint"));
        assertEquals(status,
                     json.get("active"));
    }

}

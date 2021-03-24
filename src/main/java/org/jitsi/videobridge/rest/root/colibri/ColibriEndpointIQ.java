package org.jitsi.videobridge.rest.root.colibri;

import org.jitsi.xmpp.extensions.colibri.ColibriConferenceIQ;

import java.util.UUID;

public class ColibriEndpointIQ extends ColibriConferenceIQ.Endpoint {

    public static final String UUID_ATTR_NAME = "uuid";

    private UUID uuid;

    public UUID getUuid() {
        return uuid;
    }

    /**
     * Initializes a new <tt>ColibriEndpointIQ</tt> with the given ID, UUID and displayName
     * name.
     *
     * @param id          the ID.
     * @param statsId     stats ID value
     * @param displayName the display name.
     */
    public ColibriEndpointIQ(String id, UUID uuid, String statsId, String displayName) {
        super(id, statsId, displayName);
        this.uuid = uuid;
    }
}

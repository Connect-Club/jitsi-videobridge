package org.jitsi.videobridge.stats;

import org.jitsi.nlj.stats.TransceiverStats;
import org.jitsi.nlj.transform.node.incoming.IncomingSsrcStats;
import org.jitsi.nlj.transform.node.incoming.IncomingStatisticsSnapshot;
import org.jitsi.nlj.transform.node.outgoing.OutgoingSsrcStats;
import org.jitsi.nlj.transform.node.outgoing.OutgoingStatisticsSnapshot;
import org.jitsi.osgi.ServiceUtils2;
import org.jitsi.videobridge.Conference;
import org.jitsi.videobridge.Endpoint;
import org.jitsi.videobridge.Videobridge;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.osgi.framework.BundleContext;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

import static org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.*;

public class EndpointStatistics extends Statistics {

    public final String confId;
    public final String endpointId;

    private final DateFormat timestampFormat;

    public static final String PACKETS_RECEIVED_BY_SSRC = "packets_received_by_ssrc";
    public static final String BYTES_RECEIVED_BY_SSRC = "bytes_received_by_ssrc";

    public EndpointStatistics(String confId, String endpointId) {
        this.confId = confId;
        this.endpointId = endpointId;

        timestampFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        timestampFormat.setTimeZone(TimeZone.getTimeZone("UTC"));

        unlockedSetStat(TOTAL_PACKETS_RECEIVED, 0);
        unlockedSetStat(TOTAL_BYTES_RECEIVED, 0);
        unlockedSetStat(PACKETS_RECEIVED_BY_SSRC, "{}");
        unlockedSetStat(BYTES_RECEIVED_BY_SSRC, "{}");

        unlockedSetStat(TIMESTAMP, timestampFormat.format(new Date()));
    }

    @Override
    public void generate() {
        BundleContext bundleContext
                = StatsManagerBundleActivator.getBundleContext();
        Videobridge videobridge
                = ServiceUtils2.getService(bundleContext, Videobridge.class);

        Conference conference = Arrays.stream(videobridge.getConferences())
                .filter(Conference::includeInStatistics)
                .filter(x -> Objects.equals(x.getID(), confId))
                .findFirst().orElse(null);

        if(conference == null) return;

        Endpoint endpoint = conference.getLocalEndpoints().stream()
                .filter(x -> Objects.equals(x.getID(), endpointId))
                .findFirst().orElse(null);

        if(endpoint == null) return;

        TransceiverStats transceiverStats
                = endpoint.getTransceiver().getTransceiverStats();
        IncomingStatisticsSnapshot incomingStats
                = transceiverStats.getIncomingStats();

        long totalPacketsReceived = 0;
        long totalBytesReceived = 0;
        JSONObject packetsReceivedBySsrcJson = new JSONObject();
        JSONObject bytesReceivedBySsrcJson = new JSONObject();
        for (Map.Entry<Long, IncomingSsrcStats.Snapshot> ssrcStatsEntry : incomingStats.getSsrcStats().entrySet())
        {
            totalPacketsReceived += ssrcStatsEntry.getValue().getNumReceivedPackets();
            totalBytesReceived += ssrcStatsEntry.getValue().getNumReceivedBytes();
            packetsReceivedBySsrcJson.put(ssrcStatsEntry.getKey(), ssrcStatsEntry.getValue().getNumReceivedPackets());
            bytesReceivedBySsrcJson.put(ssrcStatsEntry.getKey(), ssrcStatsEntry.getValue().getNumReceivedBytes());
        }

        unlockedSetStat(TOTAL_PACKETS_RECEIVED, totalPacketsReceived);
        unlockedSetStat(TOTAL_BYTES_RECEIVED, totalBytesReceived);
        unlockedSetStat(PACKETS_RECEIVED_BY_SSRC, packetsReceivedBySsrcJson);
        unlockedSetStat(BYTES_RECEIVED_BY_SSRC, bytesReceivedBySsrcJson);

        unlockedSetStat(TIMESTAMP, timestampFormat.format(new Date()));
    }
}

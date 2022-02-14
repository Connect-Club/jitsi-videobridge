package org.jitsi.videobridge;

import com.google.common.collect.ImmutableMap;
import okhttp3.*;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jitsi.eventadmin.Event;
import org.jitsi.nlj.stats.TransceiverStats;
import org.jitsi.nlj.transform.node.incoming.IncomingSsrcStats;
import org.jitsi.osgi.EventHandlerActivator;
import org.jitsi.rtp.rtcp.RtcpReportBlock;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.utils.logging2.LoggerImpl;
import org.jitsi.videobridge.util.PropertyUtil;
import org.jitsi.videobridge.util.TaskPools;
import org.json.simple.JSONObject;

import javax.xml.ws.Holder;
import java.io.IOException;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@SuppressWarnings("unused") // started by OSGi
public class NotificationsHandler extends EventHandlerActivator {

    private static final Logger logger = new LoggerImpl(NotificationsHandler.class.getName());

    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final static OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    static class ConferenceNotifications {
        final Lock lock = new ReentrantLock(true);
        final Queue<Request> queue = new LinkedList<>();
        boolean sendingInProgress;
    }

    private final Map<Conference, ConferenceNotifications> conferenceNotificationsMap = new ConcurrentHashMap<>();
    private final Map<Endpoint, ScheduledFuture<?>> endpointScheduledFutureMap = new ConcurrentHashMap<>();

    private String notificationUrl;

    private final Clock clock = Clock.systemUTC();

    public NotificationsHandler() {
        super(new String[]{
                EventFactory.CONFERENCE_CREATED_TOPIC,
                EventFactory.CONFERENCE_EXPIRED_TOPIC,
                EventFactory.ENDPOINT_CREATED_TOPIC,
                EventFactory.ENDPOINT_EXPIRED_TOPIC
        });
    }

    @Override
    public void handleEvent(Event event) {
        long createdAt = clock.instant().getEpochSecond();
        if (event == null) {
            logger.debug(() -> "Could not handle an event because it was null.");
            return;
        }
        if (StringUtils.isBlank(notificationUrl)) {
            notificationUrl = PropertyUtil.getValue(
                    "conference.notification.url",
                    "JVB_CONFERENCE_NOTIFICATION_URL"
            );
            if (StringUtils.isBlank(notificationUrl)) {
                logger.debug(() -> "Could not handle an event because notification url is blank.");
                return;
            }
        }
        String eventType = null;
        AbstractEndpoint endpoint = null;
        Conference conference = null;
        Map<String, Object> payload = null;
        boolean lastConferenceEvent = false;
        switch (event.getTopic()) {
            case EventFactory.CONFERENCE_CREATED_TOPIC:
                eventType = "CONFERENCE_CREATED";
                conference = (Conference) event.getProperty(EventFactory.EVENT_SOURCE);
                break;
            case EventFactory.CONFERENCE_EXPIRED_TOPIC:
                eventType = "CONFERENCE_EXPIRED";
                conference = (Conference) event.getProperty(EventFactory.EVENT_SOURCE);
                lastConferenceEvent = true;
                break;
            case EventFactory.ENDPOINT_CREATED_TOPIC:
                eventType = "ENDPOINT_CREATED";
                endpoint = (AbstractEndpoint) event.getProperty(EventFactory.EVENT_SOURCE);
                break;
            case EventFactory.ENDPOINT_EXPIRED_TOPIC:
                eventType = "ENDPOINT_EXPIRED";
                endpoint = (AbstractEndpoint) event.getProperty(EventFactory.EVENT_SOURCE);
                break;
        }
        if (eventType == null) {
            return;
        }
        final Logger logger = NotificationsHandler.logger.createChildLogger(NotificationsHandler.class.getName());
        logger.addContext("loggerUuid", UUID.randomUUID().toString());
        if (conference == null && endpoint != null) {
            logger.addContext("epId", endpoint.getID());
            logger.addContext("epUuid", endpoint.getUuid().toString());
            conference = endpoint.getConference();
        }
        logger.addContext(ImmutableMap.of("confId", conference.getID(), "gid", conference.getGid()));
        JSONObject notification = new JSONObject(ImmutableMap.of(
                "eventType", eventType,
                "createdAt", createdAt,
                "conferenceId", conference.getID(),
                "conferenceGid", conference.getGid()
        ));
        if (payload != null) {
            notification.put("payload", new JSONObject(payload));
        }
        if (endpoint != null) {
            if (endpoint instanceof Endpoint) {
                Endpoint endpnt = (Endpoint) endpoint;
                if (endpnt.isShadow()) {
                    logger.info("This is shadow endpoint. Ignoring notification");
                    return;
                }
                notification.put("endpointId", endpnt.getID());
                notification.put("endpointUuid", endpnt.getUuid().toString());
                if (event.getTopic().equals(EventFactory.ENDPOINT_CREATED_TOPIC)) {
                    JSONObject infoForNotification = endpnt.getInfoForNotification();
                    if (infoForNotification != null) {
                        notification.putAll(infoForNotification);
                    }
                    ScheduledFuture<?> scheduledFuture = TaskPools.SCHEDULED_POOL.scheduleAtFixedRate(createEndpointStatsHandler(endpnt, logger), 10, 10, TimeUnit.SECONDS);
                    ScheduledFuture<?> prevScheduledFuture = endpointScheduledFutureMap.put(endpnt, scheduledFuture);
                    if (prevScheduledFuture != null) {
                        logger.warn("prevScheduledFuture != null");
                        prevScheduledFuture.cancel(false);
                    }
                } else if (event.getTopic().equals(EventFactory.ENDPOINT_EXPIRED_TOPIC)) {
                    ScheduledFuture<?> scheduledFuture = endpointScheduledFutureMap.remove(endpnt);
                    if (scheduledFuture != null) {
                        scheduledFuture.cancel(false);
                    }
                }
            } else {
                logger.info(String.format("This is endpoint of type '%s'. Ignoring notification", endpoint.getClass().toString()));
                return;
            }
        }

        submitNotification(conference, lastConferenceEvent, notification);
    }

    private Runnable createEndpointStatsHandler(final Endpoint endpoint, Logger logger) {
        final Conference conference = endpoint.getConference();
        final Holder<Integer> previousNumExpectedPackets = new Holder<>(0);
        final Holder<Integer> previousNumReceivedPackets = new Holder<>(0);
        final Holder<Map<Long, RtcpReportBlock>> previousSsrcReportBlock = new Holder<>(new HashMap<>());
        return () -> {
            try {
                if (endpoint.isExpired()) {
                    return;
                }

                //Server stats
                TransceiverStats transceiverStats = endpoint.getTransceiver().getTransceiverStats();
                long createdAt = clock.instant().getEpochSecond();
                Map<Long, IncomingSsrcStats.Snapshot> incomingSsrcStatsMap = transceiverStats.getIncomingStats().getSsrcStats();
                if (incomingSsrcStatsMap.size() == 0) {
                    return;
                }
                double rtt = transceiverStats.getEndpointConnectionStats().getRtt();
                double jitter = incomingSsrcStatsMap.values().stream()
                        .mapToDouble(IncomingSsrcStats.Snapshot::getJitter)
                        .max().orElse(0.0);
                int numExpectedPackets = 0;
                int numReceivedPackets = 0;
                for (IncomingSsrcStats.Snapshot incomingSsrcStats : incomingSsrcStatsMap.values()) {
                    numExpectedPackets += incomingSsrcStats.getNumExpectedPackets();
                    numReceivedPackets += incomingSsrcStats.getNumReceivedPackets();
                }
                int numExpectedPacketsInterval = numExpectedPackets - previousNumExpectedPackets.value;
                int numReceivedPacketsInterval = numReceivedPackets - previousNumReceivedPackets.value;
                int numLostPacketsInterval = numExpectedPacketsInterval - numReceivedPacketsInterval;
                int fractionLost = 0;
                if (numExpectedPacketsInterval != 0 && numLostPacketsInterval > 0) {
                    fractionLost = 100 * numLostPacketsInterval /  numExpectedPacketsInterval;
                }
                previousNumExpectedPackets.value = numExpectedPackets;
                previousNumReceivedPackets.value = numReceivedPackets;
                //End of server stats

                //Clients stats
                Map<Long, RtcpReportBlock> ssrcLastReportBlock = endpoint.getSsrcReportBlock();
                endpoint.getSsrcFirstReportBlock().forEach((ssrc,reportBlock) -> previousSsrcReportBlock.value.putIfAbsent(ssrc, reportBlock));
                class SubscribedEndpointStats {
                    long packetsLost;
                    long packetsExpected;
                }
                Map<String, SubscribedEndpointStats> subscribedEndpointStats = new HashMap<>();
                ssrcLastReportBlock.forEach((ssrc, lastReportBlock) -> {
                    RtcpReportBlock prevReportBlock = previousSsrcReportBlock.value.get(ssrc);
                    if (prevReportBlock == null || lastReportBlock.getExtendedHighestSeqNum() == prevReportBlock.getExtendedHighestSeqNum()) return;
                    AbstractEndpoint subscribedEndpoint = conference.findEndpointByReceiveSSRC(lastReportBlock.getSsrc());
                    if (subscribedEndpoint == null) return;
                    SubscribedEndpointStats stats = subscribedEndpointStats.computeIfAbsent(subscribedEndpoint.getID(), k -> new SubscribedEndpointStats());
                    stats.packetsLost += lastReportBlock.getCumulativePacketsLost() - prevReportBlock.getCumulativePacketsLost();
                    stats.packetsExpected += lastReportBlock.getExtendedHighestSeqNum() - prevReportBlock.getExtendedHighestSeqNum();
                });
                List<ImmutableMap<String, Object>> subscribedEndpoints = subscribedEndpointStats.entrySet().stream()
                        .map(x -> ImmutableMap.<String,Object>of(
                                "endpointId", x.getKey(),
                                "fractionLost", x.getValue().packetsLost * 100 / x.getValue().packetsExpected
                        ))
                        .collect(Collectors.toList());
                previousSsrcReportBlock.value = ssrcLastReportBlock;
                //End of client stats

                JSONObject serverStatsNotification = new JSONObject(ImmutableMap.<String, Object>builder()
                        .put("eventType", "ENDPOINT_STATS")
                        .put("createdAt", createdAt)
                        .put("conferenceId", conference.getID())
                        .put("conferenceGid", conference.getGid())
                        .put("endpointId", endpoint.getID())
                        .put("endpointUuid", endpoint.getUuid().toString())
                        .put("payload", ImmutableMap.of(
                                "rtt", rtt,
                                "jitter", jitter,
                                "fractionLost", fractionLost,
                                "subscribedEndpoints", subscribedEndpoints
                        ))
                        .build());

                submitNotification(conference, false, serverStatsNotification);
            } catch (Exception e) {
                logger.error("Endpoint stats handler error", e);
                throw e;
            }
        };
    }

    private Callback createCallback(ConferenceNotifications conferenceNotifications) {
        return new Callback() {
            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                logger.error("External system notification error", e);
                resend(call.request(), 5);
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    assert responseBody != null;
                    if (response.isSuccessful()) {
                        conferenceNotifications.lock.lock();
                        try {
                            Request notification = conferenceNotifications.queue.poll();
                            if (notification == null) {
                                conferenceNotifications.sendingInProgress = false;
                            } else {
                                logger.info("Sending notification " + notification.body());
                                okHttpClient.newCall(notification).enqueue(this);
                            }
                        } finally {
                            conferenceNotifications.lock.unlock();
                        }
                    } else {
                        logger.error("The notification was unsuccessful. Response code = " + response.code() + ", body = " + responseBody.string());
                        resend(call.request(), 60);
                    }
                }
            }

            private void resend(Request request, long delaySeconds) {
                logger.info(String.format("The notification will be resend after %s seconds", delaySeconds));
                TaskPools.SCHEDULED_POOL.schedule(() -> okHttpClient.newCall(request).enqueue(this), delaySeconds, TimeUnit.SECONDS);
            }
        };
    }

    private void submitNotification(Conference conference, boolean lastConfNotification, JSONObject notification) {
        if (lastConfNotification) {
            // in case some messages arrive after CONFERENCE_EXPIRED event
            TaskPools.SCHEDULED_POOL.schedule(() -> conferenceNotificationsMap.remove(conference), 30, TimeUnit.SECONDS);
        }
        Request request = new Request.Builder()
                .url(notificationUrl)
                .post(RequestBody.create(notification.toJSONString(), JSON))
                .build();
        ConferenceNotifications conferenceNotifications = conferenceNotificationsMap.computeIfAbsent(conference, k -> new ConferenceNotifications());
        conferenceNotifications.lock.lock();
        try {
            if (conferenceNotifications.sendingInProgress) {
                conferenceNotifications.queue.add(request);
            } else {
                logger.info("Sending notification " + notification.toJSONString());
                okHttpClient.newCall(request).enqueue(createCallback(conferenceNotifications));
                conferenceNotifications.sendingInProgress = true;
            }
        } finally {
            conferenceNotifications.lock.unlock();
        }
    }
}

package org.jitsi.videobridge;

import com.google.common.collect.ImmutableMap;
import okhttp3.*;
import okio.Buffer;
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
            .dispatcher(new Dispatcher(TaskPools.IO_POOL))
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    static class Notifications {
        final Lock lock = new ReentrantLock(true);
        final Queue<Request> queue = new LinkedList<>();
        boolean sendingInProgress;
    }

    private final Notifications globalNotifications = new Notifications();
    private final Map<Conference, Notifications> conferenceNotificationsMap = new ConcurrentHashMap<>();
    private final Map<Endpoint, ScheduledFuture<?>> endpointScheduledFutureMap = new ConcurrentHashMap<>();

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
        String eventType = null;
        AbstractEndpoint endpoint = null;
        Conference conference = null;
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
                "eventId", UUID.randomUUID().toString(),
                "createdAt", createdAt,
                "conferenceId", conference.getID(),
                "conferenceGid", conference.getGid()
        ));
        if (endpoint != null) {
            if (endpoint instanceof Endpoint) {
                Endpoint endpnt = (Endpoint) endpoint;
                if (endpnt.isShadow()) {
                    logger.info(String.format("This is shadow endpoint. Ignoring event '%s'", event.getTopic()));
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
                logger.info(String.format("This is endpoint of type '%s'. Ignoring event '%s'", endpoint.getClass().toString(), event.getTopic()));
                return;
            }
        }

        submitConferenceNotification(conference, lastConferenceEvent, notification, logger);
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
                                "expectedPackets", x.getValue().packetsExpected,
                                "fractionLost", x.getValue().packetsLost * 100 / x.getValue().packetsExpected
                        ))
                        .collect(Collectors.toList());
                previousSsrcReportBlock.value = ssrcLastReportBlock;
                //End of client stats

                JSONObject endpointStats = new JSONObject(ImmutableMap.<String, Object>builder()
                        .put("createdAt", createdAt)
                        .put("conferenceId", conference.getID())
                        .put("conferenceGid", conference.getGid())
                        .put("endpointId", endpoint.getID())
                        .put("endpointUuid", endpoint.getUuid().toString())
                        .put("rtt", rtt)
                        .put("jitter", jitter)
                        .put("expectedPackets", numExpectedPacketsInterval)
                        .put("fractionLost", fractionLost)
                        .put("subscribedEndpoints", subscribedEndpoints)
                        .build());

                submitStatisticNotification("endpoint", endpointStats, logger);
            } catch (Exception e) {
                logger.error("Endpoint stats handler error", e);
                throw e;
            }
        };
    }

    private static String bodyToString(final RequestBody request){
        try {
            final Buffer buffer = new Buffer();
            request.writeTo(buffer);
            return buffer.readUtf8();
        }
        catch (final IOException e) {
            return "did not work";
        }
    }

    private Callback createCallback(final Notifications conferenceNotifications, final Logger logger) {
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
                                logger.info("Sending notification " + bodyToString(notification.body()));
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

    private String statisticNotificationUrl;
    private void submitStatisticNotification(String type, JSONObject notification, Logger logger) {
        if (StringUtils.isBlank(statisticNotificationUrl)) {
            statisticNotificationUrl = PropertyUtil.getValue(
                    "statistic.notification.url",
                    "JVB_STATISTIC_NOTIFICATION_URL"
            );
            if (StringUtils.isBlank(statisticNotificationUrl)) {
                logger.debug(() -> "Can not handle notification because statistic notification url is empty.");
                return;
            }
        }

        submitNotification(statisticNotificationUrl + "/" + type, globalNotifications, notification, logger);
    }

    private String conferenceNotificationUrl;
    private void submitConferenceNotification(Conference conference, boolean lastConfNotification, JSONObject notification, Logger logger) {
        if (StringUtils.isBlank(conferenceNotificationUrl)) {
            conferenceNotificationUrl = PropertyUtil.getValue(
                    "conference.notification.url",
                    "JVB_CONFERENCE_NOTIFICATION_URL"
            );
            if (StringUtils.isBlank(conferenceNotificationUrl)) {
                logger.debug(() -> "Can not handle notification because conference notification url is empty.");
                return;
            }
        }

        if (lastConfNotification) {
            // in case some messages arrive after CONFERENCE_EXPIRED event
            TaskPools.SCHEDULED_POOL.schedule(() -> conferenceNotificationsMap.remove(conference), 30, TimeUnit.SECONDS);
        }
        Notifications notifications = conferenceNotificationsMap.computeIfAbsent(conference, k -> new Notifications());

        submitNotification(conferenceNotificationUrl, notifications, notification, logger);
    }

    private void submitNotification(String notificationUrl, Notifications notifications, JSONObject notification, Logger logger) {
        Request request = new Request.Builder()
                .url(notificationUrl)
                .post(RequestBody.create(notification.toJSONString(), JSON))
                .build();
        notifications.lock.lock();
        try {
            if (notifications.sendingInProgress) {
                notifications.queue.add(request);
            } else {
                logger.info("Sending notification " + notification.toJSONString());
                okHttpClient.newCall(request).enqueue(createCallback(notifications, logger));
                notifications.sendingInProgress = true;
            }
        } finally {
            notifications.lock.unlock();
        }
    }
}

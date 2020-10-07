package org.jitsi.videobridge;

import com.google.common.collect.ImmutableMap;
import okhttp3.*;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jitsi.eventadmin.Event;
import org.jitsi.osgi.EventHandlerActivator;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.utils.logging2.LoggerImpl;
import org.json.simple.JSONObject;

import java.io.IOException;
import java.util.concurrent.ForkJoinPool;

@SuppressWarnings("unused") // started by OSGi
public class NotificationsHandler extends EventHandlerActivator {

    private static final Logger logger = new LoggerImpl(NotificationsHandler.class.getName());

    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient okHttpClient;

    private String notificationUrl;

    public NotificationsHandler() {
        super(new String[]{
                EventFactory.CONFERENCE_CREATED_TOPIC,
                EventFactory.CONFERENCE_EXPIRED_TOPIC,
                EventFactory.ENDPOINT_CREATED_TOPIC,
                EventFactory.ENDPOINT_EXPIRED_TOPIC
        });
        okHttpClient = new OkHttpClient();

        notificationUrl = System.getProperty("conference.notification.url");

        if (StringUtils.isBlank(notificationUrl)) {
            logger.info("System property `conference.notification.url` is not set. Trying to get it from google instance metadata");
            ForkJoinPool.commonPool().execute(() -> {
                Request request = new Request.Builder()
                        .addHeader("Metadata-Flavor", "Google")
                        .url("http://metadata.google.internal/computeMetadata/v1/instance/attributes/JVB_CONFERENCE_NOTIFICATION_URL")
                        .get().build();
                try (Response response = okHttpClient.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        notificationUrl = response.body().string();
                        logger.info("Notification url set to: " + notificationUrl);
                    } else {
                        String msg = String.format(
                                "Can not get `JVB_NOTIFICATION_URL` from google cloud metadata. Response(code=%s,message=%s)",
                                response.code(),
                                response.body() == null ? "" : response.body().string()
                        );
                        logger.error(msg);
                    }
                } catch (Exception e) {
                    logger.error("Can not get `JVB_NOTIFICATION_URL` from google cloud metadata", e);
                }
            });
        } else {
            logger.info("Notification url set to: " + notificationUrl);
        }
    }

    @Override
    public void handleEvent(Event event) {
        if (event == null) {
            logger.debug(() -> "Could not handle an event because it was null.");
            return;
        }
        if (StringUtils.isBlank(notificationUrl)) {
            logger.debug(() -> "Could not handle an event because notification url was blank.");
            return;
        }
        String eventType = null;
        Endpoint endpoint = null;
        Conference conference = null;
        switch (event.getTopic()) {
            case EventFactory.CONFERENCE_CREATED_TOPIC:
                eventType = "CONFERENCE_CREATED";
                conference = (Conference) event.getProperty(EventFactory.EVENT_SOURCE);
                break;
            case EventFactory.CONFERENCE_EXPIRED_TOPIC:
                eventType = "CONFERENCE_EXPIRED";
                conference = (Conference) event.getProperty(EventFactory.EVENT_SOURCE);
                break;
            case EventFactory.ENDPOINT_CREATED_TOPIC:
                eventType = "ENDPOINT_CREATED";
                endpoint = (Endpoint) event.getProperty(EventFactory.EVENT_SOURCE);
                break;
            case EventFactory.ENDPOINT_EXPIRED_TOPIC:
                eventType = "ENDPOINT_EXPIRED";
                endpoint = (Endpoint) event.getProperty(EventFactory.EVENT_SOURCE);
                break;
        }
        if (eventType != null) {
            Logger logger = NotificationsHandler.logger.createChildLogger(NotificationsHandler.class.getName());
            if (conference == null && endpoint != null) {
                logger.addContext("epId", endpoint.getID());
                conference = endpoint.getConference();
            }
            logger.addContext(ImmutableMap.of("confId", conference.getID(), "gid", conference.getGid()));
            JSONObject notification = new JSONObject(ImmutableMap.of(
                    "eventType", eventType,
                    "conferenceId", conference.getID(),
                    "conferenceGid", conference.getGid()
            ));
            if (endpoint != null) {
                if (endpoint.isShadow()) {
                    logger.info("This is shadow endpoint. Ignoring notification");
                    return;
                }
                notification.put("endpointId", endpoint.getID());
                notification.put("endpointUuid", endpoint.getUuid().toString());
            }

            String notificationStr = notification.toJSONString();

            logger.info("Sending notification " + notificationStr);

            Request request = new Request.Builder()
                    .url(notificationUrl)
                    .post(RequestBody.create(notificationStr, JSON))
                    .build();

            okHttpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NotNull Call call, @NotNull IOException e) {
                    logger.error("External system notification error", e);
                }

                @Override
                public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
                    logger.info(response.body().string());
                }
            });
        }
    }
}

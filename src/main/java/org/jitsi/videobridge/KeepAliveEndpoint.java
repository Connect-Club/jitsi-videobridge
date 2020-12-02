package org.jitsi.videobridge;

import org.jitsi.eventadmin.Event;
import org.jitsi.osgi.EventHandlerActivator;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.utils.logging2.LoggerImpl;
import org.jitsi.videobridge.util.TaskPools;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@SuppressWarnings("unused") // started by OSGi
public class KeepAliveEndpoint extends EventHandlerActivator {

    private static final Logger logger = new LoggerImpl(KeepAliveEndpoint.class.getName());

    private static final Duration PINNED_INACTIVITY_TIMEOUT_LIMIT = Duration.ofMinutes(1);

    public KeepAliveEndpoint() {
        super(new String[]{
                EventFactory.ENDPOINT_CREATED_TOPIC,
                EventFactory.ENDPOINT_EXPIRED_TOPIC
        });
    }

    private final Map<Endpoint, ScheduledFuture<?>> m = new HashMap<>();

    @Override
    public void handleEvent(Event event) {
        if (event == null) {
            logger.debug(() -> "Could not handle an event because it was null.");
            return;
        }

        Endpoint endpoint = (Endpoint) event.getProperty(EventFactory.EVENT_SOURCE);
        switch (event.getTopic()) {
            case EventFactory.ENDPOINT_CREATED_TOPIC:
                synchronized (m) {
                    if (!m.containsKey(endpoint)) {
                        ScheduledFuture<?> scheduledFuture = TaskPools.SCHEDULED_POOL.scheduleAtFixedRate(
                                new EndpointRunnable(endpoint),
                                PINNED_INACTIVITY_TIMEOUT_LIMIT.getSeconds(),
                                20,
                                TimeUnit.SECONDS
                        );
                        m.put(endpoint, scheduledFuture);
                    }
                }
                break;
            case EventFactory.ENDPOINT_EXPIRED_TOPIC:
                synchronized (m) {
                    ScheduledFuture<?> scheduledFuture = m.remove(endpoint);
                    if (scheduledFuture != null) {
                        scheduledFuture.cancel(false);
                    }
                }
                break;
        }


    }

    static class EndpointRunnable implements Runnable {

        private final Endpoint endpoint;

        EndpointRunnable(Endpoint endpoint) {
            this.endpoint = endpoint;
        }

        @Override
        public void run() {
            if (endpoint.isExpired()) return;

            endpoint.selectedEndpointsChanged(endpoint.getTooLongInactivePinnedEndpoints(PINNED_INACTIVITY_TIMEOUT_LIMIT)
                    .stream().map(AbstractEndpoint::getID)
                    .collect(Collectors.toSet())
            );
        }
    }
}

package com.judepereira.jupiter2.ui.rail;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

@Service
public class WorkspaceRailRefreshService {

    private static final Logger log = LogManager.getLogger(WorkspaceRailRefreshService.class);
    static final String WORKSPACE_RAIL_REFRESH_EVENT = "workspace-rail-refresh";

    private final Supplier<SseEmitter> emitterFactory;
    private final EventSender eventSender;
    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean shutdownStarted = new AtomicBoolean(false);

    public WorkspaceRailRefreshService() {
        this(() -> new SseEmitter(0L), WorkspaceRailRefreshService::sendNamedEvent);
    }

    WorkspaceRailRefreshService(Supplier<SseEmitter> emitterFactory, EventSender eventSender) {
        this.emitterFactory = emitterFactory;
        this.eventSender = eventSender;
    }

    public SseEmitter connect() {
        SseEmitter emitter = emitterFactory.get();
        if (shutdownStarted.get()) {
            emitter.complete();
            return emitter;
        }

        emitters.add(emitter);
        emitter.onCompletion(() -> disconnect(emitter));
        emitter.onTimeout(() -> disconnect(emitter));
        emitter.onError(throwable -> disconnect(emitter));

        if (shutdownStarted.get()) {
            disconnect(emitter);
            emitter.complete();
        }

        return emitter;
    }

    public void publishWorkspaceRailRefresh() {
        publish(WORKSPACE_RAIL_REFRESH_EVENT, "");
    }

    @EventListener
    public void onContextClosed(ContextClosedEvent event) {
        if (!shutdownStarted.compareAndSet(false, true)) {
            return;
        }

        List<SseEmitter> activeEmitters = List.copyOf(emitters);
        emitters.clear();

        for (SseEmitter emitter : activeEmitters) {
            try {
                emitter.complete();
            } catch (Exception e) {
                log.error("Failed to complete workspace rail SSE client during shutdown", e);
            }
        }
    }

    private void publish(String eventName, Object data) {
        for (SseEmitter emitter : emitters) {
            try {
                eventSender.send(emitter, eventName, data);
            } catch (Exception e) {
                log.error("Failed to send workspace rail refresh to SSE client", e);
                disconnect(emitter);
                try {
                    emitter.completeWithError(e);
                } catch (Exception completionError) {
                    log.error("Failed to close failed workspace rail SSE client", completionError);
                }
            }
        }
    }

    private void disconnect(SseEmitter emitter) {
        emitters.remove(emitter);
    }

    int activeEmitterCount() {
        return emitters.size();
    }

    private static void sendNamedEvent(SseEmitter emitter, String eventName, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(eventName).data(data));
    }

    @FunctionalInterface
    interface EventSender {
        void send(SseEmitter emitter, String eventName, Object data) throws IOException;
    }
}

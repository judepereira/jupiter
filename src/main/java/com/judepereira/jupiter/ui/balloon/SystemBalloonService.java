package com.judepereira.jupiter.ui.balloon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

@Log4j2
@Service
@RequiredArgsConstructor
public class SystemBalloonService {

    private static final int MAX_PUBLISHED_BALLOONS = 100;

    private final ObjectMapper objectMapper;
    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();
    private final Set<String> initializedShellIds = ConcurrentHashMap.newKeySet();
    private final List<SystemBalloon> publishedBalloons = new CopyOnWriteArrayList<>();
    private final AtomicBoolean shutdownStarted = new AtomicBoolean(false);

    public SseEmitter connect() {
        SseEmitter emitter = new SseEmitter(0L);
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

    public boolean markShellInitialized(String shellId) {
        if (shellId == null || shellId.isBlank()) {
            return true;
        }
        return initializedShellIds.add(shellId);
    }

    @EventListener
    public void onContextClosed(ContextClosedEvent event) {
        if (!shutdownStarted.compareAndSet(false, true)) {
            return;
        }

        initializedShellIds.clear();
        List<SseEmitter> activeEmitters = List.copyOf(emitters);
        emitters.clear();

        for (SseEmitter emitter : activeEmitters) {
            try {
                emitter.complete();
            } catch (Exception e) {
                log.error("Failed to complete system balloon SSE client during shutdown", e);
            }
        }
    }

    public void publishError(String body) {
        publish(SystemBalloon.Type.ERROR, null, body);
    }

    public void publishError(String title, String body) {
        publish(SystemBalloon.Type.ERROR, title, body);
    }

    public void publishSuccess(String body) {
        publish(SystemBalloon.Type.SUCCESS, null, body);
    }

    public void publishSuccess(String title, String body) {
        publish(SystemBalloon.Type.SUCCESS, title, body);
    }

    public void publishWarning(String body) {
        publish(SystemBalloon.Type.WARNING, null, body);
    }

    public void publishWarning(String title, String body) {
        publish(SystemBalloon.Type.WARNING, title, body);
    }

    public void publishWarning(SseEmitter emitter, String title, String body) {
        sendToEmitter(emitter, serialize(new SystemBalloon(UUID.randomUUID(), SystemBalloon.Type.WARNING, title, body, Instant.now())));
    }

    private void publish(SystemBalloon.Type type, String title, String body) {
        String payload = serialize(new SystemBalloon(UUID.randomUUID(), type, title, body, Instant.now()));
        for (SseEmitter emitter : emitters) {
            sendToEmitter(emitter, payload);
        }
    }

    private String serialize(SystemBalloon balloon) {
        synchronized (publishedBalloons) {
            publishedBalloons.add(balloon);
            while (publishedBalloons.size() > MAX_PUBLISHED_BALLOONS) {
                publishedBalloons.remove(0);
            }
        }
        try {
            return objectMapper.copy().findAndRegisterModules().writeValueAsString(balloon);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize system balloon", e);
            throw new IllegalStateException("Failed to serialize system balloon", e);
        }
    }

    private void sendToEmitter(SseEmitter emitter, String payload) {
        try {
            emitter.send(SseEmitter.event().name("balloon").data(payload));
        } catch (Exception e) {
            if (!(e instanceof AsyncRequestNotUsableException)) {
                log.error("Failed to send system balloon to SSE client", e);
            }
            disconnect(emitter);
            try {
                emitter.completeWithError(e);
            } catch (Exception completionError) {
                log.error("Failed to close failed SSE client", completionError);
            }
        }
    }

    private void disconnect(SseEmitter emitter) {
        emitters.remove(emitter);
    }

    int activeEmitterCount() {
        return emitters.size();
    }

    List<SystemBalloon> publishedBalloons() {
        synchronized (publishedBalloons) {
            return List.copyOf(publishedBalloons);
        }
    }
}

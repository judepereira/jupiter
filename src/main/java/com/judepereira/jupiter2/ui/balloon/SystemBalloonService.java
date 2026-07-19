package com.judepereira.jupiter2.ui.balloon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Log4j2
@Service
@RequiredArgsConstructor
public class SystemBalloonService {

    private static final int MAX_PUBLISHED_BALLOONS = 100;

    private final ObjectMapper objectMapper;
    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();
    private final List<SystemBalloon> publishedBalloons = new CopyOnWriteArrayList<>();

    public SseEmitter connect() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> disconnect(emitter));
        emitter.onTimeout(() -> disconnect(emitter));
        emitter.onError(throwable -> disconnect(emitter));
        return emitter;
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

    private void publish(SystemBalloon.Type type, String title, String body) {
        SystemBalloon balloon = new SystemBalloon(UUID.randomUUID(), type, title, body, Instant.now());
        synchronized (publishedBalloons) {
            publishedBalloons.add(balloon);
            while (publishedBalloons.size() > MAX_PUBLISHED_BALLOONS) {
                publishedBalloons.remove(0);
            }
        }
        String payload;
        try {
            payload = objectMapper.copy().findAndRegisterModules().writeValueAsString(balloon);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize system balloon", e);
            throw new IllegalStateException("Failed to serialize system balloon", e);
        }

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("balloon").data(payload));
            } catch (Exception e) {
                log.error("Failed to send system balloon to SSE client", e);
                disconnect(emitter);
                try {
                    emitter.completeWithError(e);
                } catch (Exception completionError) {
                    log.error("Failed to close failed SSE client", completionError);
                }
            }
        }
    }

    private void disconnect(SseEmitter emitter) {
        emitters.remove(emitter);
    }

    List<SystemBalloon> publishedBalloons() {
        synchronized (publishedBalloons) {
            return List.copyOf(publishedBalloons);
        }
    }
}

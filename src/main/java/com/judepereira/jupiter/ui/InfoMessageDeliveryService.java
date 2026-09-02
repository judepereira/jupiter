package com.judepereira.jupiter.ui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.persistence.Persistence;
import com.judepereira.jupiter.persistence.Persistence.InfoMessageAppendedEvent;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

@Log4j2
@Service
public class InfoMessageDeliveryService {

    static final String INFO_MESSAGE_EVENT = "info-message";

    private final ObjectMapper objectMapper;
    private final Supplier<SseEmitter> emitterFactory;
    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean shutdownStarted = new AtomicBoolean();

    @Autowired
    public InfoMessageDeliveryService(ObjectMapper objectMapper) {
        this(objectMapper, () -> new SseEmitter(0L));
    }

    InfoMessageDeliveryService(ObjectMapper objectMapper, Supplier<SseEmitter> emitterFactory) {
        this.objectMapper = objectMapper;
        this.emitterFactory = emitterFactory;
    }

    public SseEmitter connect() {
        SseEmitter emitter = emitterFactory.get();
        if (shutdownStarted.get()) {
            emitter.complete();
            return emitter;
        }
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(error -> emitters.remove(emitter));
        send(emitter, SseEmitter.event().comment("connected"));
        return emitter;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onInfoMessageAppended(InfoMessageAppendedEvent event) {
        send(event);
    }

    @EventListener
    public void onContextClosed(ContextClosedEvent event) {
        if (!shutdownStarted.compareAndSet(false, true)) return;
        List<SseEmitter> active = List.copyOf(emitters);
        emitters.clear();
        active.forEach(SseEmitter::complete);
    }

    private void send(InfoMessageAppendedEvent event) {
        String payload;
        try {
            payload = objectMapper.copy().findAndRegisterModules().writeValueAsString(new InfoMessageNotification(event.sessionId(), event.message()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize info message", e);
        }
        for (SseEmitter emitter : emitters) {
            send(emitter, SseEmitter.event().name(INFO_MESSAGE_EVENT).data(payload));
        }
    }

    private void send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (Exception e) {
            if (!(e instanceof AsyncRequestNotUsableException)) log.error("Failed to send info message SSE event", e);
            emitters.remove(emitter);
            try { emitter.completeWithError(e); } catch (Exception completionError) { log.error("Failed to close info message SSE client", completionError); }
        }
    }

    int activeEmitterCount() {
        return emitters.size();
    }

    private record InfoMessageNotification(long sessionId, Persistence.ChatMessageView message) {
    }
}

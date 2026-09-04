package com.judepereira.jupiter.config;

import com.judepereira.jupiter.ui.rail.WorkspaceRailRefreshService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.function.Supplier;

@Configuration
public class UiSseConfig {

    @Bean
    public Supplier<SseEmitter> sseEmitterFactory() {
        return () -> new SseEmitter(0L);
    }

    @Bean
    public WorkspaceRailRefreshService.EventSender workspaceRailEventSender() {
        return (emitter, eventName, data) -> sendNamedEvent(emitter, eventName, data);
    }

    private static void sendNamedEvent(SseEmitter emitter, String eventName, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(eventName).data(data));
    }
}

package com.judepereira.jupiter.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.persistence.Persistence;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InfoMessageDeliveryServiceTests {

    @Test
    void committedInfoMessageIsBroadcastWithItsSessionTarget() {
        TestEmitter emitter = new TestEmitter();
        InfoMessageDeliveryService service = new InfoMessageDeliveryService(new ObjectMapper(), () -> emitter);
        service.connect();

        var message = new Persistence.ChatMessageView("info", "Git updated", 42L, false, "info-1", 42L, List.of(), null);
        service.onInfoMessageAppended(new Persistence.InfoMessageAppendedEvent(7L, message));

        assertThat(emitter.events).hasSize(2);
        assertThat(emitter.events.get(1).build()).anySatisfy(data ->
                assertThat(String.valueOf(data.getData())).contains("info-1", "Git updated", "sessionId", "7"));
        assertThat(service.activeEmitterCount()).isEqualTo(1);
    }

    private static final class TestEmitter extends SseEmitter {
        private final List<SseEventBuilder> events = new java.util.ArrayList<>();

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            events.add(builder);
        }
    }
}

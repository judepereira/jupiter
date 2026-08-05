package com.judepereira.jupiter.ui.balloon;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SystemBalloonServiceTests {

    @Test
    void publishRecordsBalloonWithoutConnectedClients() {
        SystemBalloonService service = new SystemBalloonService(new ObjectMapper());

        service.publishError("Git error", "Could not check out existing Git branch \"missing\".\n\nGit output:\nfatal: invalid reference: missing");

        assertThat(service.publishedBalloons()).hasSize(1);
        SystemBalloon balloon = service.publishedBalloons().get(0);
        assertThat(balloon.type()).isEqualTo(SystemBalloon.Type.ERROR);
        assertThat(balloon.title()).isEqualTo("Git error");
        assertThat(balloon.body()).contains("Could not check out existing Git branch");
        assertThat(balloon.body()).contains("missing");
        assertThat(balloon.body()).contains("Git output:");
        assertThat(balloon.body()).contains("fatal: invalid reference: missing");
        assertThat(balloon.id()).isNotNull();
        assertThat(balloon.createdAt()).isNotNull();
    }

    @Test
    void closeContextCompletesAndRemovesActiveEmitters() {
        SystemBalloonService service = new SystemBalloonService(new ObjectMapper());
        SseEmitter emitter = service.connect();

        service.onContextClosed(new ContextClosedEvent(mock(ApplicationContext.class)));

        assertThat(service.activeEmitterCount()).isZero();
        assertThat(isCompleted(emitter)).isTrue();
    }

    @Test
    void connectAfterShutdownReturnsCompletedUntrackedEmitter() {
        SystemBalloonService service = new SystemBalloonService(new ObjectMapper());

        service.onContextClosed(new ContextClosedEvent(mock(ApplicationContext.class)));
        SseEmitter emitter = service.connect();

        assertThat(service.activeEmitterCount()).isZero();
        assertThat(isCompleted(emitter)).isTrue();
    }

    private static boolean isCompleted(ResponseBodyEmitter emitter) {
        try {
            Field completeField = ResponseBodyEmitter.class.getDeclaredField("complete");
            completeField.setAccessible(true);
            return completeField.getBoolean(emitter);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}

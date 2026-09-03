package com.judepereira.jupiter.ui.balloon;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SystemBalloonServiceTests {

    @Test
    void connectSendsCommentHandshakeWithoutPublishingBalloon() {
        TestEmitter emitter = new TestEmitter();
        SystemBalloonService service = new SystemBalloonService(new ObjectMapper(), () -> emitter);

        service.connect();

        assertThat(emitter.sentEvent).isNotNull();
        assertThat(emitter.sentEvent.build()).singleElement()
                .satisfies(data -> assertThat(data.getData()).isEqualTo(":connected\n\n"));
        assertThat(service.publishedBalloons()).isEmpty();
        assertThat(service.activeEmitterCount()).isEqualTo(1);
    }

    @Test
    void failedConnectHandshakeDisconnectsAndCompletesWithError() {
        IOException failure = new IOException("client disconnected");
        AtomicReference<Throwable> completionError = new AtomicReference<>();
        TestEmitter emitter = new TestEmitter(failure, completionError);
        SystemBalloonService service = new SystemBalloonService(new ObjectMapper(), () -> emitter);

        service.connect();

        assertThat(service.activeEmitterCount()).isZero();
        assertThat(completionError).hasValue(failure);
    }

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
        TestEmitter emitter = new TestEmitter();
        SystemBalloonService service = new SystemBalloonService(new ObjectMapper(), () -> emitter);
        service.connect();

        service.onContextClosed(new ContextClosedEvent(mock(ApplicationContext.class)));

        assertThat(service.activeEmitterCount()).isZero();
        assertThat(emitter.completed).isTrue();
    }

    @Test
    void connectAfterShutdownReturnsCompletedUntrackedEmitter() {
        TestEmitter emitter = new TestEmitter();
        SystemBalloonService service = new SystemBalloonService(new ObjectMapper(), () -> emitter);

        service.onContextClosed(new ContextClosedEvent(mock(ApplicationContext.class)));
        SseEmitter connected = service.connect();

        assertThat(connected).isSameAs(emitter);
        assertThat(service.activeEmitterCount()).isZero();
        assertThat(emitter.completed).isTrue();
    }

    private static final class TestEmitter extends SseEmitter {
        private final IOException sendFailure;
        private final AtomicReference<Throwable> completionError;
        private volatile SseEventBuilder sentEvent;
        private volatile boolean completed;

        private TestEmitter() {
            this(null, new AtomicReference<>());
        }

        private TestEmitter(IOException sendFailure, AtomicReference<Throwable> completionError) {
            this.sendFailure = sendFailure;
            this.completionError = completionError;
        }

        @Override
        public void send(SseEmitter.SseEventBuilder builder) throws IOException {
            if (sendFailure != null) {
                throw sendFailure;
            }
            sentEvent = builder;
        }

        @Override
        public void complete() {
            completed = true;
            super.complete();
        }

        @Override
        public void completeWithError(Throwable ex) {
            completed = true;
            completionError.set(ex);
            super.completeWithError(ex);
        }
    }
}

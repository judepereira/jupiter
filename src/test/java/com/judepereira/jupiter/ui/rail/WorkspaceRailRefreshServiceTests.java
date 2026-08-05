package com.judepereira.jupiter.ui.rail;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WorkspaceRailRefreshServiceTests {

    @Test
    void publishWorkspaceRailRefreshSendsNamedEventToConnectedEmitters() {
        TestEmitter emitter = new TestEmitter();
        AtomicReference<String> eventName = new AtomicReference<>();
        AtomicReference<Object> payload = new AtomicReference<>();
        WorkspaceRailRefreshService service = new WorkspaceRailRefreshService(() -> emitter,
                (target, name, data) -> {
                    eventName.set(name);
                    payload.set(data);
                });

        service.connect();
        service.publishWorkspaceRailRefresh();

        assertThat(eventName.get()).isEqualTo("workspace-rail-refresh");
        assertThat(payload.get()).isEqualTo("");
        assertThat(service.activeEmitterCount()).isEqualTo(1);
    }

    @Test
    void closeContextCompletesAndRemovesActiveEmitters() {
        TestEmitter emitter = new TestEmitter();
        WorkspaceRailRefreshService service = new WorkspaceRailRefreshService(() -> emitter,
                (target, name, data) -> { });

        service.connect();
        service.onContextClosed(new ContextClosedEvent(mock(ApplicationContext.class)));

        assertThat(service.activeEmitterCount()).isZero();
        assertThat(emitter.completed).isTrue();
    }

    @Test
    void connectAfterShutdownReturnsCompletedUntrackedEmitter() {
        TestEmitter emitter = new TestEmitter();
        WorkspaceRailRefreshService service = new WorkspaceRailRefreshService(() -> emitter,
                (target, name, data) -> { });

        service.onContextClosed(new ContextClosedEvent(mock(ApplicationContext.class)));
        SseEmitter connected = service.connect();

        assertThat(connected).isSameAs(emitter);
        assertThat(service.activeEmitterCount()).isZero();
        assertThat(emitter.completed).isTrue();
    }

    private static final class TestEmitter extends SseEmitter {
        private volatile boolean completed;

        @Override
        public void complete() {
            completed = true;
            super.complete();
        }

        @Override
        public void completeWithError(Throwable ex) {
            completed = true;
            super.completeWithError(ex);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            super.send(builder);
        }
    }
}

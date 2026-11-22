package com.judepereira.jupiter.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.catalog.AgentDefinitionService;
import com.judepereira.jupiter.agent.config.AgentProperties;
import com.judepereira.jupiter.agent.harness.AgentTurnRequest;
import com.judepereira.jupiter.agent.harness.AgentTurnResult;
import com.judepereira.jupiter.agent.harness.CancellationToken;
import com.judepereira.jupiter.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter.agent.llm.AgentStreamListener;
import com.judepereira.jupiter.command.CommandStreamService;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.ContextCompactionService;
import com.judepereira.jupiter.persistence.Persistence.ChatMessageView;
import com.judepereira.jupiter.persistence.TestAppStateSupport;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class ChatStreamRuntimeTests {
    @Test
    void completionSnapshotFailureStillSendsTerminalEventAndCompletesEmitter() throws Exception {
        AppStateService state = mock(AppStateService.class);
        ChatToolCallHtmlService html = mock(ChatToolCallHtmlService.class);
        CodingAgentHarness harness = mock(CodingAgentHarness.class);
        var runtime = new ChatStreamRuntime(harness, new AgentProperties(), state, mock(AgentDefinitionService.class),
                null, null, mock(ContextCompactionService.class), null, null, mock(CommandStreamService.class),
                new ActiveStreamRegistryService(), new ChatPresentationService(), html, null);
        var active = active(new CancellationToken());
        var listenerRef = new AtomicReference<AgentStreamListener>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var emitter = new RecordingEmitter();
        active.emitters().add(emitter);
        when(state.completeAssistantMessage(7L, "assistant", "finished", List.of()))
                .thenReturn(new ChatMessageView("ASSISTANT", "finished", 1L, false, "assistant", 10L, List.of(), null));
        when(html.hostSnapshot(7L, "assistant")).thenThrow(new IllegalStateException("snapshot unavailable"));
        doAnswer(invocation -> {
            listenerRef.set(invocation.getArgument(1));
            entered.countDown();
            release.await();
            return null;
        }).when(harness).runTurnStreaming(Mockito.any(), Mockito.any());
        runtime.activeStreams.put("assistant", active);
        runtime.startActiveStream("assistant", active);
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            var result = mock(AgentTurnResult.class);
            when(result.getFinalText()).thenReturn("finished");
            listenerRef.get().onComplete(result);

            assertThat(emitter.events())
                    .containsExactly(new RecordedEvent("done", Map.of("text", "finished", "completedTs", 10)));
            assertThat(emitter.completed()).isTrue();
        } finally {
            release.countDown();
        }
    }

    @Test
    void errorSnapshotFailureStillSendsTerminalEventAndCompletesEmitter() throws Exception {
        AppStateService state = mock(AppStateService.class);
        ChatToolCallHtmlService html = mock(ChatToolCallHtmlService.class);
        CodingAgentHarness harness = mock(CodingAgentHarness.class);
        var runtime = new ChatStreamRuntime(harness, new AgentProperties(), state, mock(AgentDefinitionService.class),
                null, null, mock(ContextCompactionService.class), null, null, mock(CommandStreamService.class),
                new ActiveStreamRegistryService(), new ChatPresentationService(), html, null);
        var active = active(new CancellationToken());
        var listenerRef = new AtomicReference<AgentStreamListener>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var emitter = new RecordingEmitter();
        active.emitters().add(emitter);
        when(state.failAssistantMessage(7L, "assistant", "Agent execution failed: failure"))
                .thenReturn(new ChatMessageView("ASSISTANT", "failed", 1L, false, "assistant", 10L, List.of(), null));
        when(html.hostSnapshot(7L, "assistant")).thenThrow(new IllegalStateException("snapshot unavailable"));
        doAnswer(invocation -> {
            listenerRef.set(invocation.getArgument(1));
            entered.countDown();
            release.await();
            return null;
        }).when(harness).runTurnStreaming(Mockito.any(), Mockito.any());
        runtime.activeStreams.put("assistant", active);
        runtime.startActiveStream("assistant", active);
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            listenerRef.get().onError(new IllegalStateException("failure"));

            assertThat(emitter.events())
                    .containsExactly(new RecordedEvent("error", Map.of("message", "failure", "completedTs", 10)));
            assertThat(emitter.completed()).isTrue();
        } finally {
            release.countDown();
        }
    }

    @Test
    void attachmentWaitsForDeltaPersistenceAndReceivesNoDuplicateDelta() throws Exception {
        AppStateService state = mock(AppStateService.class);
        CodingAgentHarness harness = mock(CodingAgentHarness.class);
        var runtime = runtime(harness, state, new ActiveStreamRegistryService());
        var active = active(new CancellationToken());
        var listenerRef = new AtomicReference<AgentStreamListener>();
        var entered = new CountDownLatch(1);
        var releaseHarness = new CountDownLatch(1);
        var releaseDelta = new CountDownLatch(1);
        var deltaEntered = new CountDownLatch(1);
        doAnswer(invocation -> {
            listenerRef.set(invocation.getArgument(1));
            entered.countDown();
            releaseHarness.await();
            return null;
        }).when(harness).runTurnStreaming(Mockito.any(), Mockito.any());
        doAnswer(invocation -> {
            deltaEntered.countDown();
            assertThat(releaseDelta.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(state).updateStreamingAssistantText(7L, "assistant", "delta");
        when(state.stopAssistantMessage(7L, "assistant", "delta next")).thenReturn(new ChatMessageView("ASSISTANT",
                "delta next\n\nAction Interrupted", 1L, false, "assistant", 10L, List.of(), null));
        var mvc = MockMvcBuilders.standaloneSetup(new StreamEndpoint(runtime)).build();
        var response = new AtomicReference<MvcResult>();
        var failure = new AtomicReference<Throwable>();
        var attaching = new CountDownLatch(1);
        var attached = new CountDownLatch(1);
        runtime.activeStreams.put("assistant", active);
        runtime.startActiveStream("assistant", active);
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var deltaThread = Thread.startVirtualThread(() -> listenerRef.get().onTextDelta("delta"));
            assertThat(deltaEntered.await(5, TimeUnit.SECONDS)).isTrue();
            var attachThread = Thread.startVirtualThread(() -> {
                attaching.countDown();
                try {
                    response.set(mvc.perform(get("/stream")).andExpect(request().asyncStarted()).andReturn());
                } catch (Throwable e) {
                    failure.set(e);
                } finally {
                    attached.countDown();
                }
            });
            assertThat(attaching.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(attached.await(100, TimeUnit.MILLISECONDS)).isFalse();
            releaseDelta.countDown();
            deltaThread.join(5000);
            attachThread.join(5000);
            assertThat(attached.getCount()).isZero();
            assertThat(failure.get()).isNull();
            listenerRef.get().onTextDelta(" next");
            runtime.stopActiveStream("assistant", 7L);
            var wire = mvc.perform(asyncDispatch(response.get())).andReturn().getResponse().getContentAsString();
            var events = new ArrayList<RecordedEvent>();
            for (String event : wire.split("\n\n")) {
                if (!event.isBlank()) {
                    events.add(RecordingEmitter.decode(event));
                }
            }
            assertThat(events).containsExactly(new RecordedEvent("delta", Map.of("text", "delta")),
                    new RecordedEvent("delta", Map.of("text", " next")), new RecordedEvent("stopped",
                            Map.of("message", "delta next\n\nAction Interrupted", "completedTs", 10)));
        } finally {
            releaseDelta.countDown();
            releaseHarness.countDown();
        }
    }

    @RestController
    static class StreamEndpoint {
        private final ChatStreamRuntime runtime;

        StreamEndpoint(ChatStreamRuntime runtime) {
            this.runtime = runtime;
        }

        @GetMapping("/stream")
        public SseEmitter stream() {
            return runtime.streamChat("assistant");
        }
    }

    @Test
    void actualLateCompletionAfterStopDoesNotOverwriteStoppedMessage() throws Exception {
        AppStateService state = TestAppStateSupport.appStateService();
        long sessionId = state.ensureChatSession(".").id();
        state.appendPendingAssistantMessage(sessionId, "assistant", "Thinking", null);
        CodingAgentHarness harness = mock(CodingAgentHarness.class);
        var registry = new ActiveStreamRegistryService();
        var runtime = runtime(harness, state, registry);
        var token = new CancellationToken();
        var active = active(token, sessionId);
        var listenerRef = new AtomicReference<AgentStreamListener>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            listenerRef.set(invocation.getArgument(1));
            entered.countDown();
            release.await();
            return null;
        }).when(harness).runTurnStreaming(Mockito.any(), Mockito.any());
        active.accumulatedText().get().append("partial");
        runtime.activeStreams.put("assistant", active);
        registry.claim("assistant", sessionId, ".");
        runtime.startActiveStream("assistant", active);

        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            runtime.stopActiveStream("assistant", sessionId);
            var stopped = state.loadAssistantMessageDetails(sessionId, "assistant");
            var lateResult = mock(AgentTurnResult.class);
            when(lateResult.getFinalText()).thenReturn("must not overwrite stopped message");
            listenerRef.get().onComplete(lateResult);

            assertThat(stopped.pending()).isFalse();
            assertThat(stopped.text()).contains("partial", "Action Interrupted");
            assertThat(state.loadAssistantMessageDetails(sessionId, "assistant")).isEqualTo(stopped);
            assertThat(token.isCancelled()).isTrue();
            assertThat(active.completed().get()).isTrue();
            assertThat(active.finished().get()).isTrue();
            assertThat(runtime.activeStreams).doesNotContainKey("assistant");
            assertThat(registry.hasActiveStreamForSession(sessionId)).isFalse();
            assertThat(active.emitters()).isEmpty();
        } finally {
            release.countDown();
        }
    }

    @Test
    void deltaPersistenceFailureCancelsBeforeRegistryRelease() throws Exception {
        AppStateService state = mock(AppStateService.class);
        CodingAgentHarness harness = mock(CodingAgentHarness.class);
        var registry = new ActiveStreamRegistryService();
        var runtime = runtime(harness, state, registry);
        var token = new CancellationToken();
        var active = active(token);
        var listenerRef = new AtomicReference<AgentStreamListener>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doThrow(new IllegalStateException("write failed")).when(state).updateStreamingAssistantText(7L, "assistant",
                "delta");
        doAnswer(invocation -> {
            listenerRef.set(invocation.getArgument(1));
            entered.countDown();
            release.await();
            return null;
        }).when(harness).runTurnStreaming(Mockito.any(), Mockito.any());
        runtime.activeStreams.put("assistant", active);
        registry.claim("assistant", 7L, ".");
        runtime.startActiveStream("assistant", active);
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            listenerRef.get().onTextDelta("delta");

            assertThat(token.isCancelled()).isTrue();
            assertThat(registry.hasActiveStreamForSession(7L)).isFalse();
            assertThat(runtime.activeStreams).doesNotContainKey("assistant");
            release.countDown();
            listenerRef.get().onTextDelta("must not persist");
            Mockito.verify(state, Mockito.never()).updateStreamingAssistantText(7L, "assistant", "must not persist");
        } finally {
            release.countDown();
        }
    }

    @Test
    void stopFallsBackToSessionWhenAssistantIdIsMissing() {
        AppStateService state = mock(AppStateService.class);
        var registry = new ActiveStreamRegistryService();
        var runtime = runtime(state, registry);
        var active = active(new CancellationToken());
        runtime.activeStreams.put("assistant", active);
        registry.claim("assistant", 7L, ".");

        assertThat(runtime.stopActiveStream(null, 7L)).isTrue();
        assertThat(runtime.activeStreams).doesNotContainKey("assistant");
    }

    @Test
    void stopRejectsExplicitAssistantIdOwnedByAnotherSessionAndPreservesRegistry() {
        AppStateService state = mock(AppStateService.class);
        var registry = new ActiveStreamRegistryService();
        var runtime = runtime(state, registry);
        var active = active(new CancellationToken());
        runtime.activeStreams.put("assistant", active);
        registry.claim("assistant", 7L, ".");

        Assertions.assertThatThrownBy(() -> runtime.stopActiveStream("assistant", 8L))
                .isInstanceOf(IllegalStateException.class);
        assertThat(registry.hasActiveStreamForSession(7L)).isTrue();
        assertThat(runtime.activeStreams).containsEntry("assistant", active);
    }

    @Test
    void persistedToolSnapshotIsSentBeforeTerminalEvent() throws Exception {
        AppStateService state = mock(AppStateService.class);
        ChatToolCallHtmlService html = mock(ChatToolCallHtmlService.class);
        var runtime = new ChatStreamRuntime(mock(CodingAgentHarness.class), new AgentProperties(), state,
                mock(AgentDefinitionService.class), null, null, mock(ContextCompactionService.class), null, null,
                mock(CommandStreamService.class), new ActiveStreamRegistryService(), new ChatPresentationService(),
                html, null);
        when(state.findAssistantMessageSessionId("assistant")).thenReturn(OptionalLong.of(7L));
        var message = new ChatMessageView("ASSISTANT", "finished", 1L, false, "assistant", 10L, List.of(), null);
        when(state.loadAssistantMessageDetails(7L, "assistant")).thenReturn(message);
        when(html.hostSnapshot(7L, "assistant")).thenReturn(List.of());
        var emitter = new RecordingEmitter();

        runtime.sendPersistedTerminalState("assistant", emitter);

        assertThat(emitter.events()).containsExactly(new RecordedEvent("tool_call_html", List.of()),
                new RecordedEvent("done", Map.of("text", "finished", "completedTs", 10)));
        assertThat(emitter.completed()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void persistedReplayReportsUnavailableMessageAsNoJobAndCompletes(boolean pending) throws Exception {
        AppStateService state = mock(AppStateService.class);
        var runtime = runtime(state, new ActiveStreamRegistryService());
        when(state.findAssistantMessageSessionId("assistant"))
                .thenReturn(pending ? OptionalLong.of(7L) : OptionalLong.empty());
        if (pending) {
            when(state.loadAssistantMessageDetails(7L, "assistant"))
                    .thenReturn(new ChatMessageView("ASSISTANT", null, 1L, true, "assistant", null, List.of(), null));
        }
        var emitter = new RecordingEmitter();

        runtime.sendPersistedTerminalState("assistant", emitter);

        assertThat(emitter.events()).containsExactly(new RecordedEvent("error", Map.of("message", "no_job")));
        assertThat(emitter.completed()).isTrue();
    }

    @Test
    void persistedReplayAllowsNullCompletionTimestamp() throws Exception {
        AppStateService state = mock(AppStateService.class);
        var runtime = runtime(state, new ActiveStreamRegistryService());
        when(state.findAssistantMessageSessionId("assistant")).thenReturn(OptionalLong.of(7L));
        when(state.loadAssistantMessageDetails(7L, "assistant")).thenReturn(
                new ChatMessageView("ASSISTANT", "finished", 1L, false, "assistant", null, List.of(), null));
        var emitter = new RecordingEmitter();

        runtime.sendPersistedTerminalState("assistant", emitter);

        assertThat(emitter.events()).containsExactly(new RecordedEvent("done", Map.of("text", "finished")));
        assertThat(emitter.completed()).isTrue();
    }

    @Test
    void persistedReplayForInactiveWorkspacePreservesSelection() throws Exception {
        AppStateService state = TestAppStateSupport.appStateService();
        long ownerSessionId = state.ensureChatSession(".").id();
        state.appendPendingAssistantMessage(ownerSessionId, "assistant", "Thinking", null);
        var completed = state.completeAssistantMessage(ownerSessionId, "assistant", "background answer", List.of());
        state.addOrReopenProject("Other project", "../other-project");
        var selection = state.loadViewData();
        assertThat(selection.activeSession().id()).isNotEqualTo(ownerSessionId);
        var runtime = runtime(state, new ActiveStreamRegistryService());
        var emitter = new RecordingEmitter();

        runtime.sendPersistedTerminalState("assistant", emitter);

        assertThat(emitter.events()).containsExactly(
                new RecordedEvent("done", Map.of("text", "background answer", "completedTs", completed.completedTs())));
        assertThat(emitter.completed()).isTrue();
        assertThat(state.loadViewData().activeSession()).isEqualTo(selection.activeSession());
        assertThat(state.loadViewData().activeWorkspace()).isEqualTo(selection.activeWorkspace());
        assertThat(state.loadViewData().activeProject()).isEqualTo(selection.activeProject());
    }

    @Test
    void persistedReplayCompletesEmitterWhenSnapshotThrows() throws Exception {
        AppStateService state = mock(AppStateService.class);
        ChatToolCallHtmlService html = mock(ChatToolCallHtmlService.class);
        var runtime = new ChatStreamRuntime(mock(CodingAgentHarness.class), new AgentProperties(), state,
                mock(AgentDefinitionService.class), null, null, mock(ContextCompactionService.class), null, null,
                mock(CommandStreamService.class), new ActiveStreamRegistryService(), new ChatPresentationService(),
                html, null);
        when(state.findAssistantMessageSessionId("assistant")).thenReturn(OptionalLong.of(7L));
        when(state.loadAssistantMessageDetails(7L, "assistant"))
                .thenReturn(new ChatMessageView("ASSISTANT", "finished", 1L, false, "assistant", 10L, List.of(), null));
        when(html.hostSnapshot(7L, "assistant")).thenThrow(new IllegalStateException("snapshot unavailable"));
        var emitter = new RecordingEmitter();

        runtime.sendPersistedTerminalState("assistant", emitter);

        assertThat(emitter.events())
                .containsExactly(new RecordedEvent("done", Map.of("text", "finished", "completedTs", 10)));
        assertThat(emitter.completed()).isTrue();
    }

    @Test
    void stopPersistenceFailureClearsRegistryAndCompletesObserver() {
        AppStateService state = mock(AppStateService.class);
        var registry = new ActiveStreamRegistryService();
        var runtime = runtime(state, registry);
        var active = active(new CancellationToken());
        active.accumulatedText().get().append("partial");
        var emitter = new RecordingEmitter();
        active.emitters().add(emitter);
        doThrow(new IllegalStateException("database unavailable")).when(state).stopAssistantMessage(7L, "assistant",
                "partial");
        runtime.activeStreams.put("assistant", active);
        registry.claim("assistant", 7L, ".");

        runtime.stopActiveStream("assistant", 7L);
        assertThat(active.completed().get()).isTrue();
        assertThat(active.finished().get()).isTrue();
        assertThat(runtime.activeStreams).doesNotContainKey("assistant");
        assertThat(registry.hasActiveStreamForSession(7L)).isFalse();
        assertThat(active.emitters()).isEmpty();
        assertThat(emitter.events())
                .containsExactly(new RecordedEvent("error", Map.of("message", "database unavailable")));
        assertThat(emitter.completed()).isTrue();
    }

    private record RecordedEvent(String name, Object data) {
        private RecordedEvent {
            try {
                var mapper = new ObjectMapper();
                data = mapper.readValue(mapper.writeValueAsString(data), Object.class);
            } catch (IOException e) {
                throw new IllegalArgumentException("Invalid SSE payload", e);
            }
        }
    }

    private static final class RecordingEmitter extends SseEmitter {
        private static final ObjectMapper MAPPER = new ObjectMapper();
        private final List<RecordedEvent> events = new ArrayList<>();
        private boolean completed;

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            String wire = builder.build().stream().map(part -> part.getData().toString()).collect(Collectors.joining());
            events.add(decode(wire));
        }

        static RecordedEvent decode(String wire) throws IOException {
            int nameStart = wire.indexOf("event:") + "event:".length();
            int nameEnd = wire.indexOf('\n', nameStart);
            int dataStart = wire.indexOf("data:", nameEnd) + "data:".length();
            return new RecordedEvent(wire.substring(nameStart, nameEnd),
                    MAPPER.readValue(wire.substring(dataStart).trim(), Object.class));
        }

        @Override
        public synchronized void complete() {
            completed = true;
        }

        synchronized List<RecordedEvent> events() {
            return List.copyOf(events);
        }

        synchronized boolean completed() {
            return completed;
        }
    }

    private static ChatStreamRuntime.ActiveStream active(CancellationToken token) {
        return active(token, 7L);
    }

    private static ChatStreamRuntime.ActiveStream active(CancellationToken token, long sessionId) {
        return ChatStreamRuntime.ActiveStream
                .create(new ChatStreamRuntime.PendingStream(sessionId, ".", mock(AgentTurnRequest.class)), token);
    }

    private static ChatStreamRuntime runtime(AppStateService state, ActiveStreamRegistryService registry) {
        return runtime(mock(CodingAgentHarness.class), state, registry);
    }

    private static ChatStreamRuntime runtime(CodingAgentHarness harness, AppStateService state,
            ActiveStreamRegistryService registry) {
        return new ChatStreamRuntime(harness, new AgentProperties(), state, mock(AgentDefinitionService.class), null,
                null, mock(ContextCompactionService.class), null, null, mock(CommandStreamService.class), registry,
                new ChatPresentationService(), null, null);
    }
}

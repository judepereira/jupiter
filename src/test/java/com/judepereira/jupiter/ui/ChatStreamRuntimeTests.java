package com.judepereira.jupiter.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import com.judepereira.jupiter.persistence.Persistence;
import com.judepereira.jupiter.persistence.Persistence.AppStateView;
import com.judepereira.jupiter.persistence.Persistence.ChatMessageView;
import com.judepereira.jupiter.persistence.Persistence.SessionView;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
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
        var emitter = mock(SseEmitter.class);
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
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        var result = mock(AgentTurnResult.class);
        when(result.getFinalText()).thenReturn("finished");
        listenerRef.get().onComplete(result);

        Mockito.verify(emitter).send(ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
        Mockito.verify(emitter).complete();
        release.countDown();
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
        var emitter = mock(SseEmitter.class);
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
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        listenerRef.get().onError(new IllegalStateException("failure"));

        Mockito.verify(emitter).send(ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
        Mockito.verify(emitter).complete();
        release.countDown();
    }

    @Test
    void attachmentWaitsForDeltaPersistenceAndReceivesNoDuplicateDelta() throws Exception {
        AppStateService state = mock(AppStateService.class);
        CodingAgentHarness harness = mock(CodingAgentHarness.class);
        var runtime = runtime(harness, state, new ActiveStreamRegistryService());
        var active = active(new CancellationToken());
        var listenerRef = new AtomicReference<AgentStreamListener>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(state).updateStreamingAssistantText(7L, "assistant", "delta");
        doAnswer(invocation -> {
            listenerRef.set(invocation.getArgument(1));
            entered.countDown();
            release.await();
            return null;
        }).when(harness).runTurnStreaming(Mockito.any(), Mockito.any());
        runtime.activeStreams.put("assistant", active);
        runtime.startActiveStream("assistant", active);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        var deltaEntered = new CountDownLatch(1);
        doAnswer(invocation -> {
            deltaEntered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(state).updateStreamingAssistantText(7L, "assistant", "delta");
        var deltaThread = Thread.startVirtualThread(() -> listenerRef.get().onTextDelta("delta"));
        assertThat(deltaEntered.await(5, TimeUnit.SECONDS)).isTrue();
        var attached = new CountDownLatch(1);
        var attachThread = Thread.startVirtualThread(() -> {
            runtime.streamChat("assistant");
            attached.countDown();
        });

        assertThat(attached.await(100, TimeUnit.MILLISECONDS)).isFalse();
        release.countDown();
        deltaThread.join(5000);
        attachThread.join(5000);
        assertThat(attached.await(5, TimeUnit.SECONDS)).isTrue();
        Mockito.verify(state).updateStreamingAssistantText(7L, "assistant", "delta");
        release.countDown();
    }

    @Test
    void stoppedStreamIgnoresLateCompletionAndKeepsStoppedPendingMessage() {
        AppStateService state = mock(AppStateService.class);
        var registry = new ActiveStreamRegistryService();
        var runtime = runtime(state, registry);
        var token = new CancellationToken();
        var active = active(token);
        active.accumulatedText().get().append("partial");
        when(state.stopAssistantMessage(7L, "assistant", "partial")).thenReturn(new ChatMessageView("ASSISTANT",
                "partial\n\nAction Interrupted", 1L, false, "assistant", 10L, List.of(), null));
        runtime.activeStreams.put("assistant", active);
        registry.claim("assistant", 7L, ".");

        runtime.stopActiveStream("assistant", 7L);
        assertThat(active.completed().get()).isTrue();
        assertThat(active.finished().get()).isTrue();
        assertThat(token.isCancelled()).isTrue();
        assertThat(runtime.activeStreams).doesNotContainKey("assistant");
        assertThat(registry.hasActiveStreamForSession(7L)).isFalse();
        assertThat(active.emitters()).isEmpty();
    }

    @Test
    void actualLateCompletionAfterStopDoesNotOverwriteStoppedMessage() throws Exception {
        AppStateService state = mock(AppStateService.class);
        CodingAgentHarness harness = mock(CodingAgentHarness.class);
        var registry = new ActiveStreamRegistryService();
        var runtime = runtime(harness, state, registry);
        var token = new CancellationToken();
        var active = active(token);
        var listenerRef = new AtomicReference<AgentStreamListener>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            listenerRef.set(invocation.getArgument(1));
            entered.countDown();
            release.await();
            return null;
        }).when(harness).runTurnStreaming(Mockito.any(), Mockito.any());
        when(state.stopAssistantMessage(7L, "assistant", "partial")).thenReturn(new ChatMessageView("ASSISTANT",
                "partial\\n\\nAction Interrupted", 1L, false, "assistant", 10L, List.of(), null));
        active.accumulatedText().get().append("partial");
        runtime.activeStreams.put("assistant", active);
        registry.claim("assistant", 7L, ".");
        runtime.startActiveStream("assistant", active);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        runtime.stopActiveStream("assistant", 7L);
        listenerRef.get().onComplete(mock(AgentTurnResult.class));
        release.countDown();

        Mockito.verify(state, Mockito.never()).completeAssistantMessage(Mockito.anyLong(), Mockito.anyString(),
                Mockito.anyString(), Mockito.anyList());
        assertThat(token.isCancelled()).isTrue();
        Mockito.verify(state).stopAssistantMessage(7L, "assistant", "partial");
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
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        listenerRef.get().onTextDelta("delta");

        assertThat(token.isCancelled()).isTrue();
        assertThat(registry.hasActiveStreamForSession(7L)).isFalse();
        assertThat(runtime.activeStreams).doesNotContainKey("assistant");
        release.countDown();
        Mockito.verify(harness).runTurnStreaming(Mockito.any(), Mockito.argThat(listener -> {
            listener.onTextDelta("must not persist");
            return true;
        }));
        Mockito.verify(state, Mockito.never()).updateStreamingAssistantText(7L, "assistant", "must not persist");
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
        var session = new SessionView(7L, "session", false, Persistence.RailStatus.NONE);
        var view = new AppStateView(List.of(), null, List.of(), null, List.of(session), null, null, false);
        when(state.loadViewData()).thenReturn(view);
        when(state.findAssistantMessageSessionId("assistant")).thenReturn(OptionalLong.of(7L));
        var message = new ChatMessageView("ASSISTANT", "finished", 1L, false, "assistant", 10L, List.of(), null);
        when(state.loadAssistantMessageDetails(7L, "assistant")).thenReturn(message);
        when(html.hostSnapshot(7L, "assistant")).thenReturn(List.of());
        var emitter = mock(SseEmitter.class);
        var calls = Mockito.inOrder(html, emitter);

        runtime.sendPersistedTerminalState("assistant", emitter);

        calls.verify(html).hostSnapshot(7L, "assistant");
        calls.verify(emitter, Mockito.times(2)).send(ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
        calls.verify(emitter).complete();
    }

    @Test
    void persistedReplayReportsPendingMessageAsNoJobAndCompletes() throws Exception {
        AppStateService state = mock(AppStateService.class);
        var runtime = runtime(state, new ActiveStreamRegistryService());
        when(state.findAssistantMessageSessionId("assistant")).thenReturn(OptionalLong.of(7L));
        when(state.loadAssistantMessageDetails(7L, "assistant"))
                .thenReturn(new ChatMessageView("ASSISTANT", null, 1L, true, "assistant", null, List.of(), null));
        var emitter = mock(SseEmitter.class);

        runtime.sendPersistedTerminalState("assistant", emitter);

        Mockito.verify(emitter).send(ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
        Mockito.verify(emitter).complete();
    }

    @Test
    void persistedReplayReportsMissingAssistantAsNoJobAndCompletes() throws Exception {
        AppStateService state = mock(AppStateService.class);
        var runtime = runtime(state, new ActiveStreamRegistryService());
        when(state.findAssistantMessageSessionId("assistant")).thenReturn(OptionalLong.empty());
        var emitter = mock(SseEmitter.class);

        runtime.sendPersistedTerminalState("assistant", emitter);

        Mockito.verify(emitter).send(ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
        Mockito.verify(emitter).complete();
        Mockito.verify(state, Mockito.never()).loadAssistantMessageDetails(Mockito.anyLong(), Mockito.anyString());
    }

    @Test
    void persistedReplayAllowsNullCompletionTimestamp() throws Exception {
        AppStateService state = mock(AppStateService.class);
        var runtime = runtime(state, new ActiveStreamRegistryService());
        when(state.findAssistantMessageSessionId("assistant")).thenReturn(OptionalLong.of(7L));
        when(state.loadAssistantMessageDetails(7L, "assistant")).thenReturn(
                new ChatMessageView("ASSISTANT", "finished", 1L, false, "assistant", null, List.of(), null));
        var emitter = mock(SseEmitter.class);

        runtime.sendPersistedTerminalState("assistant", emitter);

        Mockito.verify(emitter).send(ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
        Mockito.verify(emitter).complete();
    }

    @Test
    void persistedReplayForInactiveWorkspaceDoesNotLoadWorkspaceView() throws Exception {
        AppStateService state = mock(AppStateService.class);
        var runtime = runtime(state, new ActiveStreamRegistryService());
        when(state.findAssistantMessageSessionId("assistant")).thenReturn(OptionalLong.of(7L));
        when(state.loadAssistantMessageDetails(7L, "assistant"))
                .thenReturn(new ChatMessageView("ASSISTANT", "finished", 1L, false, "assistant", 10L, List.of(), null));

        runtime.sendPersistedTerminalState("assistant", mock(SseEmitter.class));

        Mockito.verify(state, Mockito.never()).loadViewData();
        Mockito.verify(state).findAssistantMessageSessionId("assistant");
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
        var emitter = mock(SseEmitter.class);

        runtime.sendPersistedTerminalState("assistant", emitter);

        Mockito.verify(emitter).send(ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
        Mockito.verify(emitter).complete();
    }

    @Test
    void stopPersistenceFailureClearsRegistryAndCompletesObserver() {
        AppStateService state = mock(AppStateService.class);
        var registry = new ActiveStreamRegistryService();
        var runtime = runtime(state, registry);
        var active = active(new CancellationToken());
        active.accumulatedText().get().append("partial");
        var emitter = mock(SseEmitter.class);
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
        Mockito.verify(emitter).complete();
    }

    private static ChatStreamRuntime.ActiveStream active(CancellationToken token) {
        return ChatStreamRuntime.ActiveStream
                .create(new ChatStreamRuntime.PendingStream(7L, ".", mock(AgentTurnRequest.class)), token);
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

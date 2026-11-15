package com.judepereira.jupiter.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.judepereira.jupiter.agent.catalog.AgentDefinitionService;
import com.judepereira.jupiter.agent.config.AgentProperties;
import com.judepereira.jupiter.agent.harness.AgentTurnRequest;
import com.judepereira.jupiter.agent.harness.CancellationToken;
import com.judepereira.jupiter.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter.command.CommandStreamService;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.ContextCompactionService;
import com.judepereira.jupiter.persistence.Persistence;
import com.judepereira.jupiter.persistence.Persistence.AppStateView;
import com.judepereira.jupiter.persistence.Persistence.ChatMessageView;
import com.judepereira.jupiter.persistence.Persistence.SessionView;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class ChatStreamRuntimeTests {
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
        var message = new ChatMessageView("ASSISTANT", "finished", 1L, false, "assistant", 10L, List.of(), null);
        when(state.loadAssistantMessageDetails(7L, "assistant")).thenReturn(message);
        when(html.hostSnapshot(7L, "assistant")).thenReturn(List.of());
        var emitter = mock(SseEmitter.class);
        var calls = Mockito.inOrder(html, emitter);

        runtime.sendPersistedTerminalState("assistant", emitter);

        calls.verify(html).hostSnapshot(7L, "assistant");
        calls.verify(emitter, Mockito.times(2)).send(ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
        calls.verify(emitter, Mockito.times(2)).complete();
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
        return new ChatStreamRuntime(mock(CodingAgentHarness.class), new AgentProperties(), state,
                mock(AgentDefinitionService.class), null, null, mock(ContextCompactionService.class), null, null,
                mock(CommandStreamService.class), registry, new ChatPresentationService(), null, null);
    }
}

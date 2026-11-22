package com.judepereira.jupiter.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.judepereira.jupiter.agent.tools.impl.RunCommandTool;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.ui.ActiveStreamRegistryService;
import com.judepereira.jupiter.ui.ChatToolCallHtmlService;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CommandStreamServiceTest {
    private CommandCatalogService.CommandDefinition command() {
        return new CommandCatalogService.CommandDefinition("command", "Command", null,
                CommandCatalogService.CommandKind.SCRIPT, "echo hi", null, null);
    }

    @Test
    void busySessionIsRejectedBeforePersistingAnyMessages() {
        AppStateService appState = mock(AppStateService.class);
        ActiveStreamRegistryService registry = new ActiveStreamRegistryService();
        CommandStreamService service = new CommandStreamService(mock(CommandCatalogService.class), appState,
                new RunCommandTool(), registry, mock(ChatToolCallHtmlService.class));
        var command = command();
        registry.register("existing", 7L, ".");

        assertThatThrownBy(() -> service.queue(7L, "user", "next", command, ".", null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SESSION_BUSY");

        verify(appState, never()).appendUserMessageAndPendingAssistant(7L, "user", "next", "/command", null);
    }

    @Test
    void appendFailureDoesNotFailUnpersistedAssistantAndReleasesSessionForLaterCommand() {
        AppStateService appState = mock(AppStateService.class);
        ActiveStreamRegistryService registry = new ActiveStreamRegistryService();
        CommandStreamService service = new CommandStreamService(mock(CommandCatalogService.class), appState,
                new RunCommandTool(), registry, mock(ChatToolCallHtmlService.class));
        when(appState.appendUserMessageAndPendingAssistant(7L, "user", "first", "/command", null))
                .thenThrow(new IllegalStateException("database unavailable"));
        when(appState.appendUserMessageAndPendingAssistant(7L, "user", "second", "/command", null)).thenReturn(null);

        assertThatThrownBy(() -> service.queue(7L, "user", "first", command(), ".", null))
                .hasMessage("database unavailable");
        service.queue(7L, "user", "second", command(), ".", null);

        verify(appState, never()).failAssistantMessage(7L, "first", "Command execution failed: database unavailable");
        assertThat(service.pendingCommand("first")).isNull();
        assertThat(registry.isClaimedBy("first", 7L)).isFalse();
        assertThat(registry.isClaimedBy("second", 7L)).isTrue();
    }

    @Test
    void publishFailureFailsPersistedAssistantAndClearsActiveState() {
        AppStateService appState = mock(AppStateService.class);
        ActiveStreamRegistryService registry = new ActiveStreamRegistryService();
        CommandStreamService service = new CommandStreamService(mock(CommandCatalogService.class), appState,
                new RunCommandTool(), registry, mock(ChatToolCallHtmlService.class));
        when(appState.appendUserMessageAndPendingAssistant(7L, "user", "assistant", "/command", null)).thenReturn(null);
        RuntimeException failure = new RuntimeException("rail unavailable");
        doThrow(failure).when(appState).publishWorkspaceRailRefresh();

        assertThatThrownBy(() -> service.queue(7L, "user", "assistant", command(), ".", null)).isSameAs(failure);

        verify(appState).failAssistantMessage(7L, "assistant", "Command execution failed: rail unavailable");
        assertThat(service.pendingCommand("assistant")).isNull();
        assertThat(registry.isClaimedBy("assistant", 7L)).isFalse();
    }

    @Test
    void publishFailurePreservesCleanupFailureAsSuppressed() {
        AppStateService appState = mock(AppStateService.class);
        ActiveStreamRegistryService registry = new ActiveStreamRegistryService();
        CommandStreamService service = new CommandStreamService(mock(CommandCatalogService.class), appState,
                new RunCommandTool(), registry, mock(ChatToolCallHtmlService.class));
        when(appState.appendUserMessageAndPendingAssistant(7L, "user", "assistant", "/command", null)).thenReturn(null);
        RuntimeException failure = new RuntimeException("rail unavailable");
        RuntimeException cleanupFailure = new RuntimeException("cleanup unavailable");
        doThrow(failure).when(appState).publishWorkspaceRailRefresh();
        when(appState.failAssistantMessage(7L, "assistant", "Command execution failed: rail unavailable"))
                .thenThrow(cleanupFailure);

        assertThatThrownBy(() -> service.queue(7L, "user", "assistant", command(), ".", null)).isSameAs(failure)
                .satisfies(thrown -> assertThat(thrown.getSuppressed()).containsExactly(cleanupFailure));

        assertThat(service.pendingCommand("assistant")).isNull();
        assertThat(registry.isClaimedBy("assistant", 7L)).isFalse();
    }

    @Test
    void queueSnapshotsAllowlist() {
        AppStateService appState = mock(AppStateService.class);
        Set<String> allowlist = new HashSet<>(Set.of("BEFORE"));
        when(appState.loadSessionProjectCommandEnvironmentAllowlist(7L)).thenReturn(allowlist);
        CommandStreamService service = new CommandStreamService(mock(CommandCatalogService.class), appState,
                new RunCommandTool(), mock(ActiveStreamRegistryService.class), mock(ChatToolCallHtmlService.class));

        when(appState.appendUserMessageAndPendingAssistant(7L, "user", "assistant", "/command", null)).thenReturn(null);
        service.queue(7L, "user", "assistant", new CommandCatalogService.CommandDefinition("command", "Command", null,
                CommandCatalogService.CommandKind.SCRIPT, "echo hi", null, null), ".", null);
        allowlist.clear();
        allowlist.add("AFTER");

        assertThat(service.pendingCommand("assistant").commandEnvironmentAllowlist()).containsExactly("BEFORE");
    }
}

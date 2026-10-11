package com.judepereira.jupiter.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

class CommandStreamServiceTest {
    @Test
    void queueSnapshotsAllowlist() {
        AppStateService appState = mock(AppStateService.class);
        Set<String> allowlist = new HashSet<>(Set.of("BEFORE"));
        when(appState.loadSessionProjectCommandEnvironmentAllowlist(7L)).thenReturn(allowlist);
        CommandStreamService service = new CommandStreamService(mock(CommandCatalogService.class), appState,
                new RunCommandTool(), mock(ActiveStreamRegistryService.class), mock(ChatToolCallHtmlService.class));

        service.queue(7L, "assistant", new CommandCatalogService.CommandDefinition("command", "Command", null,
                CommandCatalogService.CommandKind.SCRIPT, "echo hi", null, null), ".", null);
        allowlist.clear();
        allowlist.add("AFTER");

        assertThat(service.pendingCommand("assistant").commandEnvironmentAllowlist()).containsExactly("BEFORE");
    }

    @Test
    void fatalToolErrorCleansUpStreamWithoutPersistingOrdinaryFailure() throws Exception {
        AppStateService appState = mock(AppStateService.class);
        ActiveStreamRegistryService registry = mock(ActiveStreamRegistryService.class);
        RunCommandTool runCommandTool = mock(RunCommandTool.class);
        AssertionError fatalError = new AssertionError("fatal tool failure");
        CountDownLatch unregistered = new CountDownLatch(1);
        doAnswer(invocation -> {
            unregistered.countDown();
            return null;
        }).when(registry).unregister("assistant");
        doAnswer(invocation -> {
            throw fatalError;
        }).when(runCommandTool).execute(ArgumentMatchers.anyMap(), ArgumentMatchers.any());
        CommandStreamService service = new CommandStreamService(mock(CommandCatalogService.class), appState,
                runCommandTool, registry, mock(ChatToolCallHtmlService.class));
        service.queue(7L, "assistant", new CommandCatalogService.CommandDefinition("command", "Command", null,
                CommandCatalogService.CommandKind.SCRIPT, "echo hi", null, null), ".", null);

        assertThat(service.tryConnect("assistant")).isNotNull();
        assertThat(unregistered.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(service.tryConnect("assistant")).isNull();
        verify(registry).unregister("assistant");
        verify(appState, never()).failAssistantMessage(ArgumentMatchers.anyLong(), ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString());
        assertThat(fatalError.getSuppressed()).isEmpty();
    }
}

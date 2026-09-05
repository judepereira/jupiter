package com.judepereira.jupiter.command;

import com.judepereira.jupiter.agent.tools.impl.RunCommandTool;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.ui.ActiveStreamRegistryService;
import com.judepereira.jupiter.ui.ChatToolCallHtmlService;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommandStreamServiceTest {
    @Test
    void queueSnapshotsAllowlist() {
        AppStateService appState = mock(AppStateService.class);
        Set<String> allowlist = new java.util.HashSet<>(Set.of("BEFORE"));
        when(appState.loadSessionProjectCommandEnvironmentAllowlist(7L)).thenReturn(allowlist);
        CommandStreamService service = new CommandStreamService(
                mock(CommandCatalogService.class), appState, new RunCommandTool(),
                mock(ActiveStreamRegistryService.class), mock(ChatToolCallHtmlService.class));

        service.queue(7L, "assistant", "command", ".", null);
        allowlist.clear();
        allowlist.add("AFTER");

        assertThat(service.pendingCommand("assistant").commandEnvironmentAllowlist())
                .containsExactly("BEFORE");
    }
}

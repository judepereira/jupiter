package com.judepereira.jupiter.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.judepereira.jupiter.agent.tools.impl.RunCommandTool;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.ui.ActiveStreamRegistryService;
import com.judepereira.jupiter.ui.ChatToolCallHtmlService;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

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
}

package com.judepereira.jupiter.agent.mcp;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.judepereira.jupiter.agent.catalog.AgentDefinitionService;
import com.judepereira.jupiter.agent.catalog.AgentPreferenceResolver;
import com.judepereira.jupiter.git.GitCommandRunner;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.ContextCompactionService;
import com.judepereira.jupiter.ui.ChatStreamRuntime;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

class InboundMcpToolServiceTests {
    private final AppStateService appState = mock(AppStateService.class);
    private final InboundMcpToolService service = new InboundMcpToolService(appState,
            mock(AgentDefinitionService.class), mock(AgentPreferenceResolver.class),
            mock(ContextCompactionService.class), mock(ChatStreamRuntime.class), mock(GitCommandRunner.class));
    private final InboundMcpTokenService.Credential credential = new InboundMcpTokenService.Credential("id", "name",
            false, Set.of(7L), Set.of("post_message", "create_workspace"));

    @Test
    void invalidProjectTargetDoesNotCreateWorkspace() {
        assertThatThrownBy(() -> service.call("post_message",
                Map.of("projectId", 8L, "branchName", "feature/x", "createBranch", true, "message", "hello"),
                credential)).isInstanceOf(InboundMcpToolService.ResourceUnavailableException.class)
                .hasMessage("Resource unavailable");
        verify(appState, never()).createWorkspace(ArgumentMatchers.anyLong(), ArgumentMatchers.anyString(),
                ArgumentMatchers.anyBoolean());
    }

    @Test
    void unauthorizedWorkspaceTargetIsUnavailableWithoutLoadingWorkspaceDetails() {
        assertThatThrownBy(
                () -> service.call("post_message", Map.of("workspaceId", 99L, "message", "hello"), credential))
                .isInstanceOf(InboundMcpToolService.ResourceUnavailableException.class)
                .hasMessage("Resource unavailable");
        verify(appState, never()).loadViewData();
    }
}

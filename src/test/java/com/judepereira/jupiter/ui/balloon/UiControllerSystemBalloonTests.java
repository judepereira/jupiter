package com.judepereira.jupiter.ui.balloon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.catalog.AgentDefinitionService;
import com.judepereira.jupiter.agent.catalog.ModelCatalogService;
import com.judepereira.jupiter.agent.config.AgentProperties;
import com.judepereira.jupiter.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter.agent.mcp.McpProjectMcpServerRuntimeManager;
import com.judepereira.jupiter.agent.mcp.McpRuntimeEvents;
import com.judepereira.jupiter.command.CommandStreamService;
import com.judepereira.jupiter.openai.oauth.OpenAiOAuthService;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.Persistence.AppStateView;
import com.judepereira.jupiter.persistence.Persistence.McpServerView;
import com.judepereira.jupiter.persistence.Persistence.ProjectView;
import com.judepereira.jupiter.persistence.TestAppStateSupport;
import com.judepereira.jupiter.testsupport.ModelCatalogTestSupport;
import com.judepereira.jupiter.terminal.TerminalManager;
import com.judepereira.jupiter.terminal.TerminalStateService;
import com.judepereira.jupiter.ui.UiController;
import com.judepereira.jupiter.ui.rail.WorkspaceRailRefreshService;
import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UiControllerSystemBalloonTests {

    @Test
    void firstShellLoadPublishesFailedMcpBalloonOncePerShellId() {
        AppStateService appStateService = mock(AppStateService.class);
        McpProjectMcpServerRuntimeManager runtimeManager = mock(McpProjectMcpServerRuntimeManager.class);
        SystemBalloonService balloonService = mock(SystemBalloonService.class);
        UiController controller = controller(appStateService, runtimeManager, balloonService);

        when(appStateService.loadViewData()).thenReturn(new AppStateView(
                List.of(new ProjectView(1L, "Alpha", "/tmp/alpha", null, List.of(), null)),
                new ProjectView(1L, "Alpha", "/tmp/alpha", null, List.of(), null),
                List.of(), null, List.of(), null, null, false));
        when(appStateService.loadEnabledMcpServersForProject(1L)).thenReturn(List.of(
                new McpServerView(10L, "GitHub MCP", "http://localhost:3000", true, List.of(), List.of(1L))
        ));
        when(runtimeManager.connectionStatuses(1L)).thenReturn(Map.of(10L, McpRuntimeEvents.ConnectionStatus.FAILED));
        doNothing().when(balloonService).publishWarning(any(), anyString(), anyString());
        when(balloonService.markShellInitialized("shell-1")).thenReturn(true, false);

        controller.systemBalloonStream("shell-1");
        controller.systemBalloonStream("shell-1");

        verify(balloonService, times(1)).publishWarning(any(), eq("MCP server failed: Alpha / GitHub MCP"), eq("An MCP server is unavailable for the active project."));
    }

    private static UiController controller(AppStateService appStateService,
                                            McpProjectMcpServerRuntimeManager runtimeManager,
                                            SystemBalloonService balloonService) {
        AgentProperties properties = new AgentProperties();
        properties.setWorkspaceRoot(Path.of(".").toAbsolutePath().normalize().toString());
        TerminalManager terminalManager = mock(TerminalManager.class);
        OpenAiOAuthService openAiOAuthService = mock(OpenAiOAuthService.class);
        ModelCatalogService modelCatalogService = ModelCatalogTestSupport.modelCatalogService();
        return new UiController(mock(CodingAgentHarness.class), properties, appStateService, new AgentDefinitionService(new ObjectMapper()), modelCatalogService, balloonService, new WorkspaceRailRefreshService(() -> new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(0L), (emitter, eventName, data) -> emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().name(eventName).data(data))), appStateService.activeStreamRegistryService(), terminalManager, new TerminalStateService(), openAiOAuthService, TestAppStateSupport.contextCompactionService(appStateService), null, mock(CommandStreamService.class), runtimeManager, new com.judepereira.jupiter.ui.ChatPresentationService(), null, null, new com.judepereira.jupiter.config.HttpAuthProperties(), mock(com.judepereira.jupiter.git.GitAutoUpdateService.class), mock(com.judepereira.jupiter.git.ManualGitPullCoordinator.class), "test");
    }
}

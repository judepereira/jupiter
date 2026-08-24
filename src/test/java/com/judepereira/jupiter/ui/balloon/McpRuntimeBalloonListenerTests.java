package com.judepereira.jupiter.ui.balloon;

import com.judepereira.jupiter.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter.agent.llm.dto.ToolSchema;
import com.judepereira.jupiter.agent.mcp.McpProjectMcpServerRuntimeManager;
import com.judepereira.jupiter.agent.mcp.McpRuntimeEvents;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.Persistence.ProjectView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class McpRuntimeBalloonListenerTests {

    @Test
    void toolsChange_publishesBalloon_afterFirstSeenChange() {
        AppStateService appStateService = mock(AppStateService.class);
        McpProjectMcpServerRuntimeManager runtimeManager = mock(McpProjectMcpServerRuntimeManager.class);
        SystemBalloonService balloonService = spy(new SystemBalloonService(new com.fasterxml.jackson.databind.ObjectMapper()));
        McpRuntimeBalloonListener listener = new McpRuntimeBalloonListener(appStateService, runtimeManager, balloonService);

        when(appStateService.loadViewData()).thenReturn(new com.judepereira.jupiter.persistence.Persistence.AppStateView(
                List.of(new ProjectView(1L, "Alpha", "/tmp/a", null, List.of())), null, List.of(), null, List.of(), null, null));
        when(runtimeManager.snapshot(1L)).thenReturn(new com.judepereira.jupiter.agent.mcp.McpProjectToolSnapshot(1L,
                List.of(new ToolDefinition("mcp__alpha__one", "desc", ToolSchema.object())), Map.of()));

        listener.onProjectMcpToolsChanged(new McpRuntimeEvents.ProjectMcpToolsChanged(1L));
        listener.onProjectMcpToolsChanged(new McpRuntimeEvents.ProjectMcpToolsChanged(1L));

        assertThat(balloonService.publishedBalloons()).isEmpty();

        balloonService.connect();
        when(runtimeManager.snapshot(1L)).thenReturn(new com.judepereira.jupiter.agent.mcp.McpProjectToolSnapshot(1L,
                List.of(new ToolDefinition("mcp__alpha__two", "desc", ToolSchema.object())), Map.of()));
        listener.onProjectMcpToolsChanged(new McpRuntimeEvents.ProjectMcpToolsChanged(1L));

        assertThat(balloonService.publishedBalloons()).hasSize(1);
        assertThat(balloonService.publishedBalloons().getFirst().type()).isEqualTo(SystemBalloon.Type.SUCCESS);
    }

    @Test
    void failedThenReady_publishesWarningAndRecoveryBalloons() {
        AppStateService appStateService = mock(AppStateService.class);
        McpProjectMcpServerRuntimeManager runtimeManager = mock(McpProjectMcpServerRuntimeManager.class);
        SystemBalloonService balloonService = new SystemBalloonService(new com.fasterxml.jackson.databind.ObjectMapper());
        McpRuntimeBalloonListener listener = new McpRuntimeBalloonListener(appStateService, runtimeManager, balloonService);

        when(appStateService.loadViewData()).thenReturn(new com.judepereira.jupiter.persistence.Persistence.AppStateView(
                List.of(new ProjectView(1L, "Alpha", "/tmp/a", null, List.of())), null, List.of(), null, List.of(), null, null));

        listener.onProjectMcpServerStatusChanged(new McpRuntimeEvents.ProjectMcpServerStatusChanged(1L, 10L, "server", McpRuntimeEvents.ConnectionStatus.CONNECTING, "connecting"));
        listener.onProjectMcpServerStatusChanged(new McpRuntimeEvents.ProjectMcpServerStatusChanged(1L, 10L, "server", McpRuntimeEvents.ConnectionStatus.FAILED, "boom"));
        listener.onProjectMcpServerStatusChanged(new McpRuntimeEvents.ProjectMcpServerStatusChanged(1L, 10L, "server", McpRuntimeEvents.ConnectionStatus.READY, "ok"));

        assertThat(balloonService.publishedBalloons()).isEmpty();
        balloonService.connect();
        listener.onProjectMcpServerStatusChanged(new McpRuntimeEvents.ProjectMcpServerStatusChanged(1L, 10L, "server", McpRuntimeEvents.ConnectionStatus.FAILED, "boom"));
        listener.onProjectMcpServerStatusChanged(new McpRuntimeEvents.ProjectMcpServerStatusChanged(1L, 10L, "server", McpRuntimeEvents.ConnectionStatus.READY, "ok"));

        assertThat(balloonService.publishedBalloons()).hasSize(2);
        assertThat(balloonService.publishedBalloons().get(0).type()).isEqualTo(SystemBalloon.Type.WARNING);
        assertThat(balloonService.publishedBalloons().get(1).type()).isEqualTo(SystemBalloon.Type.SUCCESS);
    }
}

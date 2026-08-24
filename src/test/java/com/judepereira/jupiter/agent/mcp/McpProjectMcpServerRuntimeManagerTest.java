package com.judepereira.jupiter.agent.mcp;

import com.judepereira.jupiter.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.Persistence;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpClientListener;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpProjectMcpServerRuntimeManagerTest {

    @Test
    void connection_failure_sets_failed_status() {
        AppStateService appStateService = mock(AppStateService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        McpClientFactory factory = mock(McpClientFactory.class);
        when(appStateService.loadEnabledMcpServersForProject(1L)).thenReturn(List.of(
                new Persistence.McpServerView(10L, "bad server", "http://127.0.0.1:1", true, List.of(), List.of())
        ));
        when(appStateService.loadProjectEnvironmentVariables(1L)).thenReturn(Map.of());
        when(factory.create(anyString(), anyString(), anyMap(), any())).thenThrow(new IllegalStateException("boom"));

        McpProjectMcpServerRuntimeManager manager = new McpProjectMcpServerRuntimeManager(appStateService, publisher, factory);
        manager.reloadProject(1L);

        Map<Long, McpRuntimeEvents.ConnectionStatus> statuses = manager.connectionStatuses(1L);
        assertEquals(Map.of(10L, McpRuntimeEvents.ConnectionStatus.FAILED), statuses);
        assertThrows(UnsupportedOperationException.class, () -> statuses.put(11L, McpRuntimeEvents.ConnectionStatus.READY));
        verify(publisher).publishEvent(any(McpRuntimeEvents.ProjectMcpRuntimeChanged.class));
    }

    @Test
    void snapshot_is_immutable_and_reload_replaces_tool_shape() throws Exception {
        AppStateService appStateService = mock(AppStateService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

        McpClient firstClient = mock(McpClient.class);
        when(firstClient.listTools()).thenReturn(List.of(ToolSpecification.builder().name("alpha").description("one").parameters(dev.langchain4j.model.chat.request.json.JsonObjectSchema.builder().build()).build()));
        doNothing().when(firstClient).close();

        McpClient secondClient = mock(McpClient.class);
        when(secondClient.listTools()).thenReturn(List.of(ToolSpecification.builder().name("beta").description("two").parameters(dev.langchain4j.model.chat.request.json.JsonObjectSchema.builder().build()).build()));
        doNothing().when(secondClient).close();

        when(appStateService.loadEnabledMcpServersForProject(1L)).thenReturn(List.of(
                new Persistence.McpServerView(10L, "first", "http://one", true, List.of(), List.of())
        ));
        when(appStateService.loadProjectEnvironmentVariables(1L)).thenReturn(Map.of());

        McpProjectMcpServerRuntimeManager manager = new McpProjectMcpServerRuntimeManager(appStateService, publisher, (clientKey, url, headers, listener) -> firstClient);
        manager.reloadProject(1L);
        McpProjectToolSnapshot firstSnapshot = manager.snapshot(1L);
        assertEquals(1, firstSnapshot.toolDefinitions().size());
        assertEquals("mcp__first__alpha", firstSnapshot.toolDefinitions().getFirst().getName());
        assertThrows(UnsupportedOperationException.class, () -> firstSnapshot.toolDefinitions().add(new ToolDefinition("x", "", null)));

        when(appStateService.loadEnabledMcpServersForProject(1L)).thenReturn(List.of(
                new Persistence.McpServerView(10L, "first", "http://one", true, List.of(), List.of()),
                new Persistence.McpServerView(11L, "second", "http://two", true, List.of(), List.of())
        ));
        McpProjectMcpServerRuntimeManager managerWithSecond = new McpProjectMcpServerRuntimeManager(appStateService, publisher, (clientKey, url, headers, listener) -> {
            if ("first".equals(clientKey)) {
                return firstClient;
            }
            return secondClient;
        });
        managerWithSecond.reloadProject(1L);
        McpProjectToolSnapshot secondSnapshot = managerWithSecond.snapshot(1L);
        assertEquals(List.of("mcp__first__alpha", "mcp__second__beta"), secondSnapshot.toolDefinitions().stream().map(ToolDefinition::getName).toList());
        assertThrows(UnsupportedOperationException.class, () -> secondSnapshot.executors().put("x", null));
    }

    @Test
    void naming_collision_is_rejected() throws Exception {
        AppStateService appStateService = mock(AppStateService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        McpClient client = mock(McpClient.class);
        when(client.listTools()).thenReturn(List.of(
                ToolSpecification.builder().name("same").description("one").parameters(dev.langchain4j.model.chat.request.json.JsonObjectSchema.builder().build()).build(),
                ToolSpecification.builder().name("same").description("two").parameters(dev.langchain4j.model.chat.request.json.JsonObjectSchema.builder().build()).build()
        ));
        doNothing().when(client).close();
        McpClientFactory factory = (clientKey, url, headers, listener) -> client;
        when(appStateService.loadEnabledMcpServersForProject(1L)).thenReturn(List.of(
                new Persistence.McpServerView(10L, "server", "http://one", true, List.of(), List.of())
        ));
        when(appStateService.loadProjectEnvironmentVariables(1L)).thenReturn(Map.of());

        McpProjectMcpServerRuntimeManager manager = new McpProjectMcpServerRuntimeManager(appStateService, publisher, factory);
        assertThrows(McpToolCollisionException.class, () -> manager.reloadProject(1L));
    }
}

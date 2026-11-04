package com.judepereira.jupiter.agent.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.judepereira.jupiter.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.Persistence;
import com.judepereira.jupiter.security.RuntimeEnvironment;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class McpProjectMcpServerRuntimeManagerTest {

    @Test
    void connection_failure_sets_failed_status() {
        AppStateService appStateService = mock(AppStateService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        McpClientFactory factory = mock(McpClientFactory.class);
        when(appStateService.loadEnabledMcpServersForProject(1L)).thenReturn(List.of(new Persistence.McpServerView(10L,
                "bad server", "http://127.0.0.1:1", true, List.of(), List.of(), Set.of())));
        when(appStateService.loadProjectEnvironmentVariables(1L)).thenReturn(Map.of());
        when(factory.create(anyString(), anyString(), anyMap(), any())).thenThrow(new IllegalStateException("boom"));

        McpProjectMcpServerRuntimeManager manager = new McpProjectMcpServerRuntimeManager(appStateService, publisher,
                factory, new RuntimeEnvironment(Map.of()));
        manager.reloadProject(1L);

        Map<Long, McpRuntimeEvents.ConnectionStatus> statuses = manager.connectionStatuses(1L);
        assertEquals(Map.of(10L, McpRuntimeEvents.ConnectionStatus.FAILED), statuses);
        assertThrows(UnsupportedOperationException.class,
                () -> statuses.put(11L, McpRuntimeEvents.ConnectionStatus.READY));
        verify(publisher).publishEvent(any(McpRuntimeEvents.ProjectMcpRuntimeChanged.class));
    }

    @Test
    void snapshot_is_immutable_and_reload_replaces_tool_shape() throws Exception {
        AppStateService appStateService = mock(AppStateService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

        McpClient firstClient = mock(McpClient.class);
        when(firstClient.listTools()).thenReturn(List.of(ToolSpecification.builder().name("alpha").description("one")
                .parameters(JsonObjectSchema.builder().build()).build()));
        doNothing().when(firstClient).close();

        McpClient secondClient = mock(McpClient.class);
        when(secondClient.listTools()).thenReturn(List.of(ToolSpecification.builder().name("beta").description("two")
                .parameters(JsonObjectSchema.builder().build()).build()));
        doNothing().when(secondClient).close();

        when(appStateService.loadEnabledMcpServersForProject(1L)).thenReturn(List
                .of(new Persistence.McpServerView(10L, "first", "http://one", true, List.of(), List.of(), Set.of())));
        when(appStateService.loadProjectEnvironmentVariables(1L)).thenReturn(Map.of());

        McpProjectMcpServerRuntimeManager manager = new McpProjectMcpServerRuntimeManager(appStateService, publisher,
                (clientKey, url, headers, listener) -> firstClient, new RuntimeEnvironment(Map.of()));
        manager.reloadProject(1L);
        McpProjectToolSnapshot firstSnapshot = manager.snapshot(1L);
        assertEquals(1, firstSnapshot.toolDefinitions().size());
        assertEquals("mcp__first__alpha", firstSnapshot.toolDefinitions().getFirst().getName());
        assertThrows(UnsupportedOperationException.class,
                () -> firstSnapshot.toolDefinitions().add(ToolDefinition.builtIn("x", "", null)));

        when(appStateService.loadEnabledMcpServersForProject(1L)).thenReturn(List.of(
                new Persistence.McpServerView(10L, "first", "http://one", true, List.of(), List.of(), Set.of()),
                new Persistence.McpServerView(11L, "second", "http://two", true, List.of(), List.of(), Set.of())));
        McpProjectMcpServerRuntimeManager managerWithSecond = new McpProjectMcpServerRuntimeManager(appStateService,
                publisher, (clientKey, url, headers, listener) -> {
                    if ("first".equals(clientKey)) {
                        return firstClient;
                    }
                    return secondClient;
                }, new RuntimeEnvironment(Map.of()));
        managerWithSecond.reloadProject(1L);
        McpProjectToolSnapshot secondSnapshot = managerWithSecond.snapshot(1L);
        assertEquals(List.of("mcp__first__alpha", "mcp__second__beta"),
                secondSnapshot.toolDefinitions().stream().map(ToolDefinition::getName).toList());
        assertThrows(UnsupportedOperationException.class, () -> secondSnapshot.executors().put("x", null));
    }

    @Test
    void disabled_tools_are_filtered_but_retained_and_reenabled_by_reload() throws Exception {
        AppStateService state = mock(AppStateService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        McpClient client = mock(McpClient.class);
        when(client.listTools()).thenReturn(tools("raw tool", "other"));
        doNothing().when(client).close();
        when(state.loadProjectEnvironmentVariables(1L)).thenReturn(Map.of());
        when(state.loadEnabledMcpServersForProject(1L)).thenReturn(List.of(server(10L, "server", Set.of("raw tool"))));
        McpProjectMcpServerRuntimeManager manager = new McpProjectMcpServerRuntimeManager(state, publisher,
                (key, url, headers, listener) -> client, new RuntimeEnvironment(Map.of()));

        manager.reloadProject(1L);
        assertEquals(List.of("mcp__server__other"), names(manager.snapshot(1L)));
        assertEquals(List.of("other", "raw tool"), manager.discoveredServers().getFirst().tools().stream()
                .map(McpProjectMcpServerRuntimeManager.DiscoveredTool::name).toList());
        assertTrue(manager.discoveredServers().getFirst().tools().stream()
                .filter(tool -> tool.name().equals("raw tool")).findFirst().orElseThrow().disabled());

        when(state.loadEnabledMcpServersForProject(1L)).thenReturn(List.of(server(10L, "server", Set.of())));
        manager.reloadProject(1L);
        assertEquals(List.of("mcp__server__raw_tool", "mcp__server__other"), names(manager.snapshot(1L)));
    }

    @Test
    void stale_executor_is_blocked_after_disable_and_server_removal() throws Exception {
        AppStateService state = mock(AppStateService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        McpClient client = mock(McpClient.class);
        when(client.listTools()).thenReturn(tools("raw"));
        doNothing().when(client).close();
        when(state.loadProjectEnvironmentVariables(1L)).thenReturn(Map.of());
        when(state.loadEnabledMcpServersForProject(1L)).thenReturn(List.of(server(10L, "server", Set.of())));
        McpProjectMcpServerRuntimeManager manager = new McpProjectMcpServerRuntimeManager(state, publisher,
                (key, url, headers, listener) -> client, new RuntimeEnvironment(Map.of()));
        manager.reloadProject(1L);
        McpProjectToolExecutor stale = manager.snapshot(1L).executors().get("mcp__server__raw");

        when(state.loadEnabledMcpServersForProject(1L)).thenReturn(List.of(server(10L, "server", Set.of("raw"))));
        manager.reloadProject(1L);
        McpProjectToolExecutor disabledServerExecutor = stale;
        assertThrows(IllegalStateException.class, () -> disabledServerExecutor.execute(Map.of(), null));
        when(state.loadEnabledMcpServersForProject(1L)).thenReturn(List.of(server(10L, "server", Set.of())));
        manager.reloadProject(1L);
        stale = manager.snapshot(1L).executors().get("mcp__server__raw");
        when(state.loadEnabledMcpServersForProject(1L)).thenReturn(List.of());
        manager.reloadProject(1L);
        McpProjectToolExecutor removedServerExecutor = stale;
        assertThrows(IllegalStateException.class, () -> removedServerExecutor.execute(Map.of(), null));
    }

    @Test
    void discovery_unions_project_tools_and_defaults_new_tools_to_enabled() throws Exception {
        AppStateService state = mock(AppStateService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        when(state.loadProjectEnvironmentVariables(anyLong())).thenReturn(Map.of());
        when(state.loadEnabledMcpServersForProject(1L)).thenReturn(List.of(server(10L, "server", Set.of("one"))));
        when(state.loadEnabledMcpServersForProject(2L)).thenReturn(List.of(server(10L, "server", Set.of("two"))));
        McpClient first = mock(McpClient.class);
        McpClient second = mock(McpClient.class);
        when(first.listTools()).thenReturn(tools("one", "two"));
        when(second.listTools()).thenReturn(tools("one", "two"));
        doNothing().when(first).close();
        doNothing().when(second).close();
        McpProjectMcpServerRuntimeManager manager = new McpProjectMcpServerRuntimeManager(state, publisher,
                (key, url, headers, listener) -> "http://one".equals(url) ? first : second,
                new RuntimeEnvironment(Map.of()));
        when(state.loadEnabledMcpServersForProject(1L))
                .thenReturn(List.of(server(10L, "server", Set.of("one"), "http://one")));
        when(state.loadEnabledMcpServersForProject(2L))
                .thenReturn(List.of(server(10L, "server", Set.of("two"), "http://two")));

        manager.reloadProject(1L);
        manager.reloadProject(2L);
        var discovered = manager.discoveredServers().getFirst().tools();
        assertEquals(Set.of("one", "two"), discovered.stream()
                .map(McpProjectMcpServerRuntimeManager.DiscoveredTool::name).collect(Collectors.toSet()));
        assertTrue(discovered.stream().noneMatch(McpProjectMcpServerRuntimeManager.DiscoveredTool::disabled));
        assertEquals(List.of("mcp__server__two"), names(manager.snapshot(1L)));
        assertEquals(List.of("mcp__server__one"), names(manager.snapshot(2L)));
    }

    private static List<ToolSpecification> tools(String... names) {
        return Arrays.stream(names).map(name -> ToolSpecification.builder().name(name).description(name)
                .parameters(JsonObjectSchema.builder().build()).build()).toList();
    }

    private static List<String> names(McpProjectToolSnapshot snapshot) {
        return snapshot.toolDefinitions().stream().map(ToolDefinition::getName).toList();
    }

    private static Persistence.McpServerView server(long id, String name, Set<String> disabled) {
        return server(id, name, disabled, "http://one");
    }

    private static Persistence.McpServerView server(long id, String name, Set<String> disabled, String url) {
        return new Persistence.McpServerView(id, name, url, true, List.of(), List.of(), disabled);
    }

    @Test
    void naming_collision_is_rejected() throws Exception {
        AppStateService appStateService = mock(AppStateService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        McpClient client = mock(McpClient.class);
        when(client.listTools()).thenReturn(List.of(
                ToolSpecification.builder().name("same").description("one")
                        .parameters(JsonObjectSchema.builder().build()).build(),
                ToolSpecification.builder().name("same").description("two")
                        .parameters(JsonObjectSchema.builder().build()).build()));
        doNothing().when(client).close();
        McpClientFactory factory = (clientKey, url, headers, listener) -> client;
        when(appStateService.loadEnabledMcpServersForProject(1L)).thenReturn(List
                .of(new Persistence.McpServerView(10L, "server", "http://one", true, List.of(), List.of(), Set.of())));
        when(appStateService.loadProjectEnvironmentVariables(1L)).thenReturn(Map.of());

        McpProjectMcpServerRuntimeManager manager = new McpProjectMcpServerRuntimeManager(appStateService, publisher,
                factory, new RuntimeEnvironment(Map.of()));
        assertThrows(McpToolCollisionException.class, () -> manager.reloadProject(1L));
    }
}

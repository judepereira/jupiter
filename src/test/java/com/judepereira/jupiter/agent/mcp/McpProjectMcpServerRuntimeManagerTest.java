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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class McpProjectMcpServerRuntimeManagerTest {

    @Test
    void connect_preserves_primary_error_when_close_status_listener_fails() throws Exception {
        McpClient client = mock(McpClient.class);
        AssertionError primary = new AssertionError("list tools failed");
        RuntimeException listenerFailure = new RuntimeException("closed callback failed");
        when(client.listTools()).thenThrow(primary);
        McpProjectMcpServerRuntime.McpRuntimeListener listener = new McpProjectMcpServerRuntime.McpRuntimeListener() {
            @Override
            public void onStatusChanged(long serverId, McpRuntimeEvents.ConnectionStatus status, String message) {
                if (status == McpRuntimeEvents.ConnectionStatus.CLOSED) {
                    throw listenerFailure;
                }
            }

            @Override
            public void onToolsChanged(long serverId) {
            }
        };

        AssertionError thrown = assertThrows(AssertionError.class,
                () -> McpProjectMcpServerRuntime.connect(
                        new Persistence.McpServerView(10L, "server", "http://one", true, List.of(), List.of()),
                        Map.of(), new McpTemplateResolver(name -> null, name -> null),
                        (name, url, headers, clientListener) -> client, listener));
        assertSame(primary, thrown);
        assertArrayEquals(new Throwable[]{listenerFailure}, thrown.getSuppressed());
        verify(client).close();
    }

    @Test
    void connection_failure_sets_failed_status() {
        AppStateService appStateService = mock(AppStateService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        McpClientFactory factory = mock(McpClientFactory.class);
        when(appStateService.loadEnabledMcpServersForProject(1L)).thenReturn(List.of(
                new Persistence.McpServerView(10L, "bad server", "http://127.0.0.1:1", true, List.of(), List.of())));
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

        when(appStateService.loadEnabledMcpServersForProject(1L)).thenReturn(
                List.of(new Persistence.McpServerView(10L, "first", "http://one", true, List.of(), List.of())));
        when(appStateService.loadProjectEnvironmentVariables(1L)).thenReturn(Map.of());

        McpProjectMcpServerRuntimeManager manager = new McpProjectMcpServerRuntimeManager(appStateService, publisher,
                (clientKey, url, headers, listener) -> firstClient, new RuntimeEnvironment(Map.of()));
        manager.reloadProject(1L);
        McpProjectToolSnapshot firstSnapshot = manager.snapshot(1L);
        assertEquals(1, firstSnapshot.toolDefinitions().size());
        assertEquals("mcp__first__alpha", firstSnapshot.toolDefinitions().getFirst().getName());
        assertThrows(UnsupportedOperationException.class,
                () -> firstSnapshot.toolDefinitions().add(ToolDefinition.builtIn("x", "", null)));

        when(appStateService.loadEnabledMcpServersForProject(1L)).thenReturn(
                List.of(new Persistence.McpServerView(10L, "first", "http://one", true, List.of(), List.of()),
                        new Persistence.McpServerView(11L, "second", "http://two", true, List.of(), List.of())));
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
    void newly_created_clients_are_closed_when_a_later_connection_fails() throws Exception {
        AppStateService appStateService = mock(AppStateService.class);
        McpClient firstClient = mock(McpClient.class);
        when(firstClient.listTools()).thenReturn(List.of());
        when(appStateService.loadEnabledMcpServersForProject(1L)).thenReturn(
                List.of(new Persistence.McpServerView(10L, "first", "http://one", true, List.of(), List.of()),
                        new Persistence.McpServerView(11L, "second", "http://two", true, List.of(), List.of())));
        when(appStateService.loadProjectEnvironmentVariables(1L)).thenReturn(Map.of());
        McpClientFactory factory = (name, url, headers, listener) -> {
            if ("http://two".equals(url)) {
                throw new McpToolCollisionException("collision");
            }
            return firstClient;
        };

        McpProjectMcpServerRuntimeManager manager = new McpProjectMcpServerRuntimeManager(appStateService,
                mock(ApplicationEventPublisher.class), factory, new RuntimeEnvironment(Map.of()));
        assertThrows(IllegalStateException.class, () -> manager.reloadProject(1L));
        verify(firstClient).close();
    }

    @Test
    void failed_reload_closes_new_clients_but_keeps_reconnected_retained_client() throws Exception {
        AppStateService appStateService = mock(AppStateService.class);
        McpClient original = mock(McpClient.class);
        McpClient replacement = mock(McpClient.class);
        McpClient successfulNew = mock(McpClient.class);
        McpClient collision = mock(McpClient.class);
        when(original.listTools()).thenReturn(List.of());
        when(replacement.listTools()).thenReturn(List.of());
        when(successfulNew.listTools()).thenReturn(List.of());
        when(collision.listTools()).thenReturn(List.of(
                ToolSpecification.builder().name("same").description("one")
                        .parameters(JsonObjectSchema.builder().build()).build(),
                ToolSpecification.builder().name("same").description("two")
                        .parameters(JsonObjectSchema.builder().build()).build()));
        when(appStateService.loadProjectEnvironmentVariables(1L)).thenReturn(Map.of());
        when(appStateService.loadEnabledMcpServersForProject(1L)).thenReturn(
                List.of(new Persistence.McpServerView(10L, "retained", "http://retained", true, List.of(), List.of())));
        McpClientFactory factory = (name, url, headers, listener) -> switch (url) {
            case "http://retained" -> original;
            case "http://success" -> successfulNew;
            case "http://collision" -> collision;
            default -> replacement;
        };
        McpProjectMcpServerRuntimeManager manager = new McpProjectMcpServerRuntimeManager(appStateService,
                mock(ApplicationEventPublisher.class), factory, new RuntimeEnvironment(Map.of()));
        manager.reloadProject(1L);

        when(appStateService.loadEnabledMcpServersForProject(1L)).thenReturn(List.of(
                new Persistence.McpServerView(10L, "retained", "http://retained", true, List.of(), List.of()),
                new Persistence.McpServerView(11L, "new", "http://success", true, List.of(), List.of()),
                new Persistence.McpServerView(12L, "collision", "http://collision", true, List.of(), List.of())));
        assertThrows(McpToolCollisionException.class, () -> manager.reloadProject(1L));

        verify(original).close();
        verify(replacement, never()).close();
        verify(successfulNew).close();
        verify(collision).close();
        manager.close();
        verify(original, times(2)).close();
    }

    @Test
    void existing_runtime_is_not_closed_when_reload_fails() throws Exception {
        AppStateService appStateService = mock(AppStateService.class);
        McpClient client = mock(McpClient.class);
        when(client.listTools()).thenReturn(List.of());
        when(appStateService.loadEnabledMcpServersForProject(1L)).thenReturn(
                List.of(new Persistence.McpServerView(10L, "first", "http://one", true, List.of(), List.of())));
        when(appStateService.loadProjectEnvironmentVariables(1L)).thenReturn(Map.of());
        McpClientFactory factory = (name, url, headers, listener) -> client;
        McpProjectMcpServerRuntimeManager manager = new McpProjectMcpServerRuntimeManager(appStateService,
                mock(ApplicationEventPublisher.class), factory, new RuntimeEnvironment(Map.of()));
        manager.reloadProject(1L);

        when(appStateService.loadEnabledMcpServersForProject(1L)).thenReturn(
                List.of(new Persistence.McpServerView(10L, "first", "http://one", true, List.of(), List.of()),
                        new Persistence.McpServerView(11L, "second", "http://two", true, List.of(), List.of())));
        when(appStateService.loadProjectEnvironmentVariables(1L)).thenThrow(new IllegalStateException("reload failed"));
        assertThrows(IllegalStateException.class, () -> manager.reloadProject(1L));
        verify(client, never()).close();
    }

    @Test
    void shutdown_closes_all_retained_clients_and_is_idempotent() throws Exception {
        AppStateService appStateService = mock(AppStateService.class);
        McpClient first = mock(McpClient.class);
        McpClient second = mock(McpClient.class);
        when(first.listTools()).thenReturn(List.of());
        when(second.listTools()).thenReturn(List.of());
        when(appStateService.loadEnabledMcpServersForProject(1L)).thenReturn(
                List.of(new Persistence.McpServerView(10L, "first", "http://one", true, List.of(), List.of())));
        when(appStateService.loadEnabledMcpServersForProject(2L)).thenReturn(
                List.of(new Persistence.McpServerView(20L, "second", "http://two", true, List.of(), List.of())));
        when(appStateService.loadProjectEnvironmentVariables(anyLong())).thenReturn(Map.of());
        McpProjectMcpServerRuntimeManager manager = new McpProjectMcpServerRuntimeManager(appStateService,
                mock(ApplicationEventPublisher.class),
                (name, url, headers, listener) -> "http://one".equals(url) ? first : second,
                new RuntimeEnvironment(Map.of()));
        manager.reloadProject(1L);
        manager.reloadProject(2L);
        manager.close();
        manager.close();
        verify(first).close();
        verify(second).close();
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
        when(appStateService.loadEnabledMcpServersForProject(1L)).thenReturn(
                List.of(new Persistence.McpServerView(10L, "server", "http://one", true, List.of(), List.of())));
        when(appStateService.loadProjectEnvironmentVariables(1L)).thenReturn(Map.of());

        McpProjectMcpServerRuntimeManager manager = new McpProjectMcpServerRuntimeManager(appStateService, publisher,
                factory, new RuntimeEnvironment(Map.of()));
        assertThrows(McpToolCollisionException.class, () -> manager.reloadProject(1L));
        verify(client).close();
    }
}

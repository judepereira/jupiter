package com.judepereira.jupiter.agent.mcp;

import com.judepereira.jupiter.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter.persistence.Persistence;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpClientListener;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

final class McpProjectMcpServerRuntime implements AutoCloseable {
    private final Persistence.McpServerView server;
    private Map<String, String> projectEnvironmentVariables;
    private final McpTemplateResolver templateResolver;
    private final McpClientFactory clientFactory;
    private final McpRuntimeListener runtimeListener;
    private final String serverSlug;
    private McpClient client;
    private long listenerGeneration;
    private final AtomicReference<State> state = new AtomicReference<>(
            new State(McpRuntimeEvents.ConnectionStatus.CONNECTING, "connecting", List.of(), Map.of()));

    private McpProjectMcpServerRuntime(Persistence.McpServerView server,
            Map<String, String> projectEnvironmentVariables, McpTemplateResolver templateResolver,
            McpClientFactory clientFactory, McpRuntimeListener runtimeListener) {
        this.server = server;
        this.projectEnvironmentVariables = projectEnvironmentVariables;
        this.templateResolver = templateResolver;
        this.clientFactory = clientFactory;
        this.runtimeListener = runtimeListener;
        this.serverSlug = templateResolver.slugify(server.name());
    }

    static McpProjectMcpServerRuntime connect(Persistence.McpServerView server,
            Map<String, String> projectEnvironmentVariables, McpTemplateResolver templateResolver,
            McpClientFactory clientFactory, McpRuntimeListener runtimeListener) {
        McpProjectMcpServerRuntime runtime = new McpProjectMcpServerRuntime(server, projectEnvironmentVariables,
                templateResolver, clientFactory, runtimeListener);
        runtime.reconnect();
        return runtime;
    }

    synchronized void updateProjectEnvironmentVariables(Map<String, String> projectEnvironmentVariables) {
        this.projectEnvironmentVariables = projectEnvironmentVariables;
    }

    synchronized void reconnect() {
        long generation = ++listenerGeneration;
        closeClient();
        updateStatus(McpRuntimeEvents.ConnectionStatus.CONNECTING, "connecting");
        try {
            String resolvedUrl = templateResolver.resolve("MCP server URL", server.url(), projectEnvironmentVariables);
            Map<String, String> resolvedHeaders = templateResolver.resolveHeaders(server.headers(),
                    projectEnvironmentVariables);
            Listener listener = new Listener(generation);
            client = clientFactory.create(server.name(), resolvedUrl, resolvedHeaders, listener);
            if (!refreshTools(generation)) {
                return;
            }
            updateStatus(McpRuntimeEvents.ConnectionStatus.READY, "ready");
        } catch (McpToolCollisionException e) {
            clearTools();
            updateStatus(McpRuntimeEvents.ConnectionStatus.FAILED, safeMessage(e));
            throw e;
        } catch (Exception e) {
            clearTools();
            updateStatus(McpRuntimeEvents.ConnectionStatus.FAILED, safeMessage(e));
        }
    }

    synchronized void refreshTools() {
        refreshTools(listenerGeneration);
    }

    private boolean refreshTools(long generation) {
        if (generation != listenerGeneration) {
            return false;
        }
        McpClient currentClient = client;
        if (currentClient == null) {
            return false;
        }
        try {
            List<ToolSpecification> remoteTools = currentClient.listTools();
            Map<String, McpProjectToolExecutor> nextExecutors = new LinkedHashMap<>();
            List<ToolDefinition> nextDefinitions = new ArrayList<>(remoteTools.size());
            for (ToolSpecification specification : remoteTools) {
                McpToolAdapter adapter = McpToolAdapter.from(currentClient, serverSlug, specification);
                if (nextExecutors.putIfAbsent(adapter.modelToolName(), adapter) != null) {
                    throw new McpToolCollisionException("MCP tool name collision: " + adapter.modelToolName());
                }
                nextDefinitions.add(adapter.definition());
            }
            if (generation != listenerGeneration || currentClient != client) {
                return false;
            }
            publishTools(List.copyOf(nextDefinitions), Map.copyOf(nextExecutors));
            runtimeListener.onToolsChanged(server.id());
            return true;
        } catch (McpToolCollisionException e) {
            clearTools();
            updateStatus(McpRuntimeEvents.ConnectionStatus.FAILED, safeMessage(e));
            throw e;
        } catch (Exception e) {
            clearTools();
            updateStatus(McpRuntimeEvents.ConnectionStatus.FAILED, safeMessage(e));
            return false;
        }
    }

    long serverId() {
        return server.id();
    }

    String serverName() {
        return server.name();
    }

    Persistence.McpServerView serverView() {
        return server;
    }

    McpRuntimeEvents.ConnectionStatus status() {
        return state.get().status();
    }

    String statusMessage() {
        return state.get().statusMessage();
    }

    McpProjectToolSnapshot snapshot(long projectId) {
        State current = state.get();
        return new McpProjectToolSnapshot(projectId, current.toolDefinitions(), current.executors());
    }

    @Override
    public synchronized void close() {
        ++listenerGeneration;
        closeClient();
        updateStatus(McpRuntimeEvents.ConnectionStatus.CLOSED, "closed");
    }

    private void closeClient() {
        McpClient currentClient = client;
        client = null;
        if (currentClient != null) {
            try {
                currentClient.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void updateStatus(McpRuntimeEvents.ConnectionStatus status, String message) {
        state.updateAndGet(current -> new State(status, message, current.toolDefinitions(), current.executors()));
        runtimeListener.onStatusChanged(server.id(), status, message);
    }

    private void publishTools(List<ToolDefinition> definitions, Map<String, McpProjectToolExecutor> nextExecutors) {
        state.updateAndGet(current -> new State(current.status(), current.statusMessage(), definitions, nextExecutors));
    }

    private void clearTools() {
        publishTools(List.of(), Map.of());
    }

    private record State(McpRuntimeEvents.ConnectionStatus status, String statusMessage,
            List<ToolDefinition> toolDefinitions, Map<String, McpProjectToolExecutor> executors) {
    }

    private static String safeMessage(Exception e) {
        return e.getClass().getSimpleName();
    }

    private final class Listener implements McpClientListener {
        private final long generation;

        private Listener(long generation) {
            this.generation = generation;
        }

        @Override
        public void onNotificationToolsListChanged() {
            synchronized (McpProjectMcpServerRuntime.this) {
                if (generation == listenerGeneration) {
                    refreshTools(generation);
                }
            }
        }
    }

    interface McpRuntimeListener {
        void onStatusChanged(long serverId, McpRuntimeEvents.ConnectionStatus status, String message);

        void onToolsChanged(long serverId);
    }
}

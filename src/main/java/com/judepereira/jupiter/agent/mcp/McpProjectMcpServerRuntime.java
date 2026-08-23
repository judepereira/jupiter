package com.judepereira.jupiter.agent.mcp;

import com.judepereira.jupiter.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter.persistence.Persistence;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpClientListener;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class McpProjectMcpServerRuntime implements AutoCloseable {
    private final Persistence.McpServerView server;
    private Map<String, String> projectEnvironmentVariables;
    private final McpTemplateResolver templateResolver;
    private final McpClientFactory clientFactory;
    private final McpRuntimeListener runtimeListener;
    private final String serverSlug;
    private volatile McpClient client;
    private volatile McpRuntimeEvents.ConnectionStatus status = McpRuntimeEvents.ConnectionStatus.CONNECTING;
    private volatile String statusMessage = "connecting";
    private volatile List<ToolDefinition> toolDefinitions = List.of();
    private volatile Map<String, McpProjectToolExecutor> executors = Map.of();

    private McpProjectMcpServerRuntime(Persistence.McpServerView server, Map<String, String> projectEnvironmentVariables,
                                       McpTemplateResolver templateResolver, McpClientFactory clientFactory,
                                       McpRuntimeListener runtimeListener) {
        this.server = server;
        this.projectEnvironmentVariables = projectEnvironmentVariables;
        this.templateResolver = templateResolver;
        this.clientFactory = clientFactory;
        this.runtimeListener = runtimeListener;
        this.serverSlug = templateResolver.slugify(server.name());
    }

    static McpProjectMcpServerRuntime connect(Persistence.McpServerView server, Map<String, String> projectEnvironmentVariables,
                                              McpTemplateResolver templateResolver, McpClientFactory clientFactory,
                                              McpRuntimeListener runtimeListener) {
        McpProjectMcpServerRuntime runtime = new McpProjectMcpServerRuntime(server, projectEnvironmentVariables, templateResolver, clientFactory, runtimeListener);
        runtime.reconnect();
        return runtime;
    }

    synchronized void updateProjectEnvironmentVariables(Map<String, String> projectEnvironmentVariables) {
        this.projectEnvironmentVariables = projectEnvironmentVariables;
    }

    synchronized void reconnect() {
        closeClient();
        updateStatus(McpRuntimeEvents.ConnectionStatus.CONNECTING, "connecting");
        try {
            String resolvedUrl = templateResolver.resolve("MCP server URL", server.url(), projectEnvironmentVariables);
            Map<String, String> resolvedHeaders = templateResolver.resolveHeaders(server.headers(), projectEnvironmentVariables);
            client = clientFactory.create(server.name(), resolvedUrl, resolvedHeaders, new Listener());
            refreshTools();
            updateStatus(McpRuntimeEvents.ConnectionStatus.READY, "ready");
        } catch (McpToolCollisionException e) {
            toolDefinitions = List.of();
            executors = Map.of();
            updateStatus(McpRuntimeEvents.ConnectionStatus.FAILED, safeMessage(e));
            throw e;
        } catch (Exception e) {
            toolDefinitions = List.of();
            executors = Map.of();
            updateStatus(McpRuntimeEvents.ConnectionStatus.FAILED, safeMessage(e));
        }
    }

    synchronized void refreshTools() {
        McpClient currentClient = client;
        if (currentClient == null) {
            return;
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
            toolDefinitions = List.copyOf(nextDefinitions);
            executors = Map.copyOf(nextExecutors);
            runtimeListener.onToolsChanged(server.id());
        } catch (McpToolCollisionException e) {
            toolDefinitions = List.of();
            executors = Map.of();
            updateStatus(McpRuntimeEvents.ConnectionStatus.FAILED, safeMessage(e));
            throw e;
        } catch (Exception e) {
            toolDefinitions = List.of();
            executors = Map.of();
            updateStatus(McpRuntimeEvents.ConnectionStatus.FAILED, safeMessage(e));
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
        return status;
    }

    String statusMessage() {
        return statusMessage;
    }

    McpProjectToolSnapshot snapshot(long projectId) {
        return new McpProjectToolSnapshot(projectId, toolDefinitions, executors);
    }

    @Override
    public synchronized void close() {
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
        this.status = status;
        this.statusMessage = message;
        runtimeListener.onStatusChanged(server.id(), status, message);
    }

    private static String safeMessage(Exception e) {
        return e.getClass().getSimpleName();
    }

    private final class Listener implements McpClientListener {
        @Override
        public void onNotificationToolsListChanged() {
            refreshTools();
        }
    }

    interface McpRuntimeListener {
        void onStatusChanged(long serverId, McpRuntimeEvents.ConnectionStatus status, String message);

        void onToolsChanged(long serverId);
    }
}

package com.judepereira.jupiter.agent.mcp;

import com.judepereira.jupiter.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter.agent.tools.ToolExecutionContext;
import com.judepereira.jupiter.agent.tools.ToolExecutionResult;
import com.judepereira.jupiter.persistence.Persistence;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpClientListener;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class McpProjectMcpServerRuntime implements AutoCloseable {
    private volatile Persistence.McpServerView server;
    private volatile DiscoveryState discovery = new DiscoveryState(List.of(), Map.of(), List.of(), Map.of());

    private record DiscoveryState(List<ToolDefinition> rawDefinitions, Map<String, McpToolAdapter> rawExecutors,
            List<ToolDefinition> definitions, Map<String, McpProjectToolExecutor> executors) {
    }
    private Map<String, String> projectEnvironmentVariables;
    private final McpTemplateResolver templateResolver;
    private final McpClientFactory clientFactory;
    private final McpRuntimeListener runtimeListener;

    private volatile McpClient client;
    private volatile McpRuntimeEvents.ConnectionStatus status = McpRuntimeEvents.ConnectionStatus.CONNECTING;
    private volatile String statusMessage = "connecting";

    private McpProjectMcpServerRuntime(Persistence.McpServerView server,
            Map<String, String> projectEnvironmentVariables, McpTemplateResolver templateResolver,
            McpClientFactory clientFactory, McpRuntimeListener runtimeListener) {
        this.server = server;
        this.projectEnvironmentVariables = projectEnvironmentVariables;
        this.templateResolver = templateResolver;
        this.clientFactory = clientFactory;
        this.runtimeListener = runtimeListener;
    }

    static McpProjectMcpServerRuntime connect(Persistence.McpServerView server,
            Map<String, String> projectEnvironmentVariables, McpTemplateResolver templateResolver,
            McpClientFactory clientFactory, McpRuntimeListener runtimeListener) {
        McpProjectMcpServerRuntime runtime = new McpProjectMcpServerRuntime(server, projectEnvironmentVariables,
                templateResolver, clientFactory, runtimeListener);
        runtime.reconnect();
        return runtime;
    }

    synchronized void updateConfiguration(Persistence.McpServerView server,
            Map<String, String> projectEnvironmentVariables) {
        this.server = server;
        this.projectEnvironmentVariables = projectEnvironmentVariables;
        applyDisabledPolicy();
    }

    private void applyDisabledPolicy() {
        DiscoveryState current = discovery;
        Map<String, McpProjectToolExecutor> enabled = new LinkedHashMap<>();
        current.rawExecutors().values().forEach(adapter -> {
            if (!server.disabledToolNames().contains(adapter.remoteToolName())) {
                enabled.put(adapter.modelToolName(), adapter);
            }
        });
        List<ToolDefinition> definitions = current.rawDefinitions().stream()
                .filter(definition -> enabled.containsKey(definition.getName())).toList();
        discovery = new DiscoveryState(current.rawDefinitions(), current.rawExecutors(), definitions,
                Map.copyOf(enabled));
    }

    synchronized void reconnect() {
        discovery = new DiscoveryState(discovery.rawDefinitions(), discovery.rawExecutors(), List.of(), Map.of());
        closeClient();
        updateStatus(McpRuntimeEvents.ConnectionStatus.CONNECTING, "connecting");
        try {
            String resolvedUrl = templateResolver.resolve("MCP server URL", server.url(), projectEnvironmentVariables);
            Map<String, String> resolvedHeaders = templateResolver.resolveHeaders(server.headers(),
                    projectEnvironmentVariables);
            client = clientFactory.create(server.name(), resolvedUrl, resolvedHeaders, new Listener());
            if (refreshTools()) {
                updateStatus(McpRuntimeEvents.ConnectionStatus.READY, "ready");
            }
        } catch (McpToolCollisionException e) {
            discovery = new DiscoveryState(discovery.rawDefinitions(), discovery.rawExecutors(), List.of(), Map.of());
            updateStatus(McpRuntimeEvents.ConnectionStatus.FAILED, safeMessage(e));
            throw e;
        } catch (Exception e) {
            discovery = new DiscoveryState(discovery.rawDefinitions(), discovery.rawExecutors(), List.of(), Map.of());
            updateStatus(McpRuntimeEvents.ConnectionStatus.FAILED, safeMessage(e));
        }
    }

    synchronized boolean refreshTools() {
        McpClient currentClient = client;
        if (currentClient == null) {
            return false;
        }
        try {
            List<ToolSpecification> remoteTools = currentClient.listTools();
            Map<String, McpProjectToolExecutor> nextExecutors = new LinkedHashMap<>();
            Map<String, McpToolAdapter> nextDiscoveredExecutors = new LinkedHashMap<>();
            List<ToolDefinition> nextDefinitions = new ArrayList<>(remoteTools.size());
            for (ToolSpecification specification : remoteTools) {
                McpToolAdapter adapter = McpToolAdapter.from(currentClient, templateResolver.slugify(server.name()),
                        specification);
                nextDiscoveredExecutors.put(specification.name(), adapter);
                if (nextExecutors.putIfAbsent(adapter.modelToolName(), adapter) != null) {
                    throw new McpToolCollisionException("MCP tool name collision: " + adapter.modelToolName());
                }
                nextDefinitions.add(adapter.definition());
            }
            Map<String, McpProjectToolExecutor> disabledFilteredExecutors = new LinkedHashMap<>(nextExecutors);
            disabledFilteredExecutors.entrySet().removeIf(
                    entry -> server.disabledToolNames().contains(((McpToolAdapter) entry.getValue()).remoteToolName()));
            List<ToolDefinition> enabledDefinitions = nextDefinitions.stream()
                    .filter(definition -> disabledFilteredExecutors.containsKey(definition.getName())).toList();
            discovery = new DiscoveryState(List.copyOf(nextDefinitions), Map.copyOf(nextDiscoveredExecutors),
                    enabledDefinitions, Map.copyOf(disabledFilteredExecutors));
            runtimeListener.onToolsChanged(server.id());
            if (status == McpRuntimeEvents.ConnectionStatus.FAILED
                    || status == McpRuntimeEvents.ConnectionStatus.CONNECTING) {
                updateStatus(McpRuntimeEvents.ConnectionStatus.READY, "ready");
            }
            return true;
        } catch (McpToolCollisionException e) {
            discovery = new DiscoveryState(discovery.rawDefinitions(), discovery.rawExecutors(), List.of(), Map.of());
            updateStatus(McpRuntimeEvents.ConnectionStatus.FAILED, safeMessage(e));
            throw e;
        } catch (Exception e) {
            discovery = new DiscoveryState(discovery.rawDefinitions(), discovery.rawExecutors(), List.of(), Map.of());
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
        return status;
    }

    String statusMessage() {
        return statusMessage;
    }

    McpProjectToolSnapshot snapshot(long projectId) {
        DiscoveryState current = discovery;
        return new McpProjectToolSnapshot(projectId, current.definitions(), guardedExecutors(current));
    }

    private Map<String, McpProjectToolExecutor> guardedExecutors(DiscoveryState state) {
        Map<String, McpProjectToolExecutor> currentExecutors = state.executors();
        Map<String, McpProjectToolExecutor> guarded = new LinkedHashMap<>();
        currentExecutors.forEach((name, delegate) -> guarded.put(name, new McpProjectToolExecutor() {
            @Override
            public String modelToolName() {
                return delegate.modelToolName();
            }

            @Override
            public String serverSlug() {
                return delegate.serverSlug();
            }

            @Override
            public String toolSlug() {
                return delegate.toolSlug();
            }

            @Override
            public ToolExecutionResult execute(Map<String, Object> args, ToolExecutionContext context)
                    throws Exception {
                String rawName = ((McpToolAdapter) delegate).remoteToolName();
                McpProjectMcpServerRuntime runtime = McpProjectMcpServerRuntime.this;
                if (runtime.status != McpRuntimeEvents.ConnectionStatus.READY
                        || !runtime.discovery.executors().containsValue(delegate)
                        || runtime.server.disabledToolNames().contains(rawName)) {
                    throw new IllegalStateException("MCP tool is unavailable: " + rawName);
                }
                return delegate.execute(args, context);
            }
        }));
        return Map.copyOf(guarded);
    }

    List<DiscoveredTool> discoveredTools() {
        List<DiscoveredTool> result = new ArrayList<>();
        DiscoveryState current = discovery;
        for (McpToolAdapter adapter : current.rawExecutors().values()) {
            result.add(new DiscoveredTool(adapter.remoteToolName(), adapter.definition().getDescription(),
                    server.disabledToolNames().contains(adapter.remoteToolName())));
        }
        return List.copyOf(result);
    }

    record DiscoveredTool(String name, String description, boolean disabled) {
    }

    @Override
    public synchronized void close() {
        discovery = new DiscoveryState(discovery.rawDefinitions(), Map.of(), List.of(), Map.of());
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

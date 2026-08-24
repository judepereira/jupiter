package com.judepereira.jupiter.agent.mcp;

import com.judepereira.jupiter.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.Persistence.McpServerView;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Log4j2
@Service
@RequiredArgsConstructor
public class McpProjectMcpServerRuntimeManager implements McpProjectMcpServerRuntime.McpRuntimeListener {
    private final AppStateService appStateService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final McpClientFactory clientFactory;
    private final McpTemplateResolver templateResolver = new McpTemplateResolver();
    private final Map<Long, ProjectRuntime> runtimes = new ConcurrentHashMap<>();
    private final Map<Long, String> projectToolFingerprints = new ConcurrentHashMap<>();
    private final Set<Long> reloadingProjects = ConcurrentHashMap.newKeySet();

    public synchronized void reloadProject(long projectId) {
        List<McpServerView> servers = appStateService.loadEnabledMcpServersForProject(projectId);
        ProjectRuntime runtime = runtimes.computeIfAbsent(projectId, ProjectRuntime::new);
        reloadingProjects.add(projectId);
        try {
            runtime.reload(servers);
        } finally {
            reloadingProjects.remove(projectId);
        }

        applicationEventPublisher.publishEvent(new McpRuntimeEvents.ProjectMcpRuntimeChanged(projectId));

        String fingerprint = runtime.toolFingerprint();
        String previousFingerprint = projectToolFingerprints.put(projectId, fingerprint);
        if (previousFingerprint != null && !previousFingerprint.equals(fingerprint)) {
            applicationEventPublisher.publishEvent(new McpRuntimeEvents.ProjectMcpToolsChanged(projectId));
        }
    }

    public synchronized void reloadVisibleProjects() {
        for (var project : appStateService.loadViewData().projects()) {
            if (project == null) {
                continue;
            }
            reloadProject(project.id());
        }
    }

    public synchronized void initializeVisibleProjects() {
        reloadVisibleProjects();
    }

    public synchronized McpProjectToolSnapshot snapshot(long projectId) {
        ProjectRuntime runtime = runtimes.get(projectId);
        if (runtime == null) {
            return new McpProjectToolSnapshot(projectId, List.of(), Map.of());
        }
        return runtime.snapshot();
    }

    public synchronized Map<Long, McpRuntimeEvents.ConnectionStatus> connectionStatuses(long projectId) {
        ProjectRuntime runtime = runtimes.get(projectId);
        if (runtime == null) {
            return Map.of();
        }
        return runtime.connectionStatuses();
    }

    public synchronized void closeProject(long projectId) {
        ProjectRuntime runtime = runtimes.remove(projectId);
        projectToolFingerprints.remove(projectId);
        reloadingProjects.remove(projectId);
        if (runtime != null) {
            runtime.close();
        }
    }

    @Override
    public void onStatusChanged(long serverId, McpRuntimeEvents.ConnectionStatus status, String message) {
        ProjectRuntime runtime = findRuntime(serverId);
        if (runtime == null) {
            return;
        }
        applicationEventPublisher.publishEvent(new McpRuntimeEvents.ProjectMcpServerStatusChanged(
                runtime.projectId(),
                serverId,
                runtime.serverName(serverId),
                status,
                message
        ));
    }

    @Override
    public void onToolsChanged(long serverId) {
        ProjectRuntime runtime = findRuntime(serverId);
        if (runtime == null || reloadingProjects.contains(runtime.projectId())) {
            return;
        }
        applicationEventPublisher.publishEvent(new McpRuntimeEvents.ProjectMcpToolsChanged(runtime.projectId()));
    }

    private ProjectRuntime findRuntime(long serverId) {
        for (ProjectRuntime runtime : runtimes.values()) {
            if (runtime.containsServer(serverId)) {
                return runtime;
            }
        }
        return null;
    }

    private final class ProjectRuntime {
        private final long projectId;
        private final Map<Long, McpProjectMcpServerRuntime> byServerId = new LinkedHashMap<>();

        private ProjectRuntime(long projectId) {
            this.projectId = projectId;
        }

        private void reload(List<McpServerView> servers) {
            Map<Long, McpProjectMcpServerRuntime> next = new LinkedHashMap<>();
            Map<String, String> env = appStateService.loadProjectEnvironmentVariables(projectId);
            for (McpServerView server : servers) {
                McpProjectMcpServerRuntime runtime = byServerId.get(server.id());
                if (runtime == null) {
                    runtime = McpProjectMcpServerRuntime.connect(server, env, templateResolver, clientFactory, McpProjectMcpServerRuntimeManager.this);
                } else {
                    runtime.updateProjectEnvironmentVariables(env);
                    runtime.reconnect();
                }
                next.put(server.id(), runtime);
            }
            for (Map.Entry<Long, McpProjectMcpServerRuntime> entry : byServerId.entrySet()) {
                if (!next.containsKey(entry.getKey())) {
                    entry.getValue().close();
                }
            }
            byServerId.clear();
            byServerId.putAll(next);
        }

        private boolean containsServer(long serverId) {
            return byServerId.containsKey(serverId);
        }

        private String serverName(long serverId) {
            McpProjectMcpServerRuntime runtime = byServerId.get(serverId);
            return runtime == null ? null : runtime.serverName();
        }

        private long projectId() {
            return projectId;
        }

        private McpProjectToolSnapshot snapshot() {
            List<ToolDefinition> definitions = new ArrayList<>();
            Map<String, McpProjectToolExecutor> executors = new LinkedHashMap<>();
            for (McpProjectMcpServerRuntime runtime : byServerId.values()) {
                McpProjectToolSnapshot snapshot = runtime.snapshot(projectId);
                for (ToolDefinition definition : snapshot.toolDefinitions()) {
                    if (executors.putIfAbsent(definition.getName(), snapshot.executors().get(definition.getName())) != null) {
                        throw new McpToolCollisionException("MCP tool name collision: " + definition.getName());
                    }
                    definitions.add(definition);
                }
            }
            return new McpProjectToolSnapshot(projectId, definitions, executors);
        }

        private String toolFingerprint() {
            return snapshot().toolDefinitions().stream().map(ToolDefinition::getName).sorted().reduce((left, right) -> left + "\u0000" + right).orElse("");
        }

        private Map<Long, McpRuntimeEvents.ConnectionStatus> connectionStatuses() {
            Map<Long, McpRuntimeEvents.ConnectionStatus> statuses = new LinkedHashMap<>();
            for (Map.Entry<Long, McpProjectMcpServerRuntime> entry : byServerId.entrySet()) {
                statuses.put(entry.getKey(), entry.getValue().status());
            }
            return Map.copyOf(statuses);
        }

        private void close() {
            for (McpProjectMcpServerRuntime runtime : byServerId.values()) {
                runtime.close();
            }
            byServerId.clear();
        }
    }
}

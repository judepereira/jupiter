package com.judepereira.jupiter.ui.balloon;

import com.judepereira.jupiter.agent.mcp.McpProjectMcpServerRuntimeManager;
import com.judepereira.jupiter.agent.mcp.McpRuntimeEvents;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.Persistence.ProjectView;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Log4j2
@Service
@RequiredArgsConstructor
public class McpRuntimeBalloonListener {

    private final AppStateService appStateService;
    private final McpProjectMcpServerRuntimeManager mcpRuntimeManager;
    private final SystemBalloonService systemBalloonService;
    private final Map<ServerKey, McpRuntimeEvents.ConnectionStatus> lastServerStatuses = new ConcurrentHashMap<>();
    private final Map<Long, String> lastProjectToolFingerprints = new ConcurrentHashMap<>();

    @EventListener
    public void onApplicationReady(ApplicationReadyEvent event) {
        try {
            mcpRuntimeManager.initializeVisibleProjects();
        } catch (Exception e) {
            log.error("Failed to initialize MCP runtime for visible projects", e);
        }
    }

    @EventListener
    public void onProjectMcpToolsChanged(McpRuntimeEvents.ProjectMcpToolsChanged event) {
        String fingerprint = toolFingerprint(event.projectId());
        String previousFingerprint = lastProjectToolFingerprints.put(event.projectId(), fingerprint);
        if (systemBalloonService.activeEmitterCount() == 0 || previousFingerprint == null || previousFingerprint.equals(fingerprint)) {
            return;
        }

        String projectName = projectName(event.projectId());
        String title = projectName == null ? "MCP tools updated" : "MCP tools updated: " + projectName;
        String body = projectName == null ? "Project MCP tools changed." : "Project " + projectName + " MCP tools changed.";
        systemBalloonService.publishSuccess(title, body);
    }

    @EventListener
    public void onProjectMcpServerStatusChanged(McpRuntimeEvents.ProjectMcpServerStatusChanged event) {
        ServerKey key = new ServerKey(event.projectId(), event.serverId());
        McpRuntimeEvents.ConnectionStatus previousStatus = lastServerStatuses.put(key, event.status());
        if (systemBalloonService.activeEmitterCount() == 0) {
            return;
        }

        String projectName = projectName(event.projectId());
        String serverName = safeName(event.serverName());
        String subject = projectName == null ? serverName : projectName + " / " + serverName;

        if (event.status() == McpRuntimeEvents.ConnectionStatus.FAILED && previousStatus != McpRuntimeEvents.ConnectionStatus.FAILED) {
            systemBalloonService.publishWarning(
                    subject == null ? "MCP server failed" : "MCP server failed: " + subject,
                    subject == null ? "An MCP server became unavailable." : "Project " + subject + " became unavailable."
            );
            return;
        }

        if (event.status() == McpRuntimeEvents.ConnectionStatus.READY && previousStatus == McpRuntimeEvents.ConnectionStatus.FAILED) {
            systemBalloonService.publishSuccess(
                    subject == null ? "MCP server recovered" : "MCP server recovered: " + subject,
                    subject == null ? "An MCP server is available again." : "Project " + subject + " is available again."
            );
        }
    }

    private String projectName(long projectId) {
        for (ProjectView project : appStateService.loadViewData().projects()) {
            if (project.id() == projectId) {
                return project.name();
            }
        }
        return null;
    }

    private String toolFingerprint(long projectId) {
        return mcpRuntimeManager.snapshot(projectId).toolDefinitions().stream()
                .map(definition -> definition.getName() + "|" + definition.getDescription() + "|" + definition.getSchema())
                .sorted()
                .collect(Collectors.joining("\n"));
    }

    private static String safeName(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record ServerKey(long projectId, long serverId) {
    }
}

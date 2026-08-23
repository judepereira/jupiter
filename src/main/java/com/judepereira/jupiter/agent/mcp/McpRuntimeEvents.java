package com.judepereira.jupiter.agent.mcp;

public final class McpRuntimeEvents {
    private McpRuntimeEvents() {
    }

    public enum ConnectionStatus {
        CONNECTING,
        READY,
        FAILED,
        CLOSED
    }

    public record ProjectMcpRuntimeChanged(long projectId) {
    }

    public record ProjectMcpToolsChanged(long projectId) {
    }

    public record ProjectMcpServerStatusChanged(long projectId, long serverId, String serverName, ConnectionStatus status, String message) {
    }
}

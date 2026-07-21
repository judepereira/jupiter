package com.judepereira.jupiter2.agent.tools;

import com.judepereira.jupiter2.agent.catalog.AgentMode;
import lombok.Getter;

import java.nio.file.Path;

@Getter
public class ToolExecutionContext {
    private final Path workspaceRoot;
    private final boolean allowWrite;
    private final boolean allowCommand;
    private final int commandTimeoutSeconds;
    private final Long sessionId;
    private final String agentId;
    private final AgentMode agentMode;
    private final String toolCallId;

    public ToolExecutionContext(Path workspaceRoot, boolean allowWrite, boolean allowCommand, int commandTimeoutSeconds) {
        this(workspaceRoot, allowWrite, allowCommand, commandTimeoutSeconds, null, null, null, null);
    }

    public ToolExecutionContext(Path workspaceRoot, boolean allowWrite, boolean allowCommand, int commandTimeoutSeconds,
                                Long sessionId, String agentId, AgentMode agentMode, String toolCallId) {
        this.workspaceRoot = workspaceRoot;
        this.allowWrite = allowWrite;
        this.allowCommand = allowCommand;
        this.commandTimeoutSeconds = commandTimeoutSeconds;
        this.sessionId = sessionId;
        this.agentId = agentId;
        this.agentMode = agentMode;
        this.toolCallId = toolCallId;
    }
}

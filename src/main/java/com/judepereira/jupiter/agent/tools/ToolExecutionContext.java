package com.judepereira.jupiter.agent.tools;

import com.judepereira.jupiter.agent.catalog.AgentMode;
import com.judepereira.jupiter.agent.harness.CancellationToken;
import lombok.Getter;

import java.nio.file.Path;
import java.util.Map;

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
    private final Map<String, String> environmentVariables;
    private final ToolProgressSink progressSink;
    private final CancellationToken cancellationToken;

    public ToolExecutionContext(Path workspaceRoot, boolean allowWrite, boolean allowCommand, int commandTimeoutSeconds) {
        this(workspaceRoot, allowWrite, allowCommand, commandTimeoutSeconds, null, null, null, null, Map.of(), ToolProgressSink.noop(), null);
    }

    public ToolExecutionContext(Path workspaceRoot, boolean allowWrite, boolean allowCommand, int commandTimeoutSeconds,
                                Long sessionId, String agentId, AgentMode agentMode, String toolCallId) {
        this(workspaceRoot, allowWrite, allowCommand, commandTimeoutSeconds, sessionId, agentId, agentMode, toolCallId, Map.of(), ToolProgressSink.noop(), null);
    }

    public ToolExecutionContext(Path workspaceRoot, boolean allowWrite, boolean allowCommand, int commandTimeoutSeconds,
                                Long sessionId, String agentId, AgentMode agentMode, String toolCallId, ToolProgressSink progressSink) {
        this(workspaceRoot, allowWrite, allowCommand, commandTimeoutSeconds, sessionId, agentId, agentMode, toolCallId, Map.of(), progressSink, null);
    }

    public ToolExecutionContext(Path workspaceRoot, boolean allowWrite, boolean allowCommand, int commandTimeoutSeconds,
                                Long sessionId, String agentId, AgentMode agentMode, String toolCallId,
                                Map<String, String> environmentVariables, ToolProgressSink progressSink) {
        this(workspaceRoot, allowWrite, allowCommand, commandTimeoutSeconds, sessionId, agentId, agentMode, toolCallId, environmentVariables, progressSink, null);
    }

    public ToolExecutionContext(Path workspaceRoot, boolean allowWrite, boolean allowCommand, int commandTimeoutSeconds,
                                Long sessionId, String agentId, AgentMode agentMode, String toolCallId,
                                Map<String, String> environmentVariables, ToolProgressSink progressSink,
                                CancellationToken cancellationToken) {
        this.workspaceRoot = workspaceRoot;
        this.allowWrite = allowWrite;
        this.allowCommand = allowCommand;
        this.commandTimeoutSeconds = commandTimeoutSeconds;
        this.sessionId = sessionId;
        this.agentId = agentId;
        this.agentMode = agentMode;
        this.toolCallId = toolCallId;
        this.environmentVariables = environmentVariables == null ? Map.of() : Map.copyOf(environmentVariables);
        this.progressSink = progressSink == null ? ToolProgressSink.noop() : progressSink;
        this.cancellationToken = cancellationToken;
    }
}

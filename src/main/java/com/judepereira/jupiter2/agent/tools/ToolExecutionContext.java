package com.judepereira.jupiter2.agent.tools;

import java.nio.file.Path;

public class ToolExecutionContext {
    private final Path workspaceRoot;
    private final boolean allowWrite;
    private final boolean allowCommand;
    private final int commandTimeoutSeconds;

    public ToolExecutionContext(Path workspaceRoot, boolean allowWrite, boolean allowCommand, int commandTimeoutSeconds) {
        this.workspaceRoot = workspaceRoot;
        this.allowWrite = allowWrite;
        this.allowCommand = allowCommand;
        this.commandTimeoutSeconds = commandTimeoutSeconds;
    }

    public Path getWorkspaceRoot() {
        return workspaceRoot;
    }

    public boolean isAllowWrite() {
        return allowWrite;
    }

    public boolean isAllowCommand() {
        return allowCommand;
    }

    public int getCommandTimeoutSeconds() {
        return commandTimeoutSeconds;
    }
}

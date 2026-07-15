package com.judepereira.jupiter2.agent.tools;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.nio.file.Path;

@Getter
@RequiredArgsConstructor
public class ToolExecutionContext {
    private final Path workspaceRoot;
    private final boolean allowWrite;
    private final boolean allowCommand;
    private final int commandTimeoutSeconds;
}

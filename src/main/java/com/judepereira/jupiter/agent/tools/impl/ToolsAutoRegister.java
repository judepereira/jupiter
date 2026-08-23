package com.judepereira.jupiter.agent.tools.impl;

import com.judepereira.jupiter.agent.tools.ToolRegistry;

public class ToolsAutoRegister {
    public static void registerAll(ToolRegistry registry, RunCommandTool runCommandTool) {
        registerAll(registry, runCommandTool, new RipgrepToolSupport());
    }

    public static void registerAll(ToolRegistry registry, RunCommandTool runCommandTool, RipgrepToolSupport ripgrepToolSupport) {
        registry.register(new ListFilesTool(ripgrepToolSupport));
        registry.register(new ReadFileTool());
        registry.register(new SearchCodeTool(ripgrepToolSupport));
        registry.register(new WriteFileTool());
        registry.register(new ApplyPatchTool());
        registry.register(new DisplayImageTool());
        registry.register(runCommandTool);
    }
}

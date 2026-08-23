package com.judepereira.jupiter.agent.tools.impl;

import com.judepereira.jupiter.agent.tools.ToolRegistry;

public class ToolsAutoRegister {
    public static void registerAll(ToolRegistry registry, RunCommandTool runCommandTool) {
        registry.register(new ListFilesTool());
        registry.register(new ReadFileTool());
        registry.register(new SearchCodeTool());
        registry.register(new WriteFileTool());
        registry.register(new ApplyPatchTool());
        registry.register(new DisplayImageTool());
        registry.register(runCommandTool);
    }
}

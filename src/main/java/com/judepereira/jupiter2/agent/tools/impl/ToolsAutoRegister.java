package com.judepereira.jupiter2.agent.tools.impl;

import com.judepereira.jupiter2.agent.tools.ToolRegistry;

public class ToolsAutoRegister {
    public static void registerAll(ToolRegistry registry) {
        registry.register(new ListFilesTool());
        registry.register(new ReadFileTool());
        registry.register(new SearchCodeTool());
        registry.register(new WriteFileTool());
        registry.register(new ApplyPatchTool());
        registry.register(new RunCommandTool());
    }
}

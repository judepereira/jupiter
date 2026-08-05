package com.judepereira.jupiter.agent.config;

import com.judepereira.jupiter.agent.tools.ToolRegistry;
import com.judepereira.jupiter.agent.tools.impl.TaskTool;
import com.judepereira.jupiter.agent.tools.impl.ToolsAutoRegister;
import com.judepereira.jupiter.agent.catalog.AgentDefinitionService;
import com.judepereira.jupiter.agent.task.SubagentTaskService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ToolingConfig {

    @Bean
    public TaskTool taskTool(AgentDefinitionService agentDefinitionService, SubagentTaskService subagentTaskService) {
        return new TaskTool(agentDefinitionService, subagentTaskService);
    }

    @Bean
    public ToolRegistry toolRegistry(TaskTool taskTool) {
        ToolRegistry registry = new ToolRegistry();
        ToolsAutoRegister.registerAll(registry);
        registry.register(taskTool);
        return registry;
    }
}

package com.judepereira.jupiter2.agent.config;

import com.judepereira.jupiter2.agent.tools.ToolRegistry;
import com.judepereira.jupiter2.agent.tools.impl.ToolsAutoRegister;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ToolingConfig {

    @Bean
    public ToolRegistry toolRegistry() {
        ToolRegistry registry = new ToolRegistry();
        ToolsAutoRegister.registerAll(registry);
        return registry;
    }
}

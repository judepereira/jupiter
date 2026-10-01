package com.judepereira.jupiter.agent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({AgentProperties.class, OpenAiProperties.class, OpenAiOAuthProperties.class,
        AnthropicProperties.class, ModelCatalogProperties.class})
public class AgentConfig {
}

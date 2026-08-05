package com.judepereira.jupiter.agent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@Configuration
@EnableConfigurationProperties({AgentProperties.class, OpenAiProperties.class, OpenAiOAuthProperties.class, ModelCatalogProperties.class})
public class AgentConfig {
}

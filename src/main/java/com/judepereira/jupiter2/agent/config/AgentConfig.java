package com.judepereira.jupiter2.agent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@Configuration
@EnableConfigurationProperties({AgentProperties.class, OpenAiProperties.class})
public class AgentConfig {
}

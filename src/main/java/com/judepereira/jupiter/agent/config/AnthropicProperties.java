package com.judepereira.jupiter.agent.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "anthropic")
public class AnthropicProperties {
    private String baseUrl = "https://api.anthropic.com/v1/messages";
    private String version = "2023-06-01";
    private String beta = "oauth-2025-04-20";
    private int maxOutputTokens = 8192;
    private Duration requestTimeout = Duration.ofSeconds(120);
}

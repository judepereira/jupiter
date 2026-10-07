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
    private String beta;
    private String apiKey;
    private int maxOutputTokens = 8192;

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String effectiveApiKey() {
        return hasApiKey() ? apiKey.trim() : null;
    }

    /** Compatibility accessor; does not mutate the configured value. */
    public String trimmedApiKey() {
        return effectiveApiKey();
    }

    private Duration requestTimeout = Duration.ofSeconds(120);
}

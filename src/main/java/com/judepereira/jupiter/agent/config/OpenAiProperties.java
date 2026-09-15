package com.judepereira.jupiter.agent.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Setter
@Getter
@ConfigurationProperties(prefix = "openai")
public class OpenAiProperties {

    private String apiKey;
    private Retry retry = new Retry();

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

    @Getter
    @Setter
    public static class Retry {
        private int maxRetries = 10;
        private Duration initialBackoff = Duration.ofSeconds(1);
        private Duration maxBackoff = Duration.ofSeconds(120);
    }

}

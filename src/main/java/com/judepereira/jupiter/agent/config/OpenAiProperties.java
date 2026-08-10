package com.judepereira.jupiter.agent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Setter
@Getter
@ConfigurationProperties(prefix = "openai")
public class OpenAiProperties {

    private String apiKey;
    private Retry retry = new Retry();

    @Getter
    @Setter
    public static class Retry {
        private int maxRetries = 10;
        private Duration initialBackoff = Duration.ofSeconds(1);
        private Duration maxBackoff = Duration.ofSeconds(120);
    }

}

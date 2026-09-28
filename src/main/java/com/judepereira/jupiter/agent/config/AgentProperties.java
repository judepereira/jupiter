package com.judepereira.jupiter.agent.config;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties(prefix = "agent")
@Data
public class AgentProperties {

    /** provider name, e.g. "openai" */
    private String provider = "openai";
    private String model = "gpt-5.4";
    private int maxIterations = 1_000;
    private int commandTimeoutSeconds = 600;
    private String workspaceRoot = ".";
    private Tooling tooling = new Tooling();
    private Retry retry = new Retry();

    @Data
    public static class Retry {
        private int maxRetries = 10;
        private Duration initialBackoff = Duration.ofSeconds(1);
        private Duration maxBackoff = Duration.ofSeconds(120);
    }

    @Data
    public static class Tooling {
        private boolean allowWrite = false;
        private boolean allowCommand = false;
    }
}

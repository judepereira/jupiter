package com.judepereira.jupiter2.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties(prefix = "agent")
@Data
public class AgentProperties {

    /** provider name, e.g. "openai" */
    private String provider = "openai";
    private String model = "gpt-5.4";
    private int maxIterations = 100;
    private int commandTimeoutSeconds = 60;
    private String workspaceRoot = ".";
    private Tooling tooling = new Tooling();

    @Data
    public static class Tooling {
        private boolean allowWrite = false;
        private boolean allowCommand = false;
    }
}

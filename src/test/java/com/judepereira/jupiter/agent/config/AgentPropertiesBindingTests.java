package com.judepereira.jupiter.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class AgentPropertiesBindingTests {

    @Test
    void bindsSharedRetryProperties() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of("agent.retry.max-retries", "4",
                "agent.retry.initial-backoff", "250ms", "agent.retry.max-backoff", "30s")));

        AgentProperties properties = binder.bind("agent", AgentProperties.class)
                .orElseThrow(() -> new AssertionError("agent properties did not bind"));

        assertThat(properties.getRetry().getMaxRetries()).isEqualTo(4);
        assertThat(properties.getRetry().getInitialBackoff()).isEqualTo(Duration.ofMillis(250));
        assertThat(properties.getRetry().getMaxBackoff()).isEqualTo(Duration.ofSeconds(30));
    }
}

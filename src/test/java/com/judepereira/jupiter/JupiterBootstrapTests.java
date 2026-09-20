package com.judepereira.jupiter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.judepereira.jupiter.security.BootstrapConfiguration;
import com.judepereira.jupiter.security.BootstrapConfigurationReader;
import com.judepereira.jupiter.security.EncryptionKey;
import com.judepereira.jupiter.security.RuntimeEnvironment;
import com.judepereira.jupiter.testsupport.TestEncryptionSupport;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.MapPropertySource;

class JupiterBootstrapTests {
    private static final String KEY = TestEncryptionSupport.KEY;

    @Test
    void hardensBeforeReadingBootstrap() {
        List<String> events = new ArrayList<>();
        var input = new ByteArrayInputStream(envelope().getBytes(StandardCharsets.UTF_8)) {
            @Override
            public int read() {
                events.add("read");
                return super.read();
            }
        };
        assertThatThrownBy(() -> Jupiter.bootstrapConfiguration(input, () -> {
            events.add("harden");
            throw new IllegalStateException("hardening failed");
        }, BootstrapConfigurationReader::read, ignored -> events.add("start"))).hasMessage("hardening failed");
        assertThat(events).containsExactly("harden");
    }

    @Test
    void registersSeparatePropertySourceAndRuntimeBeanWithRelaxedMapping() {
        var runtime = new RuntimeEnvironment(Map.of("JUPITER_HTTP_AUTH_PASSWORD", "bootstrap-value"));
        var configuration = new BootstrapConfiguration(EncryptionKey.fromBase64(KEY), runtime);
        try (var context = new AnnotationConfigApplicationContext()) {
            var environment = Jupiter.environment(configuration);
            environment.getPropertySources().addFirst(
                    new MapPropertySource("commandLineArgs", Map.of("jupiter.http-auth.password", "command-line")));
            assertThat(environment.getPropertySources().contains("jupiterBootstrapEnvironment")).isTrue();
            assertThat(environment.getProperty("jupiter.http-auth.password")).isEqualTo("command-line");
            Jupiter.application(configuration).getInitializers()
                    .forEach(initializer -> ((ApplicationContextInitializer) initializer).initialize(context));
            assertThat(context.getBeanFactory().getSingleton("runtimeEnvironment")).isSameAs(runtime);
            assertThat(context.getEnvironment().getPropertySources().contains("runtimeEnvironment")).isFalse();
            assertThat(System.getProperties()).doesNotContainKey("JUPITER_HTTP_AUTH_PASSWORD");
        }
    }

    @Test
    void emptyRuntimeEnvironmentBeanAllowsDirectContext() {
        try (var context = new AnnotationConfigApplicationContext(Jupiter.class)) {
            assertThat(context.getBean(RuntimeEnvironment.class).asMap()).isEmpty();
        }
    }

    @Test
    void springApplicationUsesBootstrapProfileDuringStartup() {
        var runtime = new RuntimeEnvironment(
                Map.of("SPRING_PROFILES_ACTIVE", "bootstrap", "SPRING_MAIN_WEB_APPLICATION_TYPE", "none"));
        var configuration = new BootstrapConfiguration(EncryptionKey.fromBase64(KEY), runtime);

        try (var context = Jupiter.application(configuration, StartupProbeConfiguration.class).run()) {
            assertThat(context.getEnvironment().getActiveProfiles()).contains("bootstrap");
            assertThat(context.getBean(String.class)).isEqualTo("bootstrap");
            assertThat(context).isInstanceOf(AnnotationConfigApplicationContext.class);
        }
    }

    @Test
    void commandLineProfileOverridesBootstrapDuringStartup() {
        var runtime = new RuntimeEnvironment(
                Map.of("SPRING_PROFILES_ACTIVE", "bootstrap", "SPRING_MAIN_WEB_APPLICATION_TYPE", "none"));
        var configuration = new BootstrapConfiguration(EncryptionKey.fromBase64(KEY), runtime);

        try (var context = Jupiter.application(configuration, StartupProbeConfiguration.class)
                .run("--spring.profiles.active=command-line")) {
            assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("command-line");
            assertThat(context.getBean(String.class)).isEqualTo("command-line");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class StartupProbeConfiguration {
        @Bean
        StartupProbeConfiguration startupProbeConfiguration() {
            return this;
        }

        @Bean
        @Profile("bootstrap")
        String bootstrapProfileBean() {
            return "bootstrap";
        }

        @Bean
        @Profile("command-line")
        String commandLineProfileBean() {
            return "command-line";
        }

    }

    @Test
    void readsBootstrapAfterHardening() {
        List<String> events = new ArrayList<>();
        Jupiter.bootstrapConfiguration(new ByteArrayInputStream(envelope().getBytes(StandardCharsets.UTF_8)),
                () -> events.add("harden"), input -> {
                    events.add("read");
                    return BootstrapConfigurationReader.read(input);
                }, configuration -> {
                    assertThat(configuration.runtimeEnvironment().get("BOOTSTRAP_ONLY")).isEqualTo("value");
                    events.add("start");
                });
        assertThat(events).containsExactly("harden", "read", "start");
    }

    private static String envelope() {
        return "JUPITER_ENCRYPTION_KEY=" + KEY + "\nBOOTSTRAP_ONLY=value\n";
    }
}

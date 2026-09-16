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
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("commandLineArgs", Map.of("jupiter.http-auth.password", "command-line")));
            Jupiter.application(configuration).getInitializers()
                    .forEach(initializer -> ((ApplicationContextInitializer) initializer).initialize(context));
            assertThat(context.getEnvironment().getPropertySources().contains("jupiterBootstrapEnvironment")).isTrue();
            assertThat(context.getEnvironment().getProperty("jupiter.http-auth.password")).isEqualTo("command-line");
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
        return "JUPITER_BOOTSTRAP_V1\0JUPITER_ENCRYPTION_KEY\0" + KEY + "\0BOOTSTRAP_ONLY\0value\0";
    }
}

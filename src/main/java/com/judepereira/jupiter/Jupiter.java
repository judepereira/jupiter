package com.judepereira.jupiter;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.tools.impl.RipgrepToolSupport;
import com.judepereira.jupiter.security.BootstrapConfiguration;
import com.judepereira.jupiter.security.BootstrapConfigurationReader;
import com.judepereira.jupiter.security.LinuxProcessHardening;
import com.judepereira.jupiter.security.RuntimeEnvironment;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class Jupiter {
    private static final String ENCRYPTION_KEY_BEAN_NAME = "jupiterEncryptionKey";
    private static final String RUNTIME_ENVIRONMENT_BEAN_NAME = "runtimeEnvironment";
    private static final String BOOTSTRAP_PROPERTY_SOURCE_NAME = "jupiterBootstrapEnvironment";

    @org.springframework.context.annotation.Bean
    @ConditionalOnMissingBean(RuntimeEnvironment.class)
    RuntimeEnvironment runtimeEnvironment() {
        return new RuntimeEnvironment(Map.of());
    }

    @org.springframework.context.annotation.Bean
    ObjectMapper objectMapper() {
        JsonFactory jsonFactory = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder().maxStringLength(100_000_000).build()).build();
        return new ObjectMapper(jsonFactory);
    }

    @org.springframework.context.annotation.Bean
    RipgrepToolSupport ripgrepToolSupport() {
        RipgrepToolSupport support = new RipgrepToolSupport();
        support.assertAvailable();
        return support;
    }

    public static void main(String[] args) {
        bootstrapConfiguration(System.in, LinuxProcessHardening::enforce, BootstrapConfigurationReader::read,
                configuration -> application(configuration).run(args));
    }

    static void bootstrapConfiguration(InputStream input, Runnable harden,
            Function<InputStream, BootstrapConfiguration> reader, Consumer<BootstrapConfiguration> startApplication) {
        harden.run();
        startApplication.accept(reader.apply(input));
    }

    static SpringApplication application(BootstrapConfiguration configuration) {
        SpringApplication application = new SpringApplication(Jupiter.class);
        ApplicationContextInitializer<ConfigurableApplicationContext> initializer = context -> {
            context.getBeanFactory().registerSingleton(ENCRYPTION_KEY_BEAN_NAME, configuration.encryptionKey());
            context.getBeanFactory().registerSingleton(RUNTIME_ENVIRONMENT_BEAN_NAME,
                    configuration.runtimeEnvironment());
            Map<String, Object> environment = new HashMap<>();
            environment.putAll(configuration.runtimeEnvironment().asMap());
            var propertySources = context.getEnvironment().getPropertySources();
            SystemEnvironmentPropertySource source = new SystemEnvironmentPropertySource(BOOTSTRAP_PROPERTY_SOURCE_NAME,
                    environment);
            if (propertySources.contains("systemEnvironment")) {
                propertySources.addAfter("systemEnvironment", source);
            } else {
                propertySources.addLast(source);
            }
        };
        application.addInitializers(initializer);
        return application;
    }

}

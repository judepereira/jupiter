package com.judepereira.jupiter;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.tools.impl.RipgrepToolSupport;
import com.judepereira.jupiter.security.BootstrapConfiguration;
import com.judepereira.jupiter.security.BootstrapConfigurationReader;
import com.judepereira.jupiter.security.LinuxProcessHardening;
import com.judepereira.jupiter.security.RuntimeEnvironment;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

@Log4j2
@SpringBootApplication
@EnableScheduling
public class Jupiter {
    private static final String ENCRYPTION_KEY_BEAN_NAME = "jupiterEncryptionKey";
    private static final String RUNTIME_ENVIRONMENT_BEAN_NAME = "runtimeEnvironment";
    private static final String BOOTSTRAP_PROPERTY_SOURCE_NAME = "jupiterBootstrapEnvironment";

    @Bean
    @ConditionalOnMissingBean(RuntimeEnvironment.class)
    RuntimeEnvironment runtimeEnvironment() {
        return new RuntimeEnvironment(Map.of());
    }

    @Bean
    ObjectMapper objectMapper() {
        JsonFactory jsonFactory = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder().maxStringLength(100_000_000).build()).build();
        return new ObjectMapper(jsonFactory);
    }

    @Bean
    RipgrepToolSupport ripgrepToolSupport() {
        RipgrepToolSupport support = new RipgrepToolSupport();
        support.assertAvailable();
        return support;
    }

    @SneakyThrows
    public static void main(String[] args) {
        final InputStream in;
        if ("1".equals(System.getenv("JUPITER_READ_CONFIG_FROM_ENV"))) {
            log.warn("Reading sensitive vars from env! Use this during development ONLY.");
            in = new FileInputStream(".env");
        } else {
            in = System.in;
        }
        bootstrapConfiguration(in, LinuxProcessHardening::enforce, BootstrapConfigurationReader::read,
                configuration -> application(configuration).run(args));
    }

    static void bootstrapConfiguration(InputStream input, Runnable harden,
                                       Function<InputStream, BootstrapConfiguration> reader, Consumer<BootstrapConfiguration> startApplication) {
        harden.run();
        startApplication.accept(reader.apply(input));
    }

    static SpringApplication application(BootstrapConfiguration configuration) {
        return application(configuration, Jupiter.class);
    }

    static SpringApplication application(BootstrapConfiguration configuration, Class<?>... sources) {
        SpringApplication application = new SpringApplication(sources);
        application.setEnvironment(environment(configuration));
        ApplicationContextInitializer<ConfigurableApplicationContext> initializer = context -> {
            context.getBeanFactory().registerSingleton(ENCRYPTION_KEY_BEAN_NAME, configuration.encryptionKey());
            context.getBeanFactory().registerSingleton(RUNTIME_ENVIRONMENT_BEAN_NAME,
                    configuration.runtimeEnvironment());
        };
        application.addInitializers(initializer);
        return application;
    }

    static ConfigurableEnvironment environment(BootstrapConfiguration configuration) {
        ConfigurableEnvironment environment = new StandardEnvironment();
        Map<String, Object> bootstrapValues = new HashMap<>();
        bootstrapValues.putAll(configuration.runtimeEnvironment().asMap());
        SystemEnvironmentPropertySource bootstrapSource = new SystemEnvironmentPropertySource(
                BOOTSTRAP_PROPERTY_SOURCE_NAME, bootstrapValues);
        var propertySources = environment.getPropertySources();
        propertySources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, bootstrapSource);
        return environment;
    }

}

package com.judepereira.jupiter;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.tools.impl.RipgrepToolSupport;
import com.judepereira.jupiter.security.EncryptionKey;
import com.judepereira.jupiter.security.EncryptionKeyBootstrapReader;
import com.judepereira.jupiter.security.LinuxProcessHardening;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.InputStream;
import java.util.function.Consumer;
import java.util.function.Function;

@SpringBootApplication
@EnableScheduling
public class Jupiter {

    private static final String ENCRYPTION_KEY_BEAN_NAME = "jupiterEncryptionKey";

    @Bean
    ObjectMapper objectMapper() {
        JsonFactory jsonFactory = JsonFactory.builder()
                .streamReadConstraints(
                        StreamReadConstraints.builder()
                                .maxStringLength(100_000_000)
                                .build())
                .build();

        return new ObjectMapper(jsonFactory);
    }

    @Bean
    RipgrepToolSupport ripgrepToolSupport() {
        RipgrepToolSupport support = new RipgrepToolSupport();
        support.assertAvailable();
        return support;
    }

    public static void main(String[] args) {
        bootstrap(System.in, LinuxProcessHardening::enforce, EncryptionKeyBootstrapReader::read,
                key -> application(key).run(args));
    }

    static void bootstrap(InputStream input, Runnable harden, Function<InputStream, EncryptionKey> keyReader,
            Consumer<EncryptionKey> startApplication) {
        harden.run();
        EncryptionKey key = keyReader.apply(input);
        startApplication.accept(key);
    }

    static SpringApplication application(EncryptionKey key) {
        SpringApplication application = new SpringApplication(Jupiter.class);
        ApplicationContextInitializer<ConfigurableApplicationContext> initializer = context ->
                context.getBeanFactory().registerSingleton(ENCRYPTION_KEY_BEAN_NAME, key);
        application.addInitializers(initializer);
        return application;
    }

}

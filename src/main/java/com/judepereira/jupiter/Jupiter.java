package com.judepereira.jupiter;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.tools.impl.RipgrepToolSupport;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class Jupiter {

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
        SpringApplication.run(Jupiter.class, args);
    }

}

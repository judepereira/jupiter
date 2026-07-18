package com.judepereira.jupiter2;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class JupiterV2Application {

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    public static void main(String[] args) {
        SpringApplication.run(JupiterV2Application.class, args);
    }

}

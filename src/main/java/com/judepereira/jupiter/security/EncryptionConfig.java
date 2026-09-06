package com.judepereira.jupiter.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
class EncryptionConfig {
    @Bean
    EncryptionKey encryptionKey(Environment environment) {
        return EncryptionKey.fromBase64(environment.getProperty("JUPITER_ENCRYPTION_KEY"));
    }
}

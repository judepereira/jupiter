package com.judepereira.jupiter.testsupport;

import com.judepereira.jupiter.security.EncryptionKey;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration
public class TestEncryptionConfiguration {
    @Bean
    EncryptionKey encryptionKey() {
        return TestEncryptionSupport.encryptionKey();
    }
}

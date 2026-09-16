package com.judepereira.jupiter.security;

public record BootstrapConfiguration(EncryptionKey encryptionKey, RuntimeEnvironment runtimeEnvironment) {
    public BootstrapConfiguration {
        if (encryptionKey == null || runtimeEnvironment == null) {
            throw new IllegalArgumentException("bootstrap configuration is incomplete");
        }
    }
}

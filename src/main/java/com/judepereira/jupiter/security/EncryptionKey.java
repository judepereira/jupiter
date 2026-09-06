package com.judepereira.jupiter.security;

import java.util.Base64;

public record EncryptionKey(byte[] bytes) {
    public EncryptionKey {
        if (bytes == null || bytes.length != 32) {
            throw new IllegalArgumentException("JUPITER_ENCRYPTION_KEY must decode to exactly 32 bytes");
        }
        bytes = bytes.clone();
    }

    public static EncryptionKey fromBase64(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("JUPITER_ENCRYPTION_KEY is required");
        }
        try {
            return new EncryptionKey(Base64.getDecoder().decode(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("JUPITER_ENCRYPTION_KEY must be standard Base64", e);
        }
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}

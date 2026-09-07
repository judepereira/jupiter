package com.judepereira.jupiter.security;

import java.util.Base64;

public record EncryptionKey(byte[] bytes) {
    public EncryptionKey {
        if (bytes == null || bytes.length != 32) {
            throw new IllegalArgumentException("encryption key must be exactly 32 bytes");
        }
        bytes = bytes.clone();
    }

    public static EncryptionKey fromBase64(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("encryption key is required");
        }
        byte[] decoded = null;
        try {
            decoded = Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("encryption key must be standard Base64", e);
        }
        try {
            return new EncryptionKey(decoded);
        } finally {
            java.util.Arrays.fill(decoded, (byte) 0);
        }
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}

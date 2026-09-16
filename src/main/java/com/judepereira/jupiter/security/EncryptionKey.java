package com.judepereira.jupiter.security;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
        byte[] encoded = value.getBytes(StandardCharsets.US_ASCII);
        try {
            return fromBase64(encoded);
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    public static EncryptionKey fromBase64(byte[] value) {
        if (value == null || value.length == 0 || isBlank(value)) {
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
            Arrays.fill(decoded, (byte) 0);
        }
    }

    private static boolean isBlank(byte[] value) {
        for (byte character : value) {
            if (character != ' ' && character != '\t' && character != '\n' && character != '\r' && character != '\f')
                return false;
        }
        return true;
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}

package com.judepereira.jupiter.security;

import lombok.SneakyThrows;
import lombok.val;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public final class BootstrapConfigurationReader {
    private static final String KEY_NAME = "JUPITER_ENCRYPTION_KEY";

    private BootstrapConfigurationReader() {
    }

    @SneakyThrows
    public static BootstrapConfiguration read(InputStream input) {
        byte[] keyBytes = null;
        try(BufferedReader br = new BufferedReader(new InputStreamReader(input))) {
            Map<String, String> values = new HashMap<>();

            for (String line : br.lines().toList()) {
                val i = line.indexOf("=");
                if (i == -1) {
                    continue;
                }
                val key = line.substring(0, i);
                val value = line.substring(i + 1);
                if (key.equals(KEY_NAME)) {
                    if (keyBytes != null) {
                        throw failure("duplicate encryption key variable");
                    }
                    keyBytes = value.getBytes();
                } else if (values.put(key, value) != null) {
                    throw failure("duplicate bootstrap variable");
                }
            }
            if (keyBytes == null || keyBytes.length == 0) {
                throw failure("encryption key is missing");
            }
            try {
                return new BootstrapConfiguration(EncryptionKey.fromBase64(keyBytes), new RuntimeEnvironment(values));
            } catch (RuntimeException exception) {
                throw failure("encryption key is invalid");
            }
        } finally {
            if (keyBytes != null) {
                Arrays.fill(keyBytes, (byte) 0);
            }
        }
    }

    private static IllegalStateException failure(String message) {
        return new IllegalStateException(message);
    }
}

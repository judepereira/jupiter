package com.judepereira.jupiter.security;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public final class BootstrapConfigurationReader {
    private static final byte[] PREFIX = "JUPITER_BOOTSTRAP_V1\0".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_PAYLOAD = 4 * 1024 * 1024;
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final String KEY_NAME = "JUPITER_ENCRYPTION_KEY";

    private BootstrapConfigurationReader() {
    }

    public static BootstrapConfiguration read(InputStream input) {
        WipeableByteAccumulator payload = readAll(input);
        byte[] keyBytes = null;
        try {
            byte[] bytes = payload.bytes();
            if (payload.size() < PREFIX.length || !startsWith(bytes, PREFIX)) {
                throw failure("invalid bootstrap envelope");
            }
            Map<String, String> values = new HashMap<>();
            int position = PREFIX.length;
            while (position < payload.size()) {
                int nameEnd = nul(bytes, position, payload.size());
                if (nameEnd < 0)
                    throw failure("truncated bootstrap envelope");
                String name = decodeUtf8(bytes, position, nameEnd - position);
                if (!NAME.matcher(name).matches())
                    throw failure("invalid bootstrap variable name");
                int valueStart = nameEnd + 1;
                int valueEnd = nul(bytes, valueStart, payload.size());
                if (valueEnd < 0)
                    throw failure("truncated bootstrap envelope");
                if (name.equals(KEY_NAME)) {
                    if (keyBytes != null)
                        throw failure("duplicate bootstrap variable");
                    keyBytes = Arrays.copyOfRange(bytes, valueStart, valueEnd);
                } else if (values.put(name, decodeUtf8(bytes, valueStart, valueEnd - valueStart)) != null) {
                    throw failure("duplicate bootstrap variable");
                }
                position = valueEnd + 1;
            }
            if (keyBytes == null || isBlank(keyBytes))
                throw failure("encryption key is missing");
            try {
                return new BootstrapConfiguration(EncryptionKey.fromBase64(keyBytes), new RuntimeEnvironment(values));
            } catch (RuntimeException exception) {
                throw failure("encryption key is invalid");
            }
        } finally {
            if (keyBytes != null)
                Arrays.fill(keyBytes, (byte) 0);
            payload.wipe();
        }
    }

    private static WipeableByteAccumulator readAll(InputStream input) {
        WipeableByteAccumulator output = new WipeableByteAccumulator();
        byte[] buffer = new byte[8192];
        try {
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count == 0)
                    continue;
                output.append(buffer, count);
            }
            return output;
        } catch (IOException exception) {
            output.wipe();
            throw new IllegalStateException("could not read bootstrap envelope", exception);
        } catch (RuntimeException exception) {
            output.wipe();
            throw exception;
        } finally {
            Arrays.fill(buffer, (byte) 0);
        }
    }

    private static String decodeUtf8(byte[] bytes, int offset, int length) {
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder();
            decoder.onMalformedInput(CodingErrorAction.REPORT);
            decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(ByteBuffer.wrap(bytes, offset, length)).toString();
        } catch (CharacterCodingException exception) {
            throw failure("invalid UTF-8 in bootstrap envelope");
        }
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        for (int i = 0; i < prefix.length; i++)
            if (bytes[i] != prefix[i])
                return false;
        return true;
    }

    private static int nul(byte[] bytes, int start, int limit) {
        for (int i = start; i < limit; i++)
            if (bytes[i] == 0)
                return i;
        return -1;
    }

    private static boolean isBlank(byte[] bytes) {
        if (bytes.length == 0)
            return true;
        for (byte value : bytes) {
            if (value != ' ' && value != '\t' && value != '\n' && value != '\r' && value != '\f')
                return false;
        }
        return true;
    }

    private static IllegalStateException failure(String message) {
        return new IllegalStateException(message);
    }

    private static final class WipeableByteAccumulator {
        private byte[] bytes = new byte[8192];
        private int size;

        void append(byte[] source, int length) {
            if (length > MAX_PAYLOAD - size)
                throw failure("bootstrap envelope is excessive");
            ensureCapacity(size + length);
            System.arraycopy(source, 0, bytes, size, length);
            size += length;
        }

        private void ensureCapacity(int required) {
            if (required <= bytes.length)
                return;
            int capacity = Math.min(MAX_PAYLOAD, Math.max(required, bytes.length * 2));
            byte[] replacement = Arrays.copyOf(bytes, capacity);
            Arrays.fill(bytes, (byte) 0);
            bytes = replacement;
        }

        byte[] bytes() {
            return bytes;
        }

        int size() {
            return size;
        }

        void wipe() {
            Arrays.fill(bytes, (byte) 0);
            size = 0;
        }
    }
}

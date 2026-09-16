package com.judepereira.jupiter.security;

import java.io.ByteArrayOutputStream;
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
        byte[] payload = readAll(input);
        try {
            if (payload.length < PREFIX.length || !Arrays.equals(PREFIX, Arrays.copyOf(payload, PREFIX.length))) {
                throw failure("invalid bootstrap envelope");
            }
            Map<String, String> values = new HashMap<>();
            int position = PREFIX.length;
            while (position < payload.length) {
                int nameEnd = nul(payload, position);
                if (nameEnd < 0)
                    throw failure("truncated bootstrap envelope");
                String name = decodeUtf8(payload, position, nameEnd - position);
                if (!NAME.matcher(name).matches())
                    throw failure("invalid bootstrap variable name");
                int valueStart = nameEnd + 1;
                int valueEnd = nul(payload, valueStart);
                if (valueEnd < 0)
                    throw failure("truncated bootstrap envelope");
                if (values.put(name, decodeUtf8(payload, valueStart, valueEnd - valueStart)) != null) {
                    throw failure("duplicate bootstrap variable");
                }
                position = valueEnd + 1;
            }
            String encodedKey = values.remove(KEY_NAME);
            if (encodedKey == null || encodedKey.isBlank())
                throw failure("encryption key is missing");
            EncryptionKey key;
            try {
                key = EncryptionKey.fromBase64(encodedKey);
            } catch (RuntimeException exception) {
                throw failure("encryption key is invalid");
            }
            return new BootstrapConfiguration(key, new RuntimeEnvironment(values));
        } finally {
            Arrays.fill(payload, (byte) 0);
        }
    }

    private static byte[] readAll(InputStream input) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() > MAX_PAYLOAD - count)
                    throw failure("bootstrap envelope is excessive");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("could not read bootstrap envelope", exception);
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

    private static int nul(byte[] bytes, int start) {
        for (int i = start; i < bytes.length; i++)
            if (bytes[i] == 0)
                return i;
        return -1;
    }

    private static IllegalStateException failure(String message) {
        return new IllegalStateException(message);
    }
}

package com.judepereira.jupiter.security;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Base64;

/** Reads the one-shot encryption key from stdin until the caller closes the channel (EOF). */
public final class EncryptionKeyBootstrapReader {
    private static final int ENCODED_KEY_LENGTH = 44;
    private static final int MAX_INPUT_LENGTH = 128;

    private EncryptionKeyBootstrapReader() {
    }

    public static EncryptionKey read(InputStream input) {
        byte[] inputBuffer = new byte[MAX_INPUT_LENGTH + 1];
        byte[] encoded = new byte[MAX_INPUT_LENGTH];
        int inputLength = 0;
        try {
            int value;
            while ((value = input.read()) != -1) {
                if (inputLength == inputBuffer.length) {
                    throw new IllegalStateException("stdin encryption key is excessive");
                }
                inputBuffer[inputLength++] = (byte) value;
            }

            int encodedLength = copyTrimmedAscii(inputBuffer, inputLength, encoded);
            if (encodedLength == 0) {
                throw new IllegalStateException("stdin encryption key is missing");
            }
            if (encodedLength > ENCODED_KEY_LENGTH) {
                throw new IllegalStateException("stdin encryption key has trailing content");
            }

            byte[] base64Input = Arrays.copyOf(encoded, encodedLength);
            byte[] decoded = null;
            try {
                try {
                    decoded = Base64.getDecoder().decode(base64Input);
                } catch (IllegalArgumentException exception) {
                    throw new IllegalStateException("stdin encryption key is invalid Base64", exception);
                }
                if (decoded.length != 32) {
                    throw new IllegalStateException("stdin encryption key must decode to exactly 32 bytes");
                }
                return new EncryptionKey(decoded);
            } finally {
                Arrays.fill(base64Input, (byte) 0);
                if (decoded != null) {
                    Arrays.fill(decoded, (byte) 0);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("could not read encryption key from stdin", exception);
        } finally {
            Arrays.fill(inputBuffer, (byte) 0);
            Arrays.fill(encoded, (byte) 0);
        }
    }

    private static int copyTrimmedAscii(byte[] input, int length, byte[] output) {
        int start = 0;
        while (start < length && isAsciiWhitespace(input[start])) {
            start++;
        }
        int end = length;
        while (end > start && isAsciiWhitespace(input[end - 1])) {
            end--;
        }
        int contentLength = end - start;
        if (contentLength > output.length) {
            throw new IllegalStateException("stdin encryption key is excessive");
        }
        for (int index = start; index < end; index++) {
            if (isAsciiWhitespace(input[index])) {
                throw new IllegalStateException("stdin encryption key has interior whitespace");
            }
            output[index - start] = input[index];
        }
        return contentLength;
    }

    private static boolean isAsciiWhitespace(byte value) {
        return value >= 0x09 && value <= 0x0d || value == 0x20;
    }
}

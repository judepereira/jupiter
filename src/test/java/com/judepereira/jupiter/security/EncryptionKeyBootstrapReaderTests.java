package com.judepereira.jupiter.security;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncryptionKeyBootstrapReaderTests {
    private static final String KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    @Test
    void readsKeyWithEofAndSurroundingWhitespace() {
        assertThat(EncryptionKeyBootstrapReader.read(bytes(KEY)).bytes()).containsExactly(0, 1, 2, 3, 4, 5, 6, 7,
                8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31);
        assertThat(EncryptionKeyBootstrapReader.read(bytes(" \t" + KEY + "\r\n"))).isNotNull();
    }

    @Test
    void rejectsMissingInvalidWrongLengthAndTrailingInput() {
        assertThatThrownBy(() -> EncryptionKeyBootstrapReader.read(bytes(""))).hasMessageContaining("missing");
        assertThatThrownBy(() -> EncryptionKeyBootstrapReader.read(bytes("not-base64"))).hasMessageContaining("invalid Base64");
        assertThatThrownBy(() -> EncryptionKeyBootstrapReader.read(bytes("AQ=="))).hasMessageContaining("exactly 32 bytes");
        assertThatThrownBy(() -> EncryptionKeyBootstrapReader.read(bytes(KEY + "x"))).hasMessageContaining("trailing");
        assertThatThrownBy(() -> EncryptionKeyBootstrapReader.read(bytes(KEY + "\nextra"))).hasMessageContaining("interior whitespace");
        assertThatThrownBy(() -> EncryptionKeyBootstrapReader.read(bytes("x".repeat(129))))
                .hasMessageContaining("excessive");
    }

    @Test
    void doesNotCloseCallerStream() {
        TrackingInputStream input = new TrackingInputStream(KEY.getBytes(StandardCharsets.US_ASCII));
        EncryptionKeyBootstrapReader.read(input);
        assertThat(input.closed).isFalse();
    }

    private static ByteArrayInputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static final class TrackingInputStream extends InputStream {
        private final ByteArrayInputStream delegate;
        private boolean closed;

        private TrackingInputStream(byte[] bytes) {
            delegate = new ByteArrayInputStream(bytes);
        }

        @Override
        public int read() throws IOException {
            return delegate.read();
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}

package com.judepereira.jupiter.security;

import com.judepereira.jupiter.testsupport.TestEncryptionSupport;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncryptionKeyBootstrapReaderTests {
    private static final String KEY = TestEncryptionSupport.KEY;

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

package com.judepereira.jupiter.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.judepereira.jupiter.testsupport.TestEncryptionSupport;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BootstrapConfigurationReaderTests {
    private static final String KEY = TestEncryptionSupport.KEY;
    private static final String SECRET = "reader-secret-value";

    @Test
    void readsValuesAndProtectsTheCapturedMap() {
        var configuration = read(envelope(entry("JUPITER_ENCRYPTION_KEY", KEY), entry("UNICODE", "héllo 😀"),
                entry("EMPTY", ""), entry("NEWLINES", "line1\nline2"), entry("EQUALS", "left=right")));

        assertThat(configuration.encryptionKey().bytes()).hasSize(32);
        assertThat(configuration.runtimeEnvironment().asMap()).containsEntry("UNICODE", "héllo 😀")
                .containsEntry("EMPTY", "").containsEntry("NEWLINES", "line1").containsEntry("EQUALS", "left=right");
        assertThatThrownBy(() -> configuration.runtimeEnvironment().asMap().put("X", "Y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsMalformedEnvelopeCasesWithoutLeakingValues() {
        byte[][] invalid = {bytes("wrong"), bytes("JUPITER_BOOTSTRAP_V1\0NAME"), bytes("JUPITER_BOOTSTRAP_V1\0NAME\0"),
                envelope(entry("BAD-NAME", "x")), envelope(entry("DUP", "one"), entry("DUP", "two")),
                envelope(entry("OTHER", "x")), envelope(entry("JUPITER_ENCRYPTION_KEY", "")),
                envelope(entry("JUPITER_ENCRYPTION_KEY", "not-base64")),
                envelope(entry("JUPITER_ENCRYPTION_KEY", "AQ==")), invalidUtf8()};
        for (byte[] input : invalid) {
            assertThatThrownBy(() -> read(input)).isInstanceOf(IllegalStateException.class)
                    .satisfies(error -> assertThat(error.getMessage()).doesNotContain(SECRET));
        }
    }

    @Test
    void rejectsTruncationAndExcessivePayload() {
        assertThat(read(envelopeBytes("JUPITER_ENCRYPTION_KEY=" + KEY + "\nTRUNCATED")).runtimeEnvironment().asMap())
                .doesNotContainKey("TRUNCATED");
        assertThat(read(envelope(entry("JUPITER_ENCRYPTION_KEY", KEY), entry("BIG", "x".repeat(4 * 1024 * 1024))))
                .runtimeEnvironment().get("BIG")).hasSize(4 * 1024 * 1024);
    }

    @Test
    void removesKeyFromRuntimeEnvironmentAndRejectsMissingOrBlankKey() {
        var configuration = read(envelope(entry("JUPITER_ENCRYPTION_KEY", KEY), entry("VALUE", "ok")));
        assertThat(configuration.runtimeEnvironment().asMap()).doesNotContainKey("JUPITER_ENCRYPTION_KEY");
        for (byte[] input : new byte[][]{bytes(""), envelope(entry("JUPITER_ENCRYPTION_KEY", " "))}) {
            assertThatThrownBy(() -> read(input))
                    .hasMessage(input.length == 0 ? "encryption key is missing" : "encryption key is invalid");
        }
    }

    private static BootstrapConfiguration read(byte[] bytes) {
        return BootstrapConfigurationReader.read(new ByteArrayInputStream(bytes));
    }

    private static byte[] envelope(Map.Entry<String, String>... entries) {
        var output = new ByteArrayOutputStream();
        for (var entry : entries) {
            write(output, entry.getKey() + "=" + entry.getValue() + "\n");
        }
        return output.toByteArray();
    }

    private static byte[] envelopeBytes(String suffix) {
        return bytes(suffix);
    }

    private static byte[] invalidUtf8() {
        return new byte[]{'J', 'U', 'P', 'I', 'T', 'E', 'R', '_', 'B', 'O', 'O', 'T', 'S', 'T', 'R', 'A', 'P', '_', 'V',
                '1', 0, 'J', 'U', 'P', 'I', 'T', 'E', 'R', '_', 'E', 'N', 'C', 'R', 'Y', 'P', 'T', 'I', 'O', 'N', '_',
                'K', 'E', 'Y', 0, (byte) 0xc3, 0x28, 0};
    }

    private static Map.Entry<String, String> entry(String name, String value) {
        return Map.entry(name, value);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void write(ByteArrayOutputStream output, String value) {
        output.writeBytes(bytes(value));
    }
}

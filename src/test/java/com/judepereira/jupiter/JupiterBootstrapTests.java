package com.judepereira.jupiter;

import com.judepereira.jupiter.security.EncryptionKey;
import com.judepereira.jupiter.testsupport.TestEncryptionSupport;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JupiterBootstrapTests {
    private static final String KEY = TestEncryptionSupport.KEY;

    @Test
    void hardensBeforeReadingKeyAndDoesNotReadAfterHardeningFails() {
        List<String> events = new ArrayList<>();
        var input = new ByteArrayInputStream(KEY.getBytes(StandardCharsets.US_ASCII)) {
            @Override
            public int read() {
                events.add("read");
                return super.read();
            }
        };

        assertThatThrownBy(() -> Jupiter.bootstrap(input, () -> {
            events.add("harden");
            throw new IllegalStateException("hardening failed");
        }, ignored -> {
            events.add("key");
            throw new AssertionError("key reader must not run");
        }, ignored -> events.add("start")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("hardening failed");

        assertThat(events).containsExactly("harden");
    }

    @Test
    void readsKeyAndPreparesApplicationAfterHardeningSucceeds() {
        List<String> events = new ArrayList<>();
        EncryptionKey expected = new EncryptionKey(new byte[32]);

        Jupiter.bootstrap(new ByteArrayInputStream(new byte[0]), () -> events.add("harden"), ignored -> {
            events.add("key");
            return expected;
        }, key -> {
            assertThat(key).isSameAs(expected);
            events.add("start");
        });

        assertThat(events).containsExactly("harden", "key", "start");
    }
}

package com.judepereira.jupiter.agent.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class InboundMcpSessionRegistryTests {
    @Test
    void bindingExpiresAtExactTtlAndCanBeReplaced() {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var registry = new InboundMcpSessionRegistry(clock);
        registry.bind("session", "old", "fingerprint");

        clock.advance(Duration.ofHours(24));
        assertThat(registry.status("session", "old", "fingerprint"))
                .isEqualTo(InboundMcpTokenService.SessionStatus.UNKNOWN);
        registry.bind("session", "new", "fingerprint");
        assertThat(registry.status("session", "new", "fingerprint"))
                .isEqualTo(InboundMcpTokenService.SessionStatus.VALID);
    }

    @Test
    void unknownMismatchValidAndCapacityEvictionAreDistinguished() {
        var registry = new InboundMcpSessionRegistry(Clock.systemUTC());
        assertThat(registry.status("missing", "credential", "fingerprint"))
                .isEqualTo(InboundMcpTokenService.SessionStatus.UNKNOWN);
        registry.bind("one", "credential", "fingerprint");
        assertThat(registry.status("one", "credential", "wrong"))
                .isEqualTo(InboundMcpTokenService.SessionStatus.MISMATCH);
        assertThat(registry.status("one", "credential", "fingerprint"))
                .isEqualTo(InboundMcpTokenService.SessionStatus.VALID);

        for (int index = 0; index < InboundMcpSessionRegistry.MAX_SESSIONS; index++) {
            registry.bind("session-" + index, "credential", "fingerprint");
        }
        assertThat(registry.status("one", "credential", "fingerprint"))
                .isEqualTo(InboundMcpTokenService.SessionStatus.UNKNOWN);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}

package com.judepereira.jupiter.agent.mcp;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Objects;

final class InboundMcpSessionRegistry {
    static final int MAX_SESSIONS = 10_000;
    private static final Duration SESSION_TTL = Duration.ofHours(24);
    private final Clock clock;
    private final LinkedHashMap<String, SessionBinding> sessions = new LinkedHashMap<>();

    InboundMcpSessionRegistry(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    synchronized void bind(String sessionId, String credentialId) {
        Instant now = clock.instant();
        removeExpired(now);
        sessions.remove(sessionId);
        if (sessions.size() >= MAX_SESSIONS) {
            Iterator<String> oldest = sessions.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        sessions.put(sessionId, new SessionBinding(credentialId, now.plus(SESSION_TTL)));
    }

    synchronized InboundMcpTokenService.SessionStatus status(String sessionId, String credentialId) {
        removeExpired(clock.instant());
        SessionBinding binding = sessions.get(sessionId);
        if (binding == null) {
            return InboundMcpTokenService.SessionStatus.UNKNOWN;
        }
        return binding.credentialId().equals(credentialId)
                ? InboundMcpTokenService.SessionStatus.VALID
                : InboundMcpTokenService.SessionStatus.MISMATCH;
    }

    synchronized void invalidate(String sessionId) {
        sessions.remove(sessionId);
    }

    private void removeExpired(Instant now) {
        sessions.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private record SessionBinding(String credentialId, Instant expiresAt) {
        private SessionBinding {
            Objects.requireNonNull(credentialId);
        }
    }
}

package com.judepereira.jupiter.ui;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class ActiveStreamRegistryService {

    private final ConcurrentMap<String, StreamRef> streamsByAssistantId = new ConcurrentHashMap<>();

    public void register(String assistantId, long sessionId, String workspaceRoot) {
        if (assistantId == null || assistantId.isBlank()) {
            return;
        }
        streamsByAssistantId.put(assistantId, new StreamRef(sessionId, workspaceRoot));
    }

    public void unregister(String assistantId) {
        if (assistantId == null || assistantId.isBlank()) {
            return;
        }
        streamsByAssistantId.remove(assistantId);
    }

    public Set<Long> activeSessionIdsSnapshot() {
        return streamsByAssistantId.values().stream().map(StreamRef::sessionId).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public Set<String> activeAssistantIdsSnapshot() {
        return Set.copyOf(streamsByAssistantId.keySet());
    }

    public Set<Long> activeSessionIds() {
        return activeSessionIdsSnapshot();
    }

    public Set<String> activeAssistantIds() {
        return activeAssistantIdsSnapshot();
    }

    public boolean hasActiveStreamForSession(long sessionId) {
        return activeSessionIdsSnapshot().contains(sessionId);
    }

    public boolean hasActiveStreamForAssistantId(String assistantId) {
        return assistantId != null && streamsByAssistantId.containsKey(assistantId);
    }

    public Optional<Long> sessionIdForAssistantId(String assistantId) {
        StreamRef ref = assistantId == null ? null : streamsByAssistantId.get(assistantId);
        return ref == null ? Optional.empty() : Optional.of(ref.sessionId());
    }

    public Optional<String> assistantIdForSession(long sessionId) {
        return streamsByAssistantId.entrySet().stream()
                .filter(entry -> entry.getValue().sessionId() == sessionId)
                .map(Map.Entry::getKey)
                .findFirst();
    }

    private record StreamRef(long sessionId, String workspaceRoot) {}
}

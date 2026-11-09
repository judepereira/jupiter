package com.judepereira.jupiter.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class ActiveStreamRegistryService {

    private final ConcurrentMap<String, StreamRef> streamsByAssistantId = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, String> assistantBySessionId = new ConcurrentHashMap<>();
    private final Object admissionLock = new Object();

    public boolean claim(String assistantId, long sessionId, String workspaceRoot) {
        if (assistantId == null || assistantId.isBlank()) {
            throw new IllegalArgumentException("assistantId is required");
        }
        synchronized (admissionLock) {
            if (assistantBySessionId.containsKey(sessionId) || streamsByAssistantId.containsKey(assistantId)) {
                return false;
            }
            StreamRef ref = new StreamRef(sessionId, workspaceRoot, normalize(workspaceRoot));
            assistantBySessionId.put(sessionId, assistantId);
            streamsByAssistantId.put(assistantId, ref);
            return true;
        }
    }

    public void register(String assistantId, long sessionId, String workspaceRoot) {
        if (assistantId == null || assistantId.isBlank()) {
            return;
        }
        synchronized (admissionLock) {
            if (assistantBySessionId.containsKey(sessionId) || streamsByAssistantId.containsKey(assistantId)) {
                throw new IllegalStateException("SESSION_BUSY: A chat stream is already active");
            }
            StreamRef ref = new StreamRef(sessionId, workspaceRoot, normalize(workspaceRoot));
            assistantBySessionId.put(sessionId, assistantId);
            streamsByAssistantId.put(assistantId, ref);
        }
    }

    public void unregister(String assistantId) {
        if (assistantId == null || assistantId.isBlank()) {
            return;
        }
        synchronized (admissionLock) {
            StreamRef current = streamsByAssistantId.remove(assistantId);
            if (current != null) {
                assistantBySessionId.remove(current.sessionId(), assistantId);
            }
        }
    }

    public Set<Long> activeSessionIdsSnapshot() {
        return streamsByAssistantId.values().stream().map(StreamRef::sessionId).collect(Collectors.toUnmodifiableSet());
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

    public boolean hasActiveStreamForWorkspace(String workspaceRoot) {
        Path workspace = normalize(workspaceRoot);
        if (workspace == null) {
            return false;
        }
        return streamsByAssistantId.values().stream().anyMatch(ref -> workspace.equals(ref.workspace()));
    }

    private Path normalize(String workspaceRoot) {
        if (workspaceRoot == null || workspaceRoot.isBlank()) {
            return null;
        }
        try {
            Path path = Path.of(workspaceRoot);
            try {
                return path.toRealPath();
            } catch (IOException | RuntimeException e) {
                return path.toAbsolutePath().normalize();
            }
        } catch (RuntimeException e) {
            return null;
        }
    }

    public Optional<Long> sessionIdForAssistantId(String assistantId) {
        StreamRef ref = assistantId == null ? null : streamsByAssistantId.get(assistantId);
        return ref == null ? Optional.empty() : Optional.of(ref.sessionId());
    }

    public Optional<String> assistantIdForSession(long sessionId) {
        return streamsByAssistantId.entrySet().stream().filter(entry -> entry.getValue().sessionId() == sessionId)
                .map(Map.Entry::getKey).findFirst();
    }

    private record StreamRef(long sessionId, String workspaceRoot, Path workspace) {
    }
}

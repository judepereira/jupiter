package com.judepereira.jupiter.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class ActiveStreamRegistryService {

    private final ConcurrentMap<String, StreamRef> streamsByAssistantId = new ConcurrentHashMap<>();
    private final ConcurrentMap<Path, ReentrantLock> workspaceLocks = new ConcurrentHashMap<>();

    public void register(String assistantId, long sessionId, String workspaceRoot) {
        if (assistantId == null || assistantId.isBlank()) {
            return;
        }
        Path workspace = normalize(workspaceRoot);
        if (workspace == null) {
            streamsByAssistantId.put(assistantId, new StreamRef(sessionId, workspaceRoot, null));
            return;
        }
        ReentrantLock lock = workspaceLocks.computeIfAbsent(workspace, ignored -> new ReentrantLock());
        lock.lock();
        try {
            streamsByAssistantId.put(assistantId, new StreamRef(sessionId, workspaceRoot, workspace));
        } finally {
            lock.unlock();
        }
    }

    public void unregister(String assistantId) {
        if (assistantId == null || assistantId.isBlank()) {
            return;
        }
        StreamRef current = streamsByAssistantId.get(assistantId);
        if (current == null) {
            return;
        }
        if (current.workspace() == null) {
            streamsByAssistantId.remove(assistantId, current);
            return;
        }
        ReentrantLock lock = workspaceLocks.computeIfAbsent(current.workspace(), ignored -> new ReentrantLock());
        lock.lock();
        try {
            streamsByAssistantId.remove(assistantId, current);
        } finally {
            lock.unlock();
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
        ReentrantLock lock = workspaceLocks.computeIfAbsent(workspace, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return hasActiveStreamForWorkspace(workspace);
        } finally {
            lock.unlock();
        }
    }

    public boolean runIfNoActiveStreamForWorkspace(String workspaceRoot, Runnable operation) {
        Path workspace = normalize(workspaceRoot);
        if (workspace == null) {
            return false;
        }
        ReentrantLock lock = workspaceLocks.computeIfAbsent(workspace, ignored -> new ReentrantLock());
        lock.lock();
        try {
            if (hasActiveStreamForWorkspace(workspace)) {
                return false;
            }
            operation.run();
            return true;
        } finally {
            lock.unlock();
        }
    }

    private boolean hasActiveStreamForWorkspace(Path workspace) {
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

package com.judepereira.jupiter.watch;

import com.judepereira.jupiter.ui.ActiveStreamRegistryService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongConsumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Coordinates the small critical sections around persisted activity and turn
 * admission.
 */
@Service
@RequiredArgsConstructor
public class SessionActivityCoordinator {
    private final ActiveStreamRegistryService activeStreams;
    private final WatchRepository watches;
    private final ConcurrentMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, AtomicLong> versions = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<LongConsumer> activityListeners = new CopyOnWriteArrayList<>();

    public record ActivitySnapshot(long sessionId, long version) {
    }

    public void addActivityListener(LongConsumer listener) {
        activityListeners.add(listener);
    }

    public ActivitySnapshot snapshot(long sessionId) {
        return new ActivitySnapshot(sessionId, versions.computeIfAbsent(sessionId, ignored -> new AtomicLong()).get());
    }

    public void recordInteractiveActivity(long sessionId) {
        withLock(sessionId, () -> {
            versions.computeIfAbsent(sessionId, ignored -> new AtomicLong()).incrementAndGet();
            watches.activity(sessionId);
            activityListeners.forEach(listener -> listener.accept(sessionId));
        });
    }

    public void recordUserActivity(long sessionId, String publicId) {
        withLock(sessionId, () -> {
            if (watches.qualifyingUserActivity(sessionId, publicId)) {
                versions.computeIfAbsent(sessionId, ignored -> new AtomicLong()).incrementAndGet();
                watches.activity(sessionId);
                activityListeners.forEach(listener -> listener.accept(sessionId));
            }
        });
    }

    public void recordAssistantActivity(long sessionId, String publicId) {
        withLock(sessionId, () -> {
            if (watches.qualifyingAssistantActivity(sessionId, publicId)) {
                versions.computeIfAbsent(sessionId, ignored -> new AtomicLong()).incrementAndGet();
                watches.activity(sessionId);
                activityListeners.forEach(listener -> listener.accept(sessionId));
            }
        });
    }

    public boolean admit(long sessionId, long expectedVersion, Runnable persistAndRegister) {
        ReentrantLock lock = locks.computeIfAbsent(sessionId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            if (versions.computeIfAbsent(sessionId, ignored -> new AtomicLong()).get() != expectedVersion
                    || activeStreams.hasActiveStreamForSession(sessionId))
                return false;
            persistAndRegister.run();
            return true;
        } finally {
            lock.unlock();
        }
    }

    public void withLock(long sessionId, Runnable action) {
        ReentrantLock lock = locks.computeIfAbsent(sessionId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    public void cancelWatch(long sessionId) {
        withLock(sessionId, () -> {
            versions.computeIfAbsent(sessionId, ignored -> new AtomicLong()).incrementAndGet();
            watches.activity(sessionId);
        });
    }
}

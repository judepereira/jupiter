package com.judepereira.jupiter2.agent.task;

public final class SubagentTaskStreamBridge {

    private static final ThreadLocal<SubagentTaskService.SubagentTaskStreamListener> CURRENT = new ThreadLocal<>();

    private SubagentTaskStreamBridge() {
    }

    public static Scope bind(SubagentTaskService.SubagentTaskStreamListener listener) {
        SubagentTaskService.SubagentTaskStreamListener previous = CURRENT.get();
        CURRENT.set(listener == null ? SubagentTaskService.SubagentTaskStreamListener.noop() : listener);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    public static SubagentTaskService.SubagentTaskStreamListener current() {
        SubagentTaskService.SubagentTaskStreamListener listener = CURRENT.get();
        return listener == null ? SubagentTaskService.SubagentTaskStreamListener.noop() : listener;
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}

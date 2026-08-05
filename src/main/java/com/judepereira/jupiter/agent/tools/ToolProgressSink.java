package com.judepereira.jupiter.agent.tools;

@FunctionalInterface
public interface ToolProgressSink {

    void emit(String eventName, Object payload);

    static ToolProgressSink noop() {
        return (eventName, payload) -> {
        };
    }
}

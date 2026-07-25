package com.judepereira.jupiter2.agent.tools;

@FunctionalInterface
public interface ToolProgressSink {

    void emit(String eventName, Object payload);

    static ToolProgressSink noop() {
        return (eventName, payload) -> {
        };
    }
}

package com.judepereira.jupiter.agent.harness;

public class StreamCancelledException extends RuntimeException {
    public StreamCancelledException() {
        super("Action Interrupted");
    }
}

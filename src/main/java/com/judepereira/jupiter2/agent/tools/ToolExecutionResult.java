package com.judepereira.jupiter2.agent.tools;

import java.util.Map;

public class ToolExecutionResult {
    private final boolean success;
    private final String text;
    private final Map<String, Object> machine;

    public ToolExecutionResult(boolean success, String text, Map<String, Object> machine) {
        this.success = success;
        this.text = text;
        this.machine = machine;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getText() {
        return text;
    }

    public Map<String, Object> getMachine() {
        return machine;
    }
}

package com.judepereira.jupiter2.agent.harness;

import java.util.Map;

public class ToolCallTrace {
    private final String toolName;
    private final Map<String, Object> args;
    private final boolean success;
    private final String textSummary;
    private final Map<String, Object> machineSummary;

    public ToolCallTrace(String toolName, Map<String, Object> args, boolean success, String textSummary, Map<String, Object> machineSummary) {
        this.toolName = toolName;
        this.args = args;
        this.success = success;
        this.textSummary = textSummary;
        this.machineSummary = machineSummary;
    }

    public String getToolName() {
        return toolName;
    }

    public Map<String, Object> getArgs() {
        return args;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getTextSummary() {
        return textSummary;
    }

    public Map<String, Object> getMachineSummary() {
        return machineSummary;
    }
}

package com.judepereira.jupiter2.agent.harness;

import java.util.List;

public class AgentTurnResult {
    private final String finalText;
    private final List<ToolCallTrace> traces;

    public AgentTurnResult(String finalText, List<ToolCallTrace> traces) {
        this.finalText = finalText;
        this.traces = traces;
    }

    public String getFinalText() {
        return finalText;
    }

    public List<ToolCallTrace> getTraces() {
        return traces;
    }
}

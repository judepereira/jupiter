package com.judepereira.jupiter2.agent.harness;

public class AgentTurnRequest {
    private final String systemPrompt;
    private final String userPrompt;

    public AgentTurnRequest(String systemPrompt, String userPrompt) {
        this.systemPrompt = systemPrompt;
        this.userPrompt = userPrompt;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public String getUserPrompt() {
        return userPrompt;
    }
}

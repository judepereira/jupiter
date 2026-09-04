package com.judepereira.jupiter.agent.harness;

import com.judepereira.jupiter.agent.catalog.ThinkingLevel;
import com.judepereira.jupiter.agent.llm.dto.Message;
import lombok.Getter;

import java.util.List;

@Getter
public class AgentTurnRequest {
    private final String systemPrompt;
    private final List<Message> conversationHistory;
    private final String workspaceRoot;
    private final String agentId;
    private final String modelId;
    private final ThinkingLevel thinkingLevel;
    private final Long sessionId;
    private final CancellationToken cancellationToken;





    public AgentTurnRequest(String systemPrompt, List<Message> conversationHistory, String workspaceRoot,
                            String agentId, String modelId, ThinkingLevel thinkingLevel, Long sessionId,
                            CancellationToken cancellationToken) {
        this.systemPrompt = systemPrompt;
        this.conversationHistory = List.copyOf(conversationHistory);
        this.workspaceRoot = workspaceRoot;
        this.agentId = agentId;
        this.modelId = modelId;
        this.thinkingLevel = thinkingLevel;
        this.sessionId = sessionId;
        this.cancellationToken = cancellationToken;
    }
}

package com.judepereira.jupiter2.agent.harness;

import com.judepereira.jupiter2.agent.llm.dto.Message;
import lombok.Getter;

import java.util.List;

@Getter
public class AgentTurnRequest {
    private final String systemPrompt;
    private final List<Message> conversationHistory;

    public AgentTurnRequest(String systemPrompt, String userPrompt) {
        this(systemPrompt, List.of(new Message(Message.Role.USER, userPrompt)));
    }

    public AgentTurnRequest(String systemPrompt, List<Message> conversationHistory) {
        this.systemPrompt = systemPrompt;
        this.conversationHistory = List.copyOf(conversationHistory);
    }
}

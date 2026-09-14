package com.judepereira.jupiter.agent.llm.dto;

import lombok.Getter;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

@Getter
public class Message {
    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

    private final Role role;
    private final String content;
    private final String toolCallId;
    private final List<ToolCall> toolCalls;
    // Provider-native blocks are intentionally opaque to the shared/OpenAI model.
    private final List<JsonNode> providerContent;

    public Message(Role role, String content, String toolCallId, List<ToolCall> toolCalls,
                   List<JsonNode> providerContent) {
        this.role = role;
        this.content = content;
        this.toolCallId = toolCallId;
        this.toolCalls = toolCalls;
        this.providerContent = copyProviderContent(providerContent);
    }

    public List<JsonNode> getProviderContent() {
        return copyProviderContent(providerContent);
    }

    private static List<JsonNode> copyProviderContent(List<JsonNode> providerContent) {
        return providerContent == null ? List.<JsonNode>of()
                : providerContent.stream().map(node -> node == null ? (JsonNode) null : node.deepCopy()).toList();
    }
}

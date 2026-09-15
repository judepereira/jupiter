package com.judepereira.jupiter.agent.llm.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import lombok.Getter;

@Getter
public class ModelResponse {
    private final String assistantText;
    private final ToolCall toolCall; // nullable
    private final ModelResponseMetadata metadata;
    private final List<JsonNode> providerContent;

    public ModelResponse(String assistantText, ToolCall toolCall, ModelResponseMetadata metadata,
            List<JsonNode> providerContent) {
        this.assistantText = assistantText;
        this.toolCall = toolCall;
        this.metadata = metadata == null ? ModelResponseMetadata.empty() : metadata;
        this.providerContent = providerContent == null
                ? List.<JsonNode>of()
                : providerContent.stream().map(node -> node == null ? (JsonNode) null : node.deepCopy()).toList();
    }

    public List<JsonNode> getProviderContent() {
        return providerContent.stream().map(node -> node == null ? (JsonNode) null : node.deepCopy()).toList();
    }
}

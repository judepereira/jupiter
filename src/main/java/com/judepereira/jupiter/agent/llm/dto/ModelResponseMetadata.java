package com.judepereira.jupiter.agent.llm.dto;

import java.util.Map;

public record ModelResponseMetadata(
        Integer inputTokenCount,
        Integer outputTokenCount,
        Integer totalTokenCount,
        Integer cachedInputTokenCount,
        Integer cacheWriteTokenCount,
        Integer reasoningTokenCount,
        String responseId,
        String modelId,
        String finishReason,
        Map<String, Object> providerMetadata
) {
    public ModelResponseMetadata {
        providerMetadata = providerMetadata == null ? Map.of() : Map.copyOf(providerMetadata);
    }

    public static ModelResponseMetadata empty() {
        return new ModelResponseMetadata(null, null, null, null, null, null, null, null, null, Map.of());
    }
}

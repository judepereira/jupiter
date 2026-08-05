package com.judepereira.jupiter.agent.catalog;

public record ModelDefinition(
        String id,
        String displayName,
        String provider,
        String apiModelId,
        boolean supportsReasoning,
        boolean supportsTools,
        int contextTokens,
        int outputTokens,
        String inputPrice,
        String outputPrice,
        String releaseDate
) {
}

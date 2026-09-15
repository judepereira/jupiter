package com.judepereira.jupiter.agent.catalog;

import java.util.List;

public record ModelDefinition(String id, String displayName, String provider, String apiModelId,
        boolean supportsReasoning, boolean supportsTools, int contextTokens, int outputTokens, String inputPrice,
        String outputPrice, String releaseDate, String family, String lastUpdated, List<String> inputModalities,
        List<String> outputModalities) {
}

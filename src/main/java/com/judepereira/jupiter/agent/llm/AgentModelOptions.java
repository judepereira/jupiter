package com.judepereira.jupiter.agent.llm;

import com.judepereira.jupiter.agent.catalog.ThinkingLevel;

public record AgentModelOptions(
        String modelId,
        String apiModelId,
        ThinkingLevel thinkingLevel,
        boolean supportsReasoning,
        String textVerbosity
) {
}

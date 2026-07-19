package com.judepereira.jupiter2.agent.llm;

import com.judepereira.jupiter2.agent.catalog.ThinkingLevel;

public record AgentModelOptions(
        String modelId,
        String apiModelId,
        ThinkingLevel thinkingLevel,
        boolean supportsReasoning
) {
}

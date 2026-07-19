package com.judepereira.jupiter2.agent.catalog;

import java.util.List;

public record AgentDefinition(
        String id,
        String name,
        String description,
        String systemPrompt,
        String defaultModel,
        ThinkingLevel defaultThinkingLevel,
        boolean allowWrite,
        boolean allowCommand,
        List<String> allowedTools
) {
}

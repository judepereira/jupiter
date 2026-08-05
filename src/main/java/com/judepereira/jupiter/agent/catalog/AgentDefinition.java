package com.judepereira.jupiter.agent.catalog;

import java.util.List;

public record AgentDefinition(
        String id,
        String name,
        String description,
        String systemPrompt,
        AgentMode mode,
        String defaultModel,
        ThinkingLevel defaultThinkingLevel,
        String textVerbosity,
        boolean allowWrite,
        boolean allowCommand,
        List<String> allowedTools
) {
}

package com.judepereira.jupiter.agent.catalog;

import java.util.List;

public record AgentDefinition(
        String id,
        String name,
        String description,
        String systemPrompt,
        AgentMode mode,
        List<String> modelIds,
        ThinkingLevel defaultThinkingLevel,
        String textVerbosity,
        boolean allowWrite,
        boolean allowCommand,
        List<String> allowedTools
) {
    public AgentDefinition {
        modelIds = modelIds == null ? List.of() : List.copyOf(modelIds);
    }

    /** The first configured model, retained for callers that only need a preference. */
    public String defaultModel() {
        return modelIds.isEmpty() ? null : modelIds.getFirst();
    }

    public AgentDefinition(String id, String name, String description, String systemPrompt, AgentMode mode,
                           String model, ThinkingLevel thinking, String textVerbosity, boolean allowWrite,
                           boolean allowCommand, List<String> allowedTools) {
        this(id, name, description, systemPrompt, mode, List.of(model), thinking, textVerbosity,
                allowWrite, allowCommand, allowedTools);
    }
}

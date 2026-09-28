package com.judepereira.jupiter.agent.catalog;

import java.util.List;
import java.util.Map;

/** Immutable result of one catalog scan. */
public record AgentCatalogSnapshot(List<AgentDefinition> agents, Map<String, AgentSource> sources,
        List<AgentDiagnostic> diagnostics, Map<String, Inheritance> inheritance) {
    public AgentCatalogSnapshot {
        agents = List.copyOf(agents);
        sources = Map.copyOf(sources);
        diagnostics = List.copyOf(diagnostics);
        inheritance = Map.copyOf(inheritance);
    }

    public AgentDefinition getRequired(String id) {
        return agents.stream().filter(agent -> agent.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown agent id: " + id));
    }

    public AgentDefinition defaultAgent() {
        return getRequired("plan");
    }

    public Inheritance inheritanceOf(String id) {
        return inheritance.getOrDefault(id, Inheritance.NONE);
    }

    /**
     * Resolves inherited capabilities without allowing a child to widen its caller.
     */
    public AgentDefinition resolve(String id, AgentDefinition caller) {
        AgentDefinition child = getRequired(id);
        Inheritance flags = inheritanceOf(id);
        if (caller == null) {
            return child;
        }
        var models = flags.includesModel() || child.modelIds().isEmpty() ? caller.modelIds() : child.modelIds();
        // Primary agents are UI entry points and retain their catalog permissions. Only
        // delegated subagents are capability-constrained by their effective caller.
        if (child.mode() != AgentMode.SUBAGENT) {
            return new AgentDefinition(child.id(), child.name(), child.description(), child.systemPrompt(),
                    child.mode(), models, child.defaultThinkingLevel(), child.textVerbosity(), child.allowWrite(),
                    child.allowCommand(), child.allowedTools());
        }
        var tools = child.allowedTools().stream().filter(caller.allowedTools()::contains)
                .filter(tool -> !tool.equals("task")).toList();
        return new AgentDefinition(child.id(), child.name(), child.description(), child.systemPrompt(), child.mode(),
                models, child.defaultThinkingLevel(), child.textVerbosity(),
                tools.contains("write_file") || tools.contains("apply_patch"), tools.contains("run_command"), tools);
    }

    public enum Inheritance {
        NONE, MODEL, TOOLS, MODEL_AND_TOOLS;

        public boolean includesModel() {
            return this == MODEL || this == MODEL_AND_TOOLS;
        }

        public boolean includesTools() {
            return this == TOOLS || this == MODEL_AND_TOOLS;
        }
    }
}

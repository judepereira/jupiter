package com.judepereira.jupiter2.agent.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AgentDefinitionService {

    private static final String RESOURCE_PATH = "agents/agents.json";
    private static final String DEFAULT_AGENT_ID = "plan";

    private final List<AgentDefinition> agents;
    private final Map<String, AgentDefinition> agentsById;

    public AgentDefinitionService(ObjectMapper objectMapper) {
        this.agents = loadAgents(objectMapper);
        this.agentsById = indexAgents(agents);
        getRequired(DEFAULT_AGENT_ID);
    }

    public List<AgentDefinition> list() {
        return agents;
    }

    public AgentDefinition getRequired(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Agent id is required");
        }
        var agent = agentsById.get(id);
        if (agent == null) {
            throw new IllegalArgumentException("Unknown agent id: " + id);
        }
        return agent;
    }

    public AgentDefinition defaultAgent() {
        return getRequired(DEFAULT_AGENT_ID);
    }

    public AgentDefinition resolveOrDefault(String id) {
        if (id == null || id.isBlank()) {
            return defaultAgent();
        }
        return agentsById.getOrDefault(id, defaultAgent());
    }

    private static List<AgentDefinition> loadAgents(ObjectMapper objectMapper) {
        try (InputStream inputStream = new ClassPathResource(RESOURCE_PATH).getInputStream()) {
            var agents = objectMapper.readValue(inputStream, new TypeReference<List<AgentDefinition>>() {
            });
            validateAgents(agents);
            return List.copyOf(agents);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load agent catalog from classpath:" + RESOURCE_PATH, e);
        }
    }

    private static Map<String, AgentDefinition> indexAgents(List<AgentDefinition> agents) {
        Map<String, AgentDefinition> indexed = new LinkedHashMap<>();
        for (AgentDefinition agent : agents) {
            indexed.put(agent.id(), agent);
        }
        return Collections.unmodifiableMap(indexed);
    }

    private static void validateAgents(List<AgentDefinition> agents) {
        if (agents == null || agents.isEmpty()) {
            throw new IllegalStateException("Agent catalog is empty");
        }

        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (AgentDefinition agent : agents) {
            if (agent.id() == null || agent.id().isBlank()) {
                throw new IllegalStateException("Agent id is required");
            }
            if (seen.put(agent.id(), Boolean.TRUE) != null) {
                throw new IllegalStateException("Duplicate agent id: " + agent.id());
            }
            if (agent.allowedTools() == null || agent.allowedTools().isEmpty()) {
                throw new IllegalStateException("allowedTools is required for agent: " + agent.id());
            }
            for (String tool : agent.allowedTools()) {
                if (tool == null || tool.isBlank()) {
                    throw new IllegalStateException("allowedTools contains a blank tool for agent: " + agent.id());
                }
            }
            if (agent.defaultModel() == null || agent.defaultModel().isBlank()) {
                throw new IllegalStateException("defaultModel is required for agent: " + agent.id());
            }
        }
    }
}

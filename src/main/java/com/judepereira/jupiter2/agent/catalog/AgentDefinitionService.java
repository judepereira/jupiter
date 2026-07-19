package com.judepereira.jupiter2.agent.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AgentDefinitionService {

    private static final String RESOURCE_PATTERN = "classpath*:agents/*.md";
    private static final String DEFAULT_AGENT_ID = "plan";
    private static final YAMLMapper YAML_MAPPER = new YAMLMapper();

    private final List<AgentDefinition> agents;
    private final Map<String, AgentDefinition> agentsById;

    public AgentDefinitionService(ObjectMapper objectMapper) {
        this.agents = loadAgents();
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

    private static List<AgentDefinition> loadAgents() {
        try {
            var resolver = new PathMatchingResourcePatternResolver();
            var agents = Arrays.stream(resolver.getResources(RESOURCE_PATTERN))
                    .sorted(Comparator.comparing(Resource::getFilename, Comparator.nullsLast(String::compareTo))
                            .thenComparing(AgentDefinitionService::resourceSortKey))
                    .map(AgentDefinitionService::loadAgent)
                    .toList();
            validateAgents(agents);
            return List.copyOf(agents);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load agent catalog from classpath:" + RESOURCE_PATTERN, e);
        }
    }

    private static AgentDefinition loadAgent(Resource resource) {
        try (InputStream inputStream = resource.getInputStream()) {
            var content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            var frontMatter = parseFrontMatter(resource, content);
            var metadata = YAML_MAPPER.readValue(frontMatter.yaml(), FrontMatter.class);
            return new AgentDefinition(
                    metadata.id(),
                    metadata.name(),
                    metadata.description(),
                    frontMatter.body(),
                    metadata.defaultModel(),
                    metadata.defaultThinkingLevel(),
                    metadata.allowWrite(),
                    metadata.allowCommand(),
                    metadata.allowedTools()
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load agent definition from classpath:" + resourceSortKey(resource), e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed to load agent definition from classpath:" + resourceSortKey(resource), e);
        }
    }

    private static FrontMatterAndBody parseFrontMatter(Resource resource, String content) {
        if (!content.startsWith("---")) {
            throw new IllegalStateException("Missing YAML frontmatter in classpath:" + resourceSortKey(resource));
        }

        int firstLineEnd = lineEnd(content, 0);
        if (!stripTrailingCarriageReturn(content.substring(0, firstLineEnd)).equals("---")) {
            throw new IllegalStateException("Malformed YAML frontmatter in classpath:" + resourceSortKey(resource));
        }

        int frontMatterStart = nextLineStart(content, firstLineEnd);
        int frontMatterEnd = findClosingFrontMatterDelimiter(content, frontMatterStart, resource);
        var yaml = content.substring(frontMatterStart, frontMatterEnd);
        var bodyStart = nextLineStart(content, frontMatterEnd + 3);
        var body = bodyStart >= content.length() ? "" : content.substring(bodyStart);
        return new FrontMatterAndBody(yaml, trimTrailingLineBreak(trimLeadingLineBreak(body)));
    }

    private static int findClosingFrontMatterDelimiter(String content, int start, Resource resource) {
        int index = start;
        while (index <= content.length()) {
            int lineEnd = lineEnd(content, index);
            if (stripTrailingCarriageReturn(content.substring(index, lineEnd)).equals("---")) {
                return index;
            }
            if (lineEnd == content.length()) {
                break;
            }
            index = nextLineStart(content, lineEnd);
        }
        throw new IllegalStateException("Missing closing YAML frontmatter delimiter in classpath:" + resourceSortKey(resource));
    }

    private static int lineEnd(String content, int start) {
        int newline = content.indexOf('\n', start);
        return newline == -1 ? content.length() : newline;
    }

    private static int nextLineStart(String content, int lineEnd) {
        if (lineEnd >= content.length()) {
            return content.length();
        }
        return lineEnd + 1;
    }

    private static String trimLeadingLineBreak(String body) {
        if (body.startsWith("\r\n")) {
            return body.substring(2);
        }
        if (body.startsWith("\n") || body.startsWith("\r")) {
            return body.substring(1);
        }
        return body;
    }

    private static String trimTrailingLineBreak(String body) {
        if (body.endsWith("\r\n")) {
            return body.substring(0, body.length() - 2);
        }
        if (body.endsWith("\n") || body.endsWith("\r")) {
            return body.substring(0, body.length() - 1);
        }
        return body;
    }

    private static String stripTrailingCarriageReturn(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    private static String resourceSortKey(Resource resource) {
        try {
            return resource.getURL().toString();
        } catch (IOException e) {
            return resource.getDescription();
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

    private record FrontMatterAndBody(String yaml, String body) {
    }

    private record FrontMatter(
            String id,
            String name,
            String description,
            String defaultModel,
            ThinkingLevel defaultThinkingLevel,
            boolean allowWrite,
            boolean allowCommand,
            List<String> allowedTools
    ) {
    }
}

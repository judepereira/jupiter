package com.judepereira.jupiter.agent.catalog;

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
import java.util.stream.Collectors;

@Service
public class AgentDefinitionService {

    private static final String RESOURCE_PATTERN = "classpath*:agents/*.md";
    private static final String DEFAULT_AGENT_ID = "plan";
    private static final YAMLMapper YAML_MAPPER = new YAMLMapper();
    private static final List<String> SUPPORTED_TOOLS = List.of(
            "list_files",
            "read_file",
            "search_code",
            "write_file",
            "apply_patch",
            "display_image",
            "run_command",
            "task"
    );

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

    public List<AgentDefinition> listPrimaryAgents() {
        return agents.stream().filter(agent -> agent.mode() == AgentMode.AGENT).toList();
    }

    public List<AgentDefinition> listSubagents() {
        return agents.stream().filter(agent -> agent.mode() == AgentMode.SUBAGENT).toList();
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

    static AgentDefinition loadAgent(Resource resource) {
        try (InputStream inputStream = resource.getInputStream()) {
            var content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            var frontMatter = parseFrontMatter(resource, content);
            var metadata = YAML_MAPPER.readValue(frontMatter.yaml(), FrontMatter.class);
            var id = resolveId(resource, metadata.id());
            var name = resolveName(id, metadata.name());
            var allowedTools = resolveAllowedTools(metadata.tools(), id, metadata.mode());
            var allowWrite = allowedTools.contains("write_file") || allowedTools.contains("apply_patch");
            var allowCommand = allowedTools.contains("run_command");
            validateRequiredFields(metadata, id);
            return new AgentDefinition(
                    id,
                    name,
                    metadata.description(),
                    frontMatter.body(),
                    metadata.mode(),
                    metadata.model(),
                    metadata.reasoningEffort(),
                    metadata.textVerbosity(),
                    allowWrite,
                    allowCommand,
                    allowedTools
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

    private static String resolveId(Resource resource, String id) {
        if (id != null && !id.isBlank()) {
            return id;
        }
        return resourceFilenameToId(resource.getFilename());
    }

    private static String resolveName(String id, String name) {
        if (name != null && !name.isBlank()) {
            return name;
        }
        return idToDisplayName(id);
    }

    private static List<String> resolveAllowedTools(Map<String, Boolean> tools, String agentId, AgentMode mode) {
        if (tools == null || tools.isEmpty()) {
            throw new IllegalStateException("tools is required for agent: " + agentId);
        }
        if (Boolean.TRUE.equals(tools.get("*"))) {
            return mode == AgentMode.SUBAGENT
                    ? List.of("list_files", "read_file", "search_code", "write_file", "apply_patch", "display_image", "run_command", "mcp:*")
                    : List.of("list_files", "read_file", "search_code", "write_file", "apply_patch", "display_image", "run_command", "mcp:*", "task");
        }
        List<String> allowed = tools.entrySet().stream()
                .filter(entry -> Boolean.TRUE.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .peek(AgentDefinitionService::validateToolName)
                .toList();
        if (mode == AgentMode.SUBAGENT && allowed.contains("task")) {
            throw new IllegalStateException("task is not allowed for subagent: " + agentId);
        }
        return allowed;
    }

    private static void validateToolName(String tool) {
        if (tool == null || tool.isBlank()) {
            throw new IllegalStateException("tools contains a blank tool");
        }
        if ("mcp:*".equals(tool)) {
            return;
        }
        if (!SUPPORTED_TOOLS.contains(tool)) {
            throw new IllegalStateException("Unknown tool in agent definition: " + tool);
        }
    }

    private static String resourceFilenameToId(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalStateException("Agent id is required");
        }
        String baseName = filename.endsWith(".md") ? filename.substring(0, filename.length() - 3) : filename;
        return baseName.replaceFirst("^\\d+-", "");
    }

    private static String idToDisplayName(String id) {
        return Arrays.stream(id.split("[-_]") )
                .filter(part -> !part.isBlank())
                .map(part -> part.substring(0, 1).toUpperCase() + part.substring(1))
                .collect(Collectors.joining(" "));
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
            if (agent.description() == null || agent.description().isBlank()) {
                throw new IllegalStateException("description is required for agent: " + agent.id());
            }
            if (agent.mode() == null) {
                throw new IllegalStateException("mode is required for agent: " + agent.id());
            }
            if (agent.defaultModel() == null || agent.defaultModel().isBlank()) {
                throw new IllegalStateException("model is required for agent: " + agent.id());
            }
            if (agent.defaultThinkingLevel() == null) {
                throw new IllegalStateException("reasoningEffort is required for agent: " + agent.id());
            }
            if (agent.allowedTools() == null || agent.allowedTools().isEmpty()) {
                throw new IllegalStateException("tools is required for agent: " + agent.id());
            }
        }
    }

    private static void validateRequiredFields(FrontMatter metadata, String id) {
        if (metadata.description() == null || metadata.description().isBlank()) {
            throw new IllegalStateException("description is required for agent: " + id);
        }
        if (metadata.mode() == null) {
            throw new IllegalStateException("mode is required for agent: " + id);
        }
        if (metadata.model() == null || metadata.model().isBlank()) {
            throw new IllegalStateException("model is required for agent: " + id);
        }
        if (metadata.reasoningEffort() == null) {
            throw new IllegalStateException("reasoningEffort is required for agent: " + id);
        }
    }

    private record FrontMatterAndBody(String yaml, String body) {
    }

    private record FrontMatter(
            String id,
            String name,
            String description,
            AgentMode mode,
            String model,
            ThinkingLevel reasoningEffort,
            String textVerbosity,
            Map<String, Boolean> tools
    ) {
    }
}

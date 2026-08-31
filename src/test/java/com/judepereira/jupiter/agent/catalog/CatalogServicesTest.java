package com.judepereira.jupiter.agent.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.judepereira.jupiter.testsupport.ModelCatalogTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class CatalogServicesTest {

    private static final YAMLMapper YAML_MAPPER = new YAMLMapper();
    private static final Pattern AGENT_MARKDOWN = Pattern.compile("(?s)^---\\R(.*?)\\R---\\R?(.*)$");
    private static final List<String> AGENT_RESOURCE_PATHS = List.of(
            "/agents/01-plan.md",
            "/agents/02-engineer.md",
            "/agents/03-explore.md",
            "/agents/04-apprentice.md",
            "/agents/05-test.md"
    );

    @Test
    public void agentCatalogLoadsAllBundledAgentsWithExpectedDefaults() {
        AgentDefinitionService service = new AgentDefinitionService(new ObjectMapper());
        List<AgentResource> resources = AGENT_RESOURCE_PATHS.stream()
                .map(path -> new AgentResource(path, loadAgentMarkdown(path)))
                .toList();
        AgentDefinition wildcardPrimary = loadInlineWildcardAgent("11-primary.md", AgentMode.AGENT);
        AgentDefinition wildcardSubagent = loadInlineWildcardAgent("12-subagent.md", AgentMode.SUBAGENT);

        assertThat(service.list()).extracting(AgentDefinition::id)
                .containsExactlyElementsOf(resources.stream().map(AgentResource::id).toList());
        assertThat(service.listPrimaryAgents()).extracting(AgentDefinition::id)
                .containsExactly("plan", "engineer");
        assertThat(service.listSubagents()).extracting(AgentDefinition::id)
                .containsExactly("explore", "apprentice", "test");
        assertThat(service.defaultAgent().id()).isEqualTo("plan");

        resources.forEach(resource -> assertAgentMatchesResource(
                service.getRequired(resource.id()),
                resource,
                wildcardPrimary,
                wildcardSubagent
        ));
    }

    @Test
    public void wildcardToolSupportIncludesTaskForPrimaryAgentsButNotSubagents() throws Exception {
        AgentDefinition primary = AgentDefinitionService.loadAgent(resource("11-primary.md", """
                ---
                id: primary-task
                name: Primary Task
                description: Primary agent with wildcard tools
                mode: agent
                model: openai/gpt-5.5
                reasoningEffort: high
                textVerbosity: low
                tools:
                  '*': true
                ---
                body
                """));

        AgentDefinition subagent = AgentDefinitionService.loadAgent(resource("12-subagent.md", """
                ---
                id: subagent-task
                name: Subagent Task
                description: Subagent with wildcard tools
                mode: subagent
                model: openai/gpt-5.5
                reasoningEffort: high
                textVerbosity: low
                tools:
                  '*': true
                ---
                body
                """));

        List<String> expectedPrimaryTools = new ArrayList<>(subagent.allowedTools());
        expectedPrimaryTools.add("task");

        assertThat(primary.allowedTools()).containsExactlyElementsOf(expectedPrimaryTools);
        assertThat(primary.allowedTools()).contains("mcp:*");
        assertThat(subagent.allowedTools()).contains("mcp:*");
        assertThat(subagent.allowedTools()).doesNotContain("task");
    }

    @Test
    public void agentModeRejectsInvalidValues() {
        assertThatThrownBy(() -> AgentMode.fromValue("invalid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid mode");
    }

    @Test
    public void agentDefinitionLoadingFailsLoudlyWhenRequiredFieldsAreMissing() throws Exception {
        String frontMatter = """
                ---
                description: Missing required fields
                mode: agent
                reasoningEffort: high
                tools:
                  list_files: true
                ---
                body
                """;

        ByteArrayResource resource = new ByteArrayResource(frontMatter.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "99-missing-model.md";
            }
        };

        assertThatThrownBy(() -> AgentDefinitionService.loadAgent(resource))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("model is required for agent: missing-model");
    }

    @Test
    public void modelCatalogFiltersOpenAiModelsFromFetchedJson() {
        ModelCatalogService service = ModelCatalogTestSupport.modelCatalogService();

        assertThat(service.defaultModelId()).isEqualTo("openai/gpt-5.5");
        assertThat(service.list()).extracting(ModelDefinition::id)
                .containsExactly("openai/gpt-5.5", "openai/gpt-5.5-pro", "openai/gpt-5.6-sol", "openai/gpt-5.6-terra", "openai/gpt-5.6-luna");
        assertThat(service.list()).extracting(ModelDefinition::id)
                .doesNotContain("openai/gpt-4.1");
        assertThat(service.list()).extracting(ModelDefinition::provider)
                .containsOnly("openai");

        ModelDefinition model = service.getRequired("openai/gpt-5.5-pro");
        assertThat(model.provider()).isEqualTo("openai");
        assertThat(model.apiModelId()).isEqualTo("gpt-5.5-pro");
    }

    @Test
    public void modelCatalogFailsLoudlyOnBadInput() {
        String json = """
                {
                  "models": {
                    "openai/gpt-5.5-bad": {
                      "id": "",
                      "name": "Bad Model",
                      "reasoning": true,
                      "tool_call": true,
                      "release_date": "2026-01-01",
                      "limit": {
                        "context": 100,
                        "output": 50
                      }
                    }
                  }
                }
                """;

        assertThatThrownBy(() -> ModelCatalogTestSupport.modelCatalogService("http://example.test/catalog.json", json))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Model id is required");
    }

    private static Resource resource(String filename, String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    private static AgentMarkdown loadAgentMarkdown(String resourcePath) {
        String content = readResource(resourcePath);
        Matcher matcher = AGENT_MARKDOWN.matcher(content);
        if (!matcher.matches()) {
            throw new IllegalStateException("Malformed agent resource: " + resourcePath);
        }

        FrontMatter frontMatter = readFrontMatter(matcher.group(1), resourcePath);
        return new AgentMarkdown(frontMatter, trimTrailingLineBreak(trimLeadingLineBreak(matcher.group(2))));
    }

    private static String readResource(String resourcePath) {
        try (InputStream inputStream = CatalogServicesTest.class.getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing test resource: " + resourcePath);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load test resource: " + resourcePath, e);
        }
    }

    private static FrontMatter readFrontMatter(String yaml, String resourcePath) {
        try {
            return YAML_MAPPER.readValue(yaml, FrontMatter.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse agent front matter: " + resourcePath, e);
        }
    }

    private static AgentDefinition loadInlineWildcardAgent(String filename, AgentMode mode) {
        try {
            return AgentDefinitionService.loadAgent(resource(filename, """
                    ---
                    id: %s
                    name: %s
                    description: %s
                    mode: %s
                    model: openai/gpt-5.5
                    reasoningEffort: high
                    textVerbosity: low
                    tools:
                      '*': true
                    ---
                    body
                    """.formatted(
                    filenameToId(filename),
                    displayName(filenameToId(filename)),
                    mode == AgentMode.AGENT ? "Primary wildcard tools" : "Subagent wildcard tools",
                    mode.name().toLowerCase()
            )));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load inline wildcard agent", e);
        }
    }

    private static void assertAgentMatchesResource(AgentDefinition actual, AgentResource expected, AgentDefinition wildcardPrimary, AgentDefinition wildcardSubagent) {
        assertThat(actual.name()).isEqualTo(displayName(expected.id()));
        assertThat(actual.description()).isEqualTo(expected.markdown().frontMatter().description());
        assertThat(actual.mode()).isEqualTo(expected.markdown().frontMatter().mode());
        assertThat(actual.defaultModel()).isEqualTo(expected.markdown().frontMatter().model());
        assertThat(actual.defaultThinkingLevel()).isEqualTo(expected.markdown().frontMatter().reasoningEffort());
        assertThat(actual.textVerbosity()).isEqualTo(expected.markdown().frontMatter().textVerbosity());
        assertThat(actual.systemPrompt()).isEqualTo(expected.markdown().body());
        assertThat(actual.allowWrite()).isEqualTo(actual.allowedTools().contains("write_file") || actual.allowedTools().contains("apply_patch"));
        assertThat(actual.allowCommand()).isEqualTo(actual.allowedTools().contains("run_command"));

        if (Boolean.TRUE.equals(expected.markdown().frontMatter().tools().get("*"))) {
            assertThat(actual.allowedTools()).isEqualTo(expected.markdown().frontMatter().mode() == AgentMode.AGENT
                    ? wildcardPrimary.allowedTools()
                    : wildcardSubagent.allowedTools());
            return;
        }

        assertThat(actual.allowedTools()).containsExactlyElementsOf(enabledTools(expected.markdown().frontMatter().tools()));
    }

    private static List<String> enabledTools(Map<String, Boolean> tools) {
        return tools.entrySet().stream()
                .filter(entry -> Boolean.TRUE.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
    }

    private static String filenameToId(String filename) {
        String baseName = filename.endsWith(".md") ? filename.substring(0, filename.length() - 3) : filename;
        return baseName.replaceFirst("^\\d+-", "");
    }

    private static String displayName(String id) {
        return java.util.Arrays.stream(id.split("[-_]"))
                .filter(part -> !part.isBlank())
                .map(part -> part.substring(0, 1).toUpperCase() + part.substring(1))
                .collect(Collectors.joining(" "));
    }

    private static String trimLeadingLineBreak(String value) {
        if (value.startsWith("\r\n")) {
            return value.substring(2);
        }
        if (value.startsWith("\n") || value.startsWith("\r")) {
            return value.substring(1);
        }
        return value;
    }

    private static String trimTrailingLineBreak(String value) {
        if (value.endsWith("\r\n")) {
            return value.substring(0, value.length() - 2);
        }
        if (value.endsWith("\n") || value.endsWith("\r")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    private record AgentMarkdown(FrontMatter frontMatter, String body) {
    }

    private record AgentResource(String path, AgentMarkdown markdown) {
        String id() {
            return filenameToId(path.substring(path.lastIndexOf('/') + 1));
        }
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

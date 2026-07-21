package com.judepereira.jupiter2.agent.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter2.testsupport.ModelCatalogTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class CatalogServicesTest {

    @Test
    public void agentCatalogLoadsPlanEngineerAndExploreWithExpectedDefaults() {
        AgentDefinitionService service = new AgentDefinitionService(new ObjectMapper());

        assertThat(service.list()).extracting(AgentDefinition::id)
                .containsExactly("plan", "engineer", "explore");
        assertThat(service.listPrimaryAgents()).extracting(AgentDefinition::id)
                .containsExactly("plan");
        assertThat(service.listSubagents()).extracting(AgentDefinition::id)
                .containsExactly("engineer", "explore");
        assertThat(service.defaultAgent().id()).isEqualTo("plan");

        AgentDefinition plan = service.getRequired("plan");
        assertThat(plan.allowWrite()).isFalse();
        assertThat(plan.allowCommand()).isFalse();
        assertThat(plan.allowedTools()).containsExactly("list_files", "read_file", "search_code");
        assertThat(plan.mode()).isEqualTo(AgentMode.AGENT);
        assertThat(plan.defaultModel()).isEqualTo("openai/gpt-5.5");
        assertThat(plan.defaultThinkingLevel()).isEqualTo(ThinkingLevel.HIGH);
        assertThat(plan.systemPrompt()).isEqualTo(
                "You are Plan, a read-only workspace planning assistant. Inspect the repository, identify the relevant files, explain the safest implementation approach, and do not modify files or run commands.");

        AgentDefinition engineer = service.getRequired("engineer");
        assertThat(engineer.name()).isEqualTo("Engineer");
        assertThat(engineer.description()).isEqualTo("An apprentice to a seasoned software engineer.");
        assertThat(engineer.allowWrite()).isTrue();
        assertThat(engineer.allowCommand()).isTrue();
        assertThat(engineer.mode()).isEqualTo(AgentMode.SUBAGENT);
        assertThat(engineer.defaultThinkingLevel()).isEqualTo(ThinkingLevel.MEDIUM);
        assertThat(engineer.defaultModel()).isEqualTo("openai/gpt-5.5");
        assertThat(engineer.textVerbosity()).isEqualTo("low");
        assertThat(engineer.allowedTools()).containsExactly(
                "list_files", "read_file", "search_code", "write_file", "apply_patch", "run_command");
        assertThat(engineer.systemPrompt()).isEqualTo(
                "You are an apprentice to a seasoned software engineer. Make the requested code changes directly, keep the diff minimal, and use workspace tools to inspect, edit, and run commands as needed.");

        AgentDefinition explore = service.getRequired("explore");
        assertThat(explore.name()).isEqualTo("Explore");
        assertThat(explore.description()).isEqualTo("A read-only exploration subagent that finds codebase context");
        assertThat(explore.allowWrite()).isFalse();
        assertThat(explore.allowCommand()).isFalse();
        assertThat(explore.mode()).isEqualTo(AgentMode.SUBAGENT);
        assertThat(explore.defaultThinkingLevel()).isEqualTo(ThinkingLevel.MEDIUM);
        assertThat(explore.defaultModel()).isEqualTo("openai/gpt-5.4-mini");
        assertThat(explore.textVerbosity()).isEqualTo("low");
        assertThat(explore.allowedTools()).containsExactly("list_files", "read_file", "search_code");
        assertThat(explore.systemPrompt()).isEqualTo(
                "You are Explore, a read-only codebase exploration subagent. Inspect the repository, find relevant files, symbols, and flows, and return concise findings with file, class, and method references. Do not edit files or run commands.");
    }

    @Test
    public void wildcardToolSupportIncludesTaskForPrimaryAgentsButNotSubagents() throws Exception {
        Method loadAgent = AgentDefinitionService.class.getDeclaredMethod("loadAgent", Resource.class);
        loadAgent.setAccessible(true);

        AgentDefinition primary = (AgentDefinition) loadAgent.invoke(null, resource("11-primary.md", """
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

        AgentDefinition subagent = (AgentDefinition) loadAgent.invoke(null, resource("12-subagent.md", """
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

        assertThat(primary.allowedTools()).containsExactly("list_files", "read_file", "search_code", "write_file", "apply_patch", "run_command", "task");
        assertThat(subagent.allowedTools()).containsExactly("list_files", "read_file", "search_code", "write_file", "apply_patch", "run_command");
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

        Method loadAgent = AgentDefinitionService.class.getDeclaredMethod("loadAgent", org.springframework.core.io.Resource.class);
        loadAgent.setAccessible(true);

        ByteArrayResource resource = new ByteArrayResource(frontMatter.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "99-missing-model.md";
            }
        };

        assertThatThrownBy(() -> loadAgent.invoke(null, resource))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("model is required for agent: missing-model");
    }

    @Test
    public void modelCatalogFiltersOpenAiModelsFromFetchedJson() {
        ModelCatalogService service = ModelCatalogTestSupport.modelCatalogService();

        assertThat(service.defaultModelId()).isEqualTo("openai/gpt-5.5");
        assertThat(service.list()).extracting(ModelDefinition::id)
                .containsExactly("openai/gpt-5.5", "openai/gpt-5.5-pro");
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
}

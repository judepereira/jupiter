package com.judepereira.jupiter2.agent.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter2.testsupport.ModelCatalogTestSupport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class CatalogServicesTest {

    @Test
    public void agentCatalogLoadsPlanAndEngineerWithExpectedDefaults() {
        AgentDefinitionService service = new AgentDefinitionService(new ObjectMapper());

        assertThat(service.list()).extracting(AgentDefinition::id)
                .containsExactly("plan", "engineer");
        assertThat(service.defaultAgent().id()).isEqualTo("plan");

        AgentDefinition plan = service.getRequired("plan");
        assertThat(plan.allowWrite()).isFalse();
        assertThat(plan.allowCommand()).isFalse();
        assertThat(plan.allowedTools()).containsExactly("list_files", "read_file", "search_code");

        AgentDefinition engineer = service.getRequired("engineer");
        assertThat(engineer.allowWrite()).isTrue();
        assertThat(engineer.allowCommand()).isTrue();
        assertThat(engineer.defaultThinkingLevel()).isEqualTo(ThinkingLevel.MEDIUM);
        assertThat(engineer.defaultModel()).isEqualTo("openai/gpt-5.5");
        assertThat(engineer.allowedTools()).containsExactly(
                "list_files", "read_file", "search_code", "write_file", "apply_patch", "run_command");
    }

    @Test
    public void modelCatalogFiltersOpenAiModelsFromFetchedJson() {
        ModelCatalogService service = ModelCatalogTestSupport.modelCatalogService();

        assertThat(service.defaultModelId()).isEqualTo("openai/gpt-5.5");
        assertThat(service.list()).extracting(ModelDefinition::id)
                .containsExactly("openai/gpt-5.5", "openai/gpt-5.5-pro");
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
                    "openai/bad-model": {
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
}

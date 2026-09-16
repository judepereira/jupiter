package com.judepereira.jupiter.agent.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.judepereira.jupiter.testsupport.ModelCatalogTestSupport;
import org.junit.jupiter.api.Test;

class ModelCatalogFocusedTests {
    @Test
    void parsesMetadataAndFiltersIneligibleModels() {
        ModelCatalogService catalog = ModelCatalogTestSupport.modelCatalogService("http://example.test/models.json",
                """
                        {"models": {
                          "openai/gpt-5.6-sol": %s,
                          "openai/kept": %s,
                          "openai/no-tools": %s,
                          "openai/no-input-text": %s,
                          "openai/realtime": %s,
                          "openai/embedding": %s,
                          "google/gemini": %s,
                          "anthropic/not-claude": %s,
                          "anthropic/claude-good": %s
                        }}
                        """.formatted(model("openai/gpt-5.6-sol", "Sol", "2026-01-01", "sol", true, "text", "text"),
                        model("openai/kept", "Kept", "2026-02-03", "kept", true, "text", "text"),
                        model("openai/no-tools", "No tools", "2026-02-03", "x", false, "text", "text"),
                        model("openai/no-input-text", "No text", "2026-02-03", "x", true, "image", "text"),
                        model("openai/realtime", "GPT realtime", "2026-02-03", "x", true, "text", "text"),
                        model("openai/embedding", "Embedding", "2026-02-03", "x", true, "text", "embedding"),
                        model("google/gemini", "Gemini", "2026-02-03", "x", true, "text", "text"),
                        model("anthropic/not-claude", "Other", "2026-02-03", "x", true, "text", "text"),
                        model("anthropic/claude-good", "Claude", "2026-02-03", "claude-good", true, "text", "text")));

        assertThat(catalog.list()).extracting(ModelDefinition::id).containsExactly("openai/gpt-5.6-sol", "openai/kept",
                "anthropic/claude-good");
        var kept = catalog.getRequired("openai/kept");
        assertThat(kept.family()).isEqualTo("kept");
        assertThat(kept.releaseDate()).isEqualTo("2026-02-03");
        assertThat(kept.lastUpdated()).isEqualTo("2026-02-03");
        assertThat(kept.inputModalities()).containsExactly("text");
        assertThat(kept.outputModalities()).containsExactly("text");
    }

    @Test
    void ignoresMalformedUnsupportedProviderEntriesBeforeValidation() {
        ModelCatalogService catalog = ModelCatalogTestSupport.modelCatalogService("http://example.test/models.json", """
                {"models": {
                  "openai/gpt-5.6-sol": %s,
                  "google/missing-id": {"name":"bad"},
                  "google/duplicate-a": {"id":"google/same"},
                  "google/duplicate-b": {"id":"google/same"}
                }}
                """.formatted(model("openai/gpt-5.6-sol", "Sol", "2026-01-01", "sol", true, "text", "text")));

        assertThat(catalog.list()).extracting(ModelDefinition::id).containsExactly("openai/gpt-5.6-sol");
    }

    @Test
    void validatesMalformedSupportedProviderEntries() {
        assertThatThrownBy(() -> ModelCatalogTestSupport.modelCatalogService("http://example.test/missing.json", """
                {"models":{"openai/gpt-5.6-sol":{"name":"bad"}}}
                """)).hasRootCauseMessage("Model id is required");
    }

    @Test
    void rejectsSupportedKeyWithUnsupportedEmbeddedProvider() {
        assertThatThrownBy(() -> ModelCatalogTestSupport.modelCatalogService("http://example.test/mismatch.json", """
                {"models":{"openai/gpt-5.6-sol":{"id":"google/gpt-5.6-sol"}}}
                """)).hasRootCauseMessage(
                "Model catalog key does not match model id: openai/gpt-5.6-sol != google/gpt-5.6-sol");
    }

    @Test
    void rejectsSupportedKeyAndEmbeddedIdMismatch() {
        assertThatThrownBy(() -> ModelCatalogTestSupport.modelCatalogService("http://example.test/mismatch.json", """
                {"models":{"openai/gpt-5.6-sol":{"id":"openai/other"}}}
                """))
                .hasRootCauseMessage("Model catalog key does not match model id: openai/gpt-5.6-sol != openai/other");
    }

    @Test
    void selectsNewestDistinctRepresentativesWithDeterministicTies() {
        ModelCatalogService catalog = ModelCatalogTestSupport.modelCatalogService("http://example.test/defaults.json",
                """
                        {"models": {
                          "openai/gpt-5.6-sol": %s,
                          "openai/base": %s, "openai/base-20260401": %s, "openai/base-fast": %s, "openai/base-preview": %s,
                          "openai/new": %s, "openai/new-old": %s,
                          "openai/tie-b": %s, "openai/tie-a": %s,
                          "openai/missing-b": %s, "openai/missing-a": %s,
                          "openai/extra": %s
                        }}
                        """
                        .formatted(model("openai/gpt-5.6-sol", "Default", "2026-01-01", null, true, "text", "text"),
                                model("openai/base", "Base", "2026-03-01", "base", true, "text", "text"),
                                model("openai/base-20260401", "Base 20260401", "2026-03-01", "base", true, "text",
                                        "text"),
                                model("openai/base-fast", "Base Fast", "2026-03-01", "base", true, "text", "text"),
                                model("openai/base-preview", "Base Preview", "2026-03-01", "base", true, "text",
                                        "text"),
                                model("openai/new", "New", "2026-04-01", "new", true, "text", "text"),
                                model("openai/new-old", "Old", "2025-01-01", "new", true, "text", "text"),
                                model("openai/tie-b", "Tie B", "2025-02-01", "tie", true, "text", "text"),
                                model("openai/tie-a", "Tie A", "2025-02-01", "tie", true, "text", "text"),
                                model("openai/missing-b", "Missing B", "2026-02-01", null, true, "text", "text"),
                                model("openai/missing-a", "Missing A", "2026-02-01", null, true, "text", "text"),
                                model("openai/extra", "Extra", "2020-01-01", "extra", true, "text", "text")));

        assertThat(catalog.latestDistinctFamilyModelIds("openai")).containsExactly("openai/new", "openai/base",
                "openai/missing-a", "openai/missing-b", "openai/gpt-5.6-sol");
        assertThat(catalog.latestDistinctFamilyModelIds("anthropic")).isEmpty();
    }

    private static String model(String id, String name, String date, String family, boolean tools, String input,
            String output) {
        String familyJson = family == null ? "" : ",\"family\":\"%s\"".formatted(family);
        return "{\"id\":\"%s\",\"name\":\"%s\",\"tool_call\":%s,\"release_date\":\"%s\",\"last_updated\":\"%s\",\"family\":%s,\"modalities\":{\"input\":[\"%s\"],\"output\":[\"%s\"]},\"limit\":{\"context\":1,\"output\":1}}"
                .formatted(id, name, tools, date, date, family == null ? "null" : "\"" + family + "\"", input, output);
    }
}

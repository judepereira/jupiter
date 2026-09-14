package com.judepereira.jupiter.agent.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.config.ModelCatalogProperties;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Spliterators;
import java.util.stream.StreamSupport;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class ModelCatalogService {

    private static final String DEFAULT_MODEL_ID = "openai/gpt-5.6-sol";
    private static final String GPT_5_6_MODEL_PREFIX = "openai/gpt-5.6";
    private static final String ANTHROPIC_MODEL_PREFIX = "anthropic/claude-";

    private final List<ModelDefinition> models;
    private final Map<String, ModelDefinition> modelsById;

    public ModelCatalogService(
            ObjectMapper objectMapper,
            RestClient.Builder restClientBuilder,
            ModelCatalogProperties properties) {
        this.models =
                loadModels(objectMapper, restClientBuilder.build(), properties.getCatalogUrl());
        this.modelsById = indexModels(models);
        getRequired(DEFAULT_MODEL_ID);
    }

    public List<ModelDefinition> list() {
        return models;
    }

    public ModelDefinition getRequired(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Model id is required");
        }
        var model = modelsById.get(id);
        if (model == null) {
            throw new IllegalArgumentException("Unknown model id: " + id);
        }
        return model;
    }

    public ModelDefinition resolveOrDefault(String id) {
        if (id == null || id.isBlank()) {
            return getRequired(DEFAULT_MODEL_ID);
        }
        return modelsById.getOrDefault(id, getRequired(DEFAULT_MODEL_ID));
    }

    /** Resolves a bundled preference exactly; provider-wide substitution is not supported. */
    public ModelDefinition resolveBundledModel(String id) {
        return getRequired(id);
    }

    public boolean hasProviderModel(String provider) {
        return models.stream().anyMatch(model -> model.provider().equals(provider));
    }

    public String defaultModelId() {
        return DEFAULT_MODEL_ID;
    }

    private static List<ModelDefinition> loadModels(
            ObjectMapper objectMapper, RestClient restClient, String catalogUrl) {
        try {
            String body;
            if (catalogUrl.startsWith("file:")) {
                body = Files.readString(Path.of(URI.create(catalogUrl)));
            } else {
                body = restClient.get().uri(catalogUrl).retrieve().body(String.class);
            }
            var root = objectMapper.readTree(body);
            var modelsNode = root.path("models");
            var openAiModels =
                    StreamSupport.stream(
                                    Spliterators.spliteratorUnknownSize(modelsNode.fields(), 0),
                                    false)
                            .filter(
                                    entry ->
                                            entry.getKey().equals(GPT_5_6_MODEL_PREFIX)
                                                    || entry.getKey()
                                                            .startsWith(GPT_5_6_MODEL_PREFIX + "-")
                                                    || entry.getKey()
                                                            .startsWith(ANTHROPIC_MODEL_PREFIX))
                            .map(Map.Entry::getValue)
                            .map(ModelCatalogService::toModelDefinition)
                            .toList();
            validateModels(openAiModels);
            return List.copyOf(openAiModels);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to load model catalog from models.dev: " + catalogUrl, e);
        }
    }

    private static ModelDefinition toModelDefinition(JsonNode node) {
        var id = node.path("id").asText();
        var displayName = node.path("name").asText();
        int separator = id.indexOf('/');
        var provider = separator > 0 ? id.substring(0, separator) : id;
        var apiModelId = separator > 0 ? id.substring(separator + 1) : id;
        var limit = node.path("limit");
        return new ModelDefinition(
                id,
                displayName,
                provider,
                apiModelId,
                node.path("reasoning").asBoolean(),
                node.path("tool_call").asBoolean(),
                limit.path("context").asInt(),
                limit.path("output").asInt(),
                null,
                null,
                node.path("release_date").asText(null));
    }

    private static Map<String, ModelDefinition> indexModels(List<ModelDefinition> models) {
        Map<String, ModelDefinition> indexed = new LinkedHashMap<>();
        for (ModelDefinition model : models) {
            indexed.put(model.id(), model);
        }
        return Collections.unmodifiableMap(indexed);
    }

    private static void validateModels(List<ModelDefinition> models) {
        if (models == null || models.isEmpty()) {
            throw new IllegalStateException("Model catalog is empty");
        }

        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (ModelDefinition model : models) {
            if (model.id() == null || model.id().isBlank()) {
                throw new IllegalStateException("Model id is required");
            }
            if (seen.put(model.id(), Boolean.TRUE) != null) {
                throw new IllegalStateException("Duplicate model id: " + model.id());
            }
            if (model.apiModelId() == null || model.apiModelId().isBlank()) {
                throw new IllegalStateException("apiModelId is required for model: " + model.id());
            }
        }
    }
}

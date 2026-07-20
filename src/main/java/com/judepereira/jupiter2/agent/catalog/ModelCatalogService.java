package com.judepereira.jupiter2.agent.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.judepereira.jupiter2.agent.config.ModelCatalogProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Spliterators;
import java.util.stream.StreamSupport;

@Service
public class ModelCatalogService {

    private static final String DEFAULT_MODEL_ID = "openai/gpt-5.5";
    private static final String OPENAI_PROVIDER = "openai";
    private static final String GPT_5_MODEL_PREFIX = "openai/gpt-5";

    private final List<ModelDefinition> models;
    private final Map<String, ModelDefinition> modelsById;

    public ModelCatalogService(ObjectMapper objectMapper, RestClient.Builder restClientBuilder, ModelCatalogProperties properties) {
        this.models = loadModels(objectMapper, restClientBuilder.build(), properties.getCatalogUrl());
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

    public String defaultModelId() {
        return DEFAULT_MODEL_ID;
    }

    private static List<ModelDefinition> loadModels(ObjectMapper objectMapper, RestClient restClient, String catalogUrl) {
        try {
            String body = restClient.get().uri(catalogUrl).retrieve().body(String.class);
            var root = objectMapper.readTree(body);
            var modelsNode = root.path("models");
            var openAiModels = StreamSupport.stream(Spliterators.spliteratorUnknownSize(modelsNode.fields(), 0), false)
                    .filter(entry -> entry.getKey().startsWith(GPT_5_MODEL_PREFIX))
                    .map(Map.Entry::getValue)
                    .map(ModelCatalogService::toModelDefinition)
                    .toList();
            validateModels(openAiModels);
            return List.copyOf(openAiModels);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load model catalog from models.dev: " + catalogUrl, e);
        }
    }

    private static ModelDefinition toModelDefinition(JsonNode node) {
        var id = node.path("id").asText();
        var displayName = node.path("name").asText();
        var apiModelId = id.startsWith(OPENAI_PROVIDER + "/") ? id.substring(OPENAI_PROVIDER.length() + 1) : id;
        var limit = node.path("limit");
        return new ModelDefinition(
                id,
                displayName,
                OPENAI_PROVIDER,
                apiModelId,
                node.path("reasoning").asBoolean(),
                node.path("tool_call").asBoolean(),
                limit.path("context").asInt(),
                limit.path("output").asInt(),
                null,
                null,
                node.path("release_date").asText(null)
        );
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

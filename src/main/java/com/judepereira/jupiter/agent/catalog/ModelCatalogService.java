package com.judepereira.jupiter.agent.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.config.ModelCatalogProperties;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Spliterators;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class ModelCatalogService {
    private static final String DEFAULT_MODEL_ID = "openai/gpt-5.6-sol";
    private static final List<String> OPENAI_SPECIALIZED_TERMS = List.of("realtime", "audio", "transcription", "tts",
            "text-to-speech", "image-generation", "image_generation", "image", "search", "embedding", "moderation");

    private final List<ModelDefinition> models;
    private final Map<String, ModelDefinition> modelsById;

    public ModelCatalogService(ObjectMapper objectMapper, RestClient.Builder restClientBuilder,
            ModelCatalogProperties properties) {
        this.models = loadModels(objectMapper, restClientBuilder.build(), properties.getCatalogUrl());
        this.modelsById = indexModels(models);
        getRequired(DEFAULT_MODEL_ID);
    }

    public List<ModelDefinition> list() {
        return models;
    }

    public ModelDefinition getRequired(String id) {
        if (id == null || id.isBlank())
            throw new IllegalArgumentException("Model id is required");
        var model = modelsById.get(id);
        if (model == null)
            throw new IllegalArgumentException("Unknown model id: " + id);
        return model;
    }

    public ModelDefinition resolveOrDefault(String id) {
        return id == null || id.isBlank()
                ? getRequired(DEFAULT_MODEL_ID)
                : modelsById.getOrDefault(id, getRequired(DEFAULT_MODEL_ID));
    }

    public ModelDefinition resolveBundledModel(String id) {
        return getRequired(id);
    }
    public boolean hasProviderModel(String provider) {
        return models.stream().anyMatch(m -> m.provider().equals(provider));
    }
    public String defaultModelId() {
        return DEFAULT_MODEL_ID;
    }

    public List<String> latestDistinctFamilyModelIds(String provider) {
        return models.stream().filter(m -> provider.equals(m.provider()))
                .collect(Collectors.toMap(m -> m.family() == null || m.family().isBlank() ? m.id() : m.family(),
                        Function.identity(), ModelCatalogService::preferredFamilyModel, LinkedHashMap::new))
                .values().stream()
                .sorted(Comparator
                        .comparing(ModelDefinition::releaseDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(ModelDefinition::lastUpdated, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(ModelDefinition::id))
                .limit(5).map(ModelDefinition::id).toList();
    }

    private static ModelDefinition preferredFamilyModel(ModelDefinition a, ModelDefinition b) {
        int release = compareNullable(a.releaseDate(), b.releaseDate());
        if (release != 0)
            return release > 0 ? a : b;
        int updated = compareNullable(a.lastUpdated(), b.lastUpdated());
        if (updated != 0)
            return updated > 0 ? a : b;
        int canonical = Integer.compare(canonicalScore(a), canonicalScore(b));
        if (canonical != 0)
            return canonical < 0 ? a : b;
        return a.id().compareTo(b.id()) <= 0 ? a : b;
    }

    private static int canonicalScore(ModelDefinition model) {
        String value = (model.id() + " " + model.displayName()).toLowerCase(Locale.ROOT);
        int score = 0;
        if (value.matches(".*(-fast|\\bmini\\b|\\bnano\\b|preview|\\d{4}(?:-\\d{2}-\\d{2}|\\d{4})).*"))
            score++;
        return score;
    }

    private static int compareNullable(String left, String right) {
        if (left == null)
            return right == null ? 0 : -1;
        if (right == null)
            return 1;
        return left.compareTo(right);
    }

    private static List<ModelDefinition> loadModels(ObjectMapper mapper, RestClient client, String url) {
        try {
            String body = url.startsWith("file:")
                    ? Files.readString(Path.of(URI.create(url)))
                    : client.get().uri(url).retrieve().body(String.class);
            var fields = mapper.readTree(body).path("models");
            var allModels = StreamSupport.stream(Spliterators.spliteratorUnknownSize(fields.fields(), 0), false)
                    .filter(entry -> supportedProvider(entry.getKey())).map(entry -> {
                        validateCatalogEntry(entry.getKey(), entry.getValue());
                        return toModelDefinition(entry.getValue());
                    }).toList();
            validateModels(allModels);
            var eligible = allModels.stream().filter(ModelCatalogService::eligible).toList();
            if (eligible.isEmpty())
                throw new IllegalStateException("Model catalog is empty");
            return eligible;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load model catalog from models.dev: " + url, e);
        }
    }

    private static boolean supportedProvider(String modelKey) {
        int separator = modelKey.indexOf('/');
        if (separator <= 0)
            return false;
        String provider = modelKey.substring(0, separator);
        return provider.equals("openai") || provider.equals("anthropic");
    }

    private static void validateCatalogEntry(String key, JsonNode node) {
        JsonNode idNode = node.get("id");
        if (idNode == null || !idNode.isTextual() || idNode.asText().isBlank())
            throw new IllegalStateException("Model id is required");
        if (!key.equals(idNode.asText()))
            throw new IllegalStateException(
                    "Model catalog key does not match model id: " + key + " != " + idNode.asText());
    }

    private static ModelDefinition toModelDefinition(JsonNode node) {
        String id = node.path("id").asText();
        int separator = id.indexOf('/');
        var limit = node.path("limit");
        return new ModelDefinition(id, node.path("name").asText(), separator > 0 ? id.substring(0, separator) : id,
                separator > 0 ? id.substring(separator + 1) : id, node.path("reasoning").asBoolean(),
                node.path("tool_call").asBoolean(), limit.path("context").asInt(), limit.path("output").asInt(), null,
                null, node.path("release_date").asText(null), node.path("family").asText(null),
                node.path("last_updated").asText(null), textValues(node.path("modalities").path("input")),
                textValues(node.path("modalities").path("output")));
    }

    private static List<String> textValues(JsonNode node) {
        if (!node.isArray())
            return List.of("text");
        return StreamSupport.stream(node.spliterator(), false).map(JsonNode::asText).toList();
    }

    private static boolean eligible(ModelDefinition m) {
        if (!(m.provider().equals("openai") || m.provider().equals("anthropic")) || !m.supportsTools()
                || !m.inputModalities().contains("text") || !m.outputModalities().contains("text"))
            return false;
        if (m.provider().equals("anthropic"))
            return m.id().startsWith("anthropic/claude-");
        String value = (m.id() + " " + m.displayName()).toLowerCase(Locale.ROOT);
        return OPENAI_SPECIALIZED_TERMS.stream().noneMatch(value::contains);
    }

    private static Map<String, ModelDefinition> indexModels(List<ModelDefinition> models) {
        Map<String, ModelDefinition> indexed = new LinkedHashMap<>();
        for (var model : models)
            indexed.put(model.id(), model);
        return Collections.unmodifiableMap(indexed);
    }

    private static void validateModels(List<ModelDefinition> models) {
        if (models.isEmpty())
            throw new IllegalStateException("Model catalog is empty");
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (var model : models) {
            if (model.id() == null || model.id().isBlank())
                throw new IllegalStateException("Model id is required");
            if (seen.put(model.id(), true) != null)
                throw new IllegalStateException("Duplicate model id: " + model.id());
            if (model.apiModelId() == null || model.apiModelId().isBlank())
                throw new IllegalStateException("apiModelId is required for model: " + model.id());
        }
    }
}

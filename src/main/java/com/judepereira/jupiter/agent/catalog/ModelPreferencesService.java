package com.judepereira.jupiter.agent.catalog;

import com.judepereira.jupiter.persistence.AppStateRepository;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ModelPreferencesService {
    private final AppStateRepository repository;
    private final ModelCatalogService catalog;

    public ModelPreferencesService(AppStateRepository repository, ModelCatalogService catalog) {
        this.repository = repository;
        this.catalog = catalog;
    }

    public void initializeProvider(String provider, Collection<String> defaults) {
        validateProvider(provider);
        var ids = new LinkedHashSet<>(defaults);
        ids.forEach(id -> validateModel(provider, id));
        repository.initializeSelectedModelIds(provider, ids);
    }

    public List<String> selectedModelIds(String provider) {
        validateProvider(provider);
        return repository.loadSelectedModelIds(provider);
    }

    @Transactional
    public void replaceSelectedModelIds(String provider, Collection<String> modelIds) {
        validateProvider(provider);
        replaceSelectedModelIdsInternal(provider, modelIds, true);
    }

    public List<ModelDefinition> selectedModels(String provider) {
        validateProvider(provider);
        return selectedModelIds(provider).stream()
                .map(id -> catalog.list().stream()
                        .filter(model -> model.id().equals(id) && provider.equals(model.provider())).findFirst()
                        .orElse(null))
                .filter(model -> model != null).toList();
    }

    private void replaceSelectedModelIdsInternal(String provider, Collection<String> modelIds, boolean requireOne) {
        var ids = new LinkedHashSet<>(modelIds);
        ids.forEach(id -> validateModel(provider, id));
        if (requireOne && ids.isEmpty())
            throw new IllegalArgumentException("At least one model must be selected");
        repository.replaceSelectedModelIds(provider, ids);
    }

    private void validateProvider(String provider) {
        if (provider == null || provider.isBlank() || !catalog.hasProviderModel(provider))
            throw new IllegalArgumentException("Unsupported model provider: " + provider);
    }

    private void validateModel(String provider, String modelId) {
        if (modelId == null || !provider.equals(catalog.getRequired(modelId).provider()))
            throw new IllegalArgumentException("Model does not belong to provider: " + modelId);
    }
}

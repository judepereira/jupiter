package com.judepereira.jupiter.agent.catalog;

import com.judepereira.jupiter.persistence.AppStateRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ModelPreferencesService {
    private final AppStateRepository repository;
    private final ModelCatalogService catalog;

    public ModelPreferencesService(AppStateRepository repository, ModelCatalogService catalog) {
        this.repository = repository;
        this.catalog = catalog;
    }

    public void initializeProvider(String provider, String defaultModelId) {
        if (repository.isProviderInitialized(provider))
            return;
        var favourites = new ArrayList<>(favouriteModelIds());
        boolean hasProviderFavourite = favourites.stream().map(this::resolveKnownModel)
                .anyMatch(model -> model != null && model.provider().equals(provider));
        if (!hasProviderFavourite && defaultModelId != null)
            favourites.add(defaultModelId);
        repository.updateFavouriteModelIds(favourites);
        repository.updateProviderInitialized(provider, true);
    }

    public List<String> favouriteModelIds() {
        return repository.loadFavouriteModelIds();
    }

    public boolean isFavourite(String modelId) {
        return favouriteModelIds().contains(modelId);
    }

    public void setFavourite(String modelId, boolean favourite) {
        var ids = new ArrayList<>(favouriteModelIds());
        ids.remove(modelId);
        if (favourite)
            ids.add(modelId);
        repository.updateFavouriteModelIds(ids);
    }

    public List<ModelDefinition> favouriteModels() {
        return favouriteModelIds().stream().map(this::resolveKnownModel).filter(model -> model != null).toList();
    }

    private ModelDefinition resolveKnownModel(String id) {
        try {
            return catalog.getRequired(id);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}

package com.judepereira.jupiter.agent.catalog;

import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class ModelPickerService {
    private final ModelPreferencesService preferences;
    private final ProviderAvailabilityService availability;

    public ModelPickerService(ModelPreferencesService preferences, ProviderAvailabilityService availability) {
        this.preferences = preferences;
        this.availability = availability;
    }

    public List<ModelDefinition> listPickerModels() {
        return preferences.favouriteModels().stream()
                .filter(model -> availability.isAvailable(model.provider()))
                .toList();
    }
}

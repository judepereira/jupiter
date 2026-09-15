package com.judepereira.jupiter.agent.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ModelPickerService {
    private final ModelPreferencesService preferences;
    private final ProviderAvailabilityService availability;
    private final AgentDefinitionService agentDefinitions;
    private final ModelCatalogService catalog;

    public ModelPickerService(ModelPreferencesService preferences, ProviderAvailabilityService availability,
            AgentDefinitionService agentDefinitions, ModelCatalogService catalog) {
        this.preferences = preferences;
        this.availability = availability;
        this.agentDefinitions = agentDefinitions;
        this.catalog = catalog;
    }

    public List<ModelDefinition> listPickerModels() {
        Map<String, ModelDefinition> models = new LinkedHashMap<>();
        for (String provider : List.of("openai", "anthropic")) {
            if (!availability.isAvailable(provider))
                continue;
            preferences.selectedModels(provider).forEach(model -> models.putIfAbsent(model.id(), model));
        }
        for (AgentDefinition agent : agentDefinitions.list()) {
            for (String modelId : agent.modelIds()) {
                ModelDefinition model = catalog.getRequired(modelId);
                if (availability.isAvailable(model.provider()))
                    models.putIfAbsent(model.id(), model);
            }
        }
        return List.copyOf(models.values());
    }
}

package com.judepereira.jupiter.agent.catalog;

import org.springframework.stereotype.Service;

@Service
public class AgentModelResolutionService {
    private final ModelCatalogService catalog;
    private final ProviderAvailabilityService availability;

    public AgentModelResolutionService(
            ModelCatalogService catalog, ProviderAvailabilityService availability) {
        this.catalog = catalog;
        this.availability = availability;
    }

    /** Resolves an agent for execution, requiring a configured provider. */
    public ModelResolution resolve(AgentDefinition agent) {
        for (String id : agent.modelIds()) {
            ModelDefinition model = catalog.getRequired(id);
            if (availability.isAvailable(model.provider()))
                return new ModelResolution(agent.defaultModel(), model);
        }
        throw new IllegalStateException(
                "No configured provider is available for agent '" + agent.id() + "'");
    }

    /**
     * Resolves the catalog preference for display; provider availability is intentionally not
     * checked.
     */
    public ModelResolution resolveForDisplay(AgentDefinition agent) {
        ModelDefinition model = catalog.getRequired(agent.defaultModel());
        return new ModelResolution(agent.defaultModel(), model);
    }

    public record ModelResolution(String preferredModelId, ModelDefinition model) {
        public boolean fallback() {
            return !preferredModelId.equals(model.id());
        }
    }
}

package com.judepereira.jupiter.agent.catalog;

import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.Persistence.AgentModelPreference;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/** Validates the user-facing agent preference contract before persistence. */
@Service
public class AgentPreferenceService {
    private final AppStateService appState;
    private final AgentDefinitionService agents;
    private final ModelCatalogService catalog;
    private final ProviderAvailabilityService availability;
    private final ModelPickerService modelPicker;

    public AgentPreferenceService(AppStateService appState, AgentDefinitionService agents, ModelCatalogService catalog,
            ProviderAvailabilityService availability, ModelPickerService modelPicker) {
        this.appState = appState;
        this.agents = agents;
        this.catalog = catalog;
        this.availability = availability;
        this.modelPicker = modelPicker;
    }

    public List<AgentPreferenceView> list() {
        return agents.list().stream().map(this::view).toList();
    }

    public void save(String agentId, String modelId, String thinkingLevel) {
        AgentDefinition agent = agents.getRequired(agentId);
        String model = blankToNull(modelId);
        String thinking = blankToNull(thinkingLevel);
        if (model != null) {
            ModelDefinition definition = catalog.getRequired(model);
            if (!isEligible(definition)) {
                throw new IllegalArgumentException("Model is not an eligible text-and-tools model: " + model);
            }
            // A disconnected provider is intentionally allowed: execution will report the
            // failure clearly.
            availability.isAvailable(definition.provider());
        }
        if (thinking != null) {
            thinking = ThinkingLevel.fromValue(thinking).name();
        }
        appState.upsertAgentModelPreference(new AgentModelPreference(agent.id(), model, thinking));
    }

    public void reset(String agentId) {
        agents.getRequired(agentId);
        appState.resetAgentModelPreference(agentId);
    }

    private AgentPreferenceView view(AgentDefinition agent) {
        Optional<AgentModelPreference> saved = appState.findAgentModelPreference(agent.id());
        String modelId = saved.map(AgentModelPreference::modelId).orElse(null);
        ModelDefinition model = null;
        boolean stale = false;
        boolean unavailable = false;
        if (modelId != null) {
            try {
                model = catalog.getRequired(modelId);
                unavailable = !availability.isAvailable(model.provider());
            } catch (IllegalArgumentException e) {
                stale = true;
            }
        }
        List<ModelDefinition> eligible = modelPicker.listConfiguredModels().stream().filter(this::isEligible).toList();
        ModelDefinition savedModel = model;
        if (savedModel != null && eligible.stream().noneMatch(candidate -> candidate.id().equals(savedModel.id()))) {
            eligible = Stream.concat(eligible.stream(), Stream.of(savedModel)).toList();
        }
        return new AgentPreferenceView(agent, saved.orElse(null), model, eligible, stale, unavailable);
    }

    private boolean isEligible(ModelDefinition model) {
        return model.supportsTools() && model.inputModalities() != null
                && model.inputModalities().stream().anyMatch(modality -> "text".equalsIgnoreCase(modality));
    }

    private Optional<ModelDefinition> findModel(String id) {
        try {
            return Optional.of(catalog.getRequired(id));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record AgentPreferenceView(AgentDefinition agent, AgentModelPreference saved, ModelDefinition model,
            List<ModelDefinition> eligibleModels, boolean staleModel, boolean unavailableModel) {
        public String thinkingLevel() {
            return saved == null ? null : saved.thinkingLevel();
        }
    }
}

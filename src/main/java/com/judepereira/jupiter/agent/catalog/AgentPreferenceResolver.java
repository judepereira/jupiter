package com.judepereira.jupiter.agent.catalog;

import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.Persistence.AgentModelPreference;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Owns precedence and validation of durable agent preferences. */
@Service
public class AgentPreferenceResolver {
    private final AppStateService appStateService;
    private final ModelCatalogService modelCatalog;
    private final ProviderAvailabilityService availability;
    private final AgentModelResolutionService modelResolutionService;

    public AgentPreferenceResolver(AppStateService appStateService, ModelCatalogService modelCatalog,
            ProviderAvailabilityService availability, AgentModelResolutionService modelResolutionService) {
        this.appStateService = appStateService;
        this.modelCatalog = modelCatalog;
        this.availability = availability;
        this.modelResolutionService = modelResolutionService;
    }

    /** Resolves preferences for execution; selected providers must be available. */
    public Resolution resolve(AgentDefinition agent, String explicitModelId, ThinkingLevel explicitThinking,
            boolean strictSnapshot) {
        Optional<AgentModelPreference> saved = appStateService.findAgentModelPreference(agent.id());
        String modelId = explicitModelId;
        boolean strict = strictSnapshot;
        String preferred = null;
        if (modelId == null || modelId.isBlank()) {
            modelId = saved.map(AgentModelPreference::modelId).orElse(null);
            strict = modelId != null;
            if (strict) {
                ModelDefinition model;
                model = modelCatalog.getRequired(modelId);
                if (!availability.isAvailable(model.provider())) {
                    throw new IllegalStateException(
                            "Saved model override is unavailable for agent '" + agent.id() + "': " + modelId);
                }
                ThinkingLevel thinking = explicitThinking != null
                        ? explicitThinking
                        : saved.map(AgentModelPreference::thinkingLevel).map(ThinkingLevel::fromValue)
                                .orElse(agent.defaultThinkingLevel());
                return new Resolution(model, model.id(), thinking, true);
            }
        }
        ModelDefinition model;
        if (modelId != null && !modelId.isBlank()) {
            model = modelCatalog.getRequired(modelId);
            if (!availability.isAvailable(model.provider())) {
                throw new IllegalStateException("Selected model provider is unavailable: " + modelId);
            }
            preferred = model.id();
        } else {
            AgentModelResolutionService.ModelResolution fallback = modelResolutionService.resolve(agent);
            model = fallback.model();
            preferred = fallback.preferredModelId();
        }
        ThinkingLevel thinking = explicitThinking;
        if (thinking == null) {
            thinking = saved.flatMap(p -> Optional.ofNullable(p.thinkingLevel())).map(ThinkingLevel::fromValue)
                    .orElse(agent.defaultThinkingLevel());
        }
        return new Resolution(model, preferred, thinking, strict);
    }

    /**
     * Resolves the catalog preference for rendering controls without requiring a
     * provider.
     */
    public Resolution resolveForDisplay(AgentDefinition agent) {
        Optional<AgentModelPreference> saved = appStateService.findAgentModelPreference(agent.id());
        ModelDefinition model;
        String savedModelId = saved.map(AgentModelPreference::modelId).orElse(null);
        if (savedModelId != null && !savedModelId.isBlank()) {
            try {
                model = modelCatalog.getRequired(savedModelId);
            } catch (IllegalArgumentException e) {
                // Settings reports stale preferences; chat controls remain renderable using the
                // bundled model.
                model = modelResolutionService.resolveForDisplay(agent).model();
            }
        } else {
            model = modelResolutionService.resolveForDisplay(agent).model();
        }
        ThinkingLevel thinking = saved.flatMap(p -> Optional.ofNullable(p.thinkingLevel()))
                .map(ThinkingLevel::fromValue).orElse(agent.defaultThinkingLevel());
        return new Resolution(model, model.id(), thinking, false);
    }

    public record Resolution(ModelDefinition model, String preferredModelId, ThinkingLevel thinkingLevel,
            boolean strictModel) {
    }
}

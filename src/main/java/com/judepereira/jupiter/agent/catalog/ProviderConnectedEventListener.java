package com.judepereira.jupiter.agent.catalog;

import java.util.Comparator;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class ProviderConnectedEventListener {
    private static final String OPENAI = "openai";
    private static final String ANTHROPIC = "anthropic";
    private static final String OPENAI_DEFAULT = "openai/gpt-5.6-sol";

    private final ModelCatalogService catalog;
    private final ModelPreferencesService preferences;
    private final ProviderAvailabilityService availability;

    public ProviderConnectedEventListener(ModelCatalogService catalog, ModelPreferencesService preferences,
            ProviderAvailabilityService availability) {
        this.catalog = catalog;
        this.preferences = preferences;
        this.availability = availability;
    }

    @EventListener
    public synchronized void onApplicationReady(ApplicationReadyEvent event) {
        initializeIfConnected(OPENAI);
        initializeIfConnected(ANTHROPIC);
    }

    @EventListener
    public synchronized void onProviderConnected(ProviderConnectedEvent event) {
        initializeProvider(event.provider());
    }

    private void initializeIfConnected(String provider) {
        if (availability.isAvailable(provider))
            initializeProvider(provider);
    }

    private void initializeProvider(String provider) {
        String defaultModelId = switch (provider) {
            case OPENAI -> catalog.list().stream()
                    .filter(model -> OPENAI_DEFAULT.equals(model.id()) && OPENAI.equals(model.provider()))
                    .map(ModelDefinition::id).findFirst().orElse(null);
            case ANTHROPIC -> catalog.list().stream().filter(model -> ANTHROPIC.equals(model.provider()))
                    .filter(model -> model.displayName() != null
                            && model.displayName().toLowerCase().contains("sonnet"))
                    .filter(model -> model.releaseDate() != null)
                    .max(Comparator.comparing(ModelDefinition::releaseDate).thenComparing(ModelDefinition::id))
                    .map(ModelDefinition::id).orElse(null);
            default -> throw new IllegalArgumentException("Unknown provider: " + provider);
        };
        preferences.initializeProvider(provider, defaultModelId);
    }
}

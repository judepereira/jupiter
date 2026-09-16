package com.judepereira.jupiter.agent.catalog;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class ProviderConnectedEventListener {
    private static final String OPENAI = "openai";
    private static final String ANTHROPIC = "anthropic";

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
        var defaults = catalog.latestDistinctFamilyModelIds(provider);
        preferences.initializeProvider(provider, defaults);
    }
}

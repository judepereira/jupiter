package com.judepereira.jupiter.agent.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.judepereira.jupiter.persistence.AppStateRepository;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;

class ModelSelectionServicesTests {
    private static final ModelDefinition OPENAI = model("openai/one");
    private static final ModelDefinition OPENAI_TWO = model("openai/two");
    private static final ModelDefinition ANTHROPIC = model("anthropic/one");

    @Test
    void preferencesValidateReplaceDeduplicateAndOnlyInitializeEmptyRows() {
        AppStateRepository repository = mock(AppStateRepository.class);
        ModelCatalogService catalog = catalog(OPENAI, OPENAI_TWO, ANTHROPIC);
        ModelPreferencesService preferences = new ModelPreferencesService(repository, catalog);

        preferences.replaceSelectedModelIds("openai", List.of("openai/one", "openai/one", "openai/two"));
        verify(repository).replaceSelectedModelIds("openai", Set.of("openai/one", "openai/two"));
        assertThatThrownBy(() -> preferences.replaceSelectedModelIds("openai", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> preferences.replaceSelectedModelIds("openai", List.of("anthropic/one")))
                .isInstanceOf(IllegalArgumentException.class);

        preferences.initializeProvider("openai", List.of("openai/one"));
        verify(repository).initializeSelectedModelIds("openai", Set.of("openai/one"));
        preferences.initializeProvider("anthropic", List.of("anthropic/one"));
        verify(repository).initializeSelectedModelIds("anthropic", Set.of("anthropic/one"));
    }

    @Test
    void selectedModelsOmitsStaleIds() {
        AppStateRepository repository = mock(AppStateRepository.class);
        when(repository.loadSelectedModelIds("openai")).thenReturn(List.of("openai/one", "openai/stale"));
        assertThat(new ModelPreferencesService(repository, catalog(OPENAI)).selectedModels("openai"))
                .extracting(ModelDefinition::id).containsExactly("openai/one");
    }

    @Test
    void pickerUnionsSelectedAndAgentModelsAndFiltersDisconnectedProviders() {
        ModelPreferencesService preferences = mock(ModelPreferencesService.class);
        ProviderAvailabilityService availability = mock(ProviderAvailabilityService.class);
        AgentDefinitionService agents = mock(AgentDefinitionService.class);
        ModelCatalogService catalog = catalog(OPENAI, OPENAI_TWO, ANTHROPIC);
        when(preferences.selectedModels("openai")).thenReturn(List.of(OPENAI, OPENAI_TWO));
        when(preferences.selectedModels("anthropic")).thenReturn(List.of(ANTHROPIC));
        when(agents.list()).thenReturn(List.of(agent("openai/one", "anthropic/one")));
        when(availability.isAvailable("openai")).thenReturn(true);
        when(availability.isAvailable("anthropic")).thenReturn(false);

        assertThat(new ModelPickerService(preferences, availability, agents, catalog).listPickerModels())
                .extracting(ModelDefinition::id).containsExactly("openai/one", "openai/two");
        when(availability.isAvailable("openai")).thenReturn(false);
        assertThat(new ModelPickerService(preferences, availability, agents, catalog).listPickerModels()).isEmpty();
    }

    @Test
    void listenerInitializesOnlyConnectedProvidersAndNamedConnection() {
        ModelCatalogService catalog = mock(ModelCatalogService.class);
        ModelPreferencesService preferences = mock(ModelPreferencesService.class);
        ProviderAvailabilityService availability = mock(ProviderAvailabilityService.class);
        when(availability.isAvailable("openai")).thenReturn(true);
        when(availability.isAvailable("anthropic")).thenReturn(false);
        when(catalog.latestDistinctFamilyModelIds("openai")).thenReturn(List.of("openai/one"));
        ProviderConnectedEventListener listener = new ProviderConnectedEventListener(catalog, preferences,
                availability);

        listener.onApplicationReady(mock(ApplicationReadyEvent.class));
        verify(preferences).initializeProvider("openai", List.of("openai/one"));
        verify(preferences, never()).initializeProvider(eq("anthropic"), any());
        when(catalog.latestDistinctFamilyModelIds("anthropic")).thenReturn(List.of("anthropic/one"));
        listener.onProviderConnected(new ProviderConnectedEvent("anthropic"));
        verify(preferences).initializeProvider("anthropic", List.of("anthropic/one"));
    }

    private static AgentDefinition agent(String... ids) {
        return new AgentDefinition("agent", "Agent", "", "", AgentMode.AGENT, List.of(ids), ThinkingLevel.MEDIUM, "",
                false, false, List.of());
    }

    private static ModelDefinition model(String id) {
        return new ModelDefinition(id, id, id.substring(0, id.indexOf('/')), id.substring(id.indexOf('/') + 1), false,
                true, 1, 1, null, null, null, null, null, List.of("text"), List.of("text"));
    }

    private static ModelCatalogService catalog(ModelDefinition... models) {
        ModelCatalogService catalog = mock(ModelCatalogService.class);
        when(catalog.list()).thenReturn(List.of(models));
        when(catalog.hasProviderModel(any())).thenAnswer(invocation -> List.of(models).stream()
                .anyMatch(model -> model.provider().equals(invocation.getArgument(0))));
        for (ModelDefinition model : models)
            when(catalog.getRequired(model.id())).thenReturn(model);
        return catalog;
    }
}

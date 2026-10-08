package com.judepereira.jupiter.agent.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.Persistence.AgentModelPreference;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentPreferenceResolverTest {
    private final AppStateService state = mock(AppStateService.class);
    private final ModelCatalogService catalog = mock(ModelCatalogService.class);
    private final ProviderAvailabilityService availability = mock(ProviderAvailabilityService.class);
    private final AgentModelResolutionService fallback = mock(AgentModelResolutionService.class);
    private final AgentPreferenceResolver resolver = new AgentPreferenceResolver(state, catalog, availability,
            fallback);
    private final AgentDefinition agent = new AgentDefinition("agent", "Agent", "description", "prompt",
            AgentMode.AGENT, List.of("bundled"), ThinkingLevel.MEDIUM, "low", false, false, List.of());
    private final ModelDefinition bundled = model("bundled", "openai");
    private final ModelDefinition configured = model("configured", "anthropic");

    @BeforeEach
    void defaults() {
        when(state.findAgentModelPreference("agent")).thenReturn(Optional.empty());
        when(availability.isAvailable("openai")).thenReturn(true);
        when(availability.isAvailable("anthropic")).thenReturn(true);
        when(fallback.resolve(agent)).thenReturn(new AgentModelResolutionService.ModelResolution("bundled", bundled));
        when(fallback.resolveForDisplay(agent))
                .thenReturn(new AgentModelResolutionService.ModelResolution("bundled", bundled));
        when(catalog.getRequired("configured")).thenReturn(configured);
        when(catalog.getRequired("bundled")).thenReturn(bundled);
    }

    @Test
    void explicitModelAndThinkingAreIndependent() {
        var result = resolver.resolve(agent, "configured", ThinkingLevel.HIGH, false);
        assertThat(result.model()).isSameAs(configured);
        assertThat(result.thinkingLevel()).isEqualTo(ThinkingLevel.HIGH);
        assertThat(result.strictModel()).isFalse();
    }

    @Test
    void arbitraryConfiguredModelWins() {
        var result = resolver.resolve(agent, "configured", null, false);
        assertThat(result.model()).isSameAs(configured);
        assertThat(result.preferredModelId()).isEqualTo("configured");
    }

    @Test
    void savedModelIsStrictAndUnavailableFails() {
        when(state.findAgentModelPreference("agent"))
                .thenReturn(Optional.of(new AgentModelPreference("agent", "configured", "HIGH")));
        when(availability.isAvailable("anthropic")).thenReturn(false);
        assertThatThrownBy(() -> resolver.resolve(agent, null, null, false)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Saved model override");
    }

    @Test
    void noSavedModelUsesBundledFallback() {
        var result = resolver.resolve(agent, null, null, false);
        assertThat(result.model()).isSameAs(bundled);
        assertThat(result.preferredModelId()).isEqualTo("bundled");
    }

    @Test
    void savedModelAndThinkingDefaultsAreUsedIndependently() {
        when(state.findAgentModelPreference("agent"))
                .thenReturn(Optional.of(new AgentModelPreference("agent", null, "HIGH")));

        var result = resolver.resolve(agent, null, null, false);

        assertThat(result.model()).isSameAs(bundled);
        assertThat(result.thinkingLevel()).isEqualTo(ThinkingLevel.HIGH);
    }

    @Test
    void savedModelOverrideWinsOverBundledFallback() {
        when(state.findAgentModelPreference("agent"))
                .thenReturn(Optional.of(new AgentModelPreference("agent", "configured", null)));

        var result = resolver.resolve(agent, null, null, false);

        assertThat(result.model()).isSameAs(configured);
        assertThat(result.preferredModelId()).isEqualTo("configured");
    }

    @Test
    void displayResolutionUsesSavedModelWithoutProviderAvailability() {
        when(state.findAgentModelPreference("agent"))
                .thenReturn(Optional.of(new AgentModelPreference("agent", "configured", "HIGH")));
        when(availability.isAvailable("anthropic")).thenReturn(false);

        var result = resolver.resolveForDisplay(agent);

        assertThat(result.model()).isSameAs(configured);
        assertThat(result.thinkingLevel()).isEqualTo(ThinkingLevel.HIGH);
    }

    @Test
    void displayResolutionUsesBundledModelWhenSavedModelIsStale() {
        when(state.findAgentModelPreference("agent"))
                .thenReturn(Optional.of(new AgentModelPreference("agent", "stale", null)));
        when(catalog.getRequired("stale")).thenThrow(new IllegalArgumentException("missing"));

        var result = resolver.resolveForDisplay(agent);

        assertThat(result.model()).isSameAs(bundled);
    }

    @Test
    void revalidateNonStrictDisconnectedSnapshotUsesBundledFallbackAndPreservesMetadata() {
        var snapshot = new AgentPreferenceResolver.Resolution(configured, "preferred", ThinkingLevel.HIGH, false);
        when(availability.isAvailable("anthropic")).thenReturn(false);
        when(fallback.resolve(agent))
                .thenReturn(new AgentModelResolutionService.ModelResolution("configured", bundled));

        var result = resolver.revalidateSnapshot(agent, snapshot);

        assertThat(result.model()).isSameAs(bundled);
        assertThat(result.preferredModelId()).isEqualTo("preferred");
        assertThat(result.thinkingLevel()).isEqualTo(ThinkingLevel.HIGH);
        assertThat(result.strictModel()).isFalse();
    }

    @Test
    void revalidateStrictDisconnectedSnapshotFails() {
        var snapshot = new AgentPreferenceResolver.Resolution(configured, configured.id(), ThinkingLevel.HIGH, true);
        when(availability.isAvailable("anthropic")).thenReturn(false);

        assertThatThrownBy(() -> resolver.revalidateSnapshot(agent, snapshot)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("strict model provider is unavailable");
    }

    @Test
    void revalidateDoesNotRereadSavedPreferences() {
        var snapshot = new AgentPreferenceResolver.Resolution(configured, "preferred", ThinkingLevel.HIGH, false);
        when(availability.isAvailable("anthropic")).thenReturn(false);
        when(fallback.resolve(agent))
                .thenReturn(new AgentModelResolutionService.ModelResolution("configured", bundled));
        when(state.findAgentModelPreference("agent"))
                .thenReturn(Optional.of(new AgentModelPreference("agent", "configured", "LOW")));

        var result = resolver.revalidateSnapshot(agent, snapshot);

        assertThat(result.thinkingLevel()).isEqualTo(ThinkingLevel.HIGH);
        assertThat(result.preferredModelId()).isEqualTo("preferred");
    }

    @Test
    void staleSavedModelIsNotSilentlyReplaced() {
        when(state.findAgentModelPreference("agent"))
                .thenReturn(Optional.of(new AgentModelPreference("agent", "stale", null)));
        when(catalog.getRequired("stale")).thenThrow(new IllegalArgumentException("missing"));
        assertThatThrownBy(() -> resolver.resolve(agent, null, null, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("missing");
    }

    private static ModelDefinition model(String id, String provider) {
        return new ModelDefinition(id, id, provider, id, true, true, 100, 20, null, null, null, null, null,
                List.of("text"), List.of("text"));
    }
}

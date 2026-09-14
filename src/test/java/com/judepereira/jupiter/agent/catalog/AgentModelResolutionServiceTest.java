package com.judepereira.jupiter.agent.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

class AgentModelResolutionServiceTest {
    private final ModelCatalogService catalog = mock(ModelCatalogService.class);
    private final ProviderAvailabilityService availability =
            mock(ProviderAvailabilityService.class);
    private final AgentModelResolutionService service =
            new AgentModelResolutionService(catalog, availability);

    @Test
    void rejectsUnknownPreferenceInsteadOfSubstitutingAnotherProviderModel() {
        when(catalog.getRequired("anthropic/missing"))
                .thenThrow(new IllegalArgumentException("Unknown model id"));

        assertThatThrownBy(() -> service.resolve(agent("anthropic/missing")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown model id");
    }

    @Test
    void selectsFirstAvailableModelInDeclaredOrderAndAttributesPreferredModel() {
        ModelDefinition openAi = model("openai/first", "openai");
        ModelDefinition claude = model("anthropic/claude", "anthropic");
        when(catalog.getRequired(openAi.id())).thenReturn(openAi);
        when(catalog.getRequired(claude.id())).thenReturn(claude);
        when(availability.isAvailable("openai")).thenReturn(false);
        when(availability.isAvailable("anthropic")).thenReturn(true);

        var result = service.resolve(agent(openAi.id(), claude.id()));

        assertThat(result.model()).isSameAs(claude);
        assertThat(result.preferredModelId()).isEqualTo(openAi.id());
        assertThat(result.fallback()).isTrue();
    }

    @Test
    void doesNotRetryLaterModelsAfterAConfiguredModelIsAvailable() {
        ModelDefinition first = model("anthropic/first", "anthropic");
        ModelDefinition second = model("openai/second", "openai");
        when(catalog.getRequired(first.id())).thenReturn(first);
        when(availability.isAvailable("anthropic")).thenReturn(true);

        var result = service.resolve(agent(first.id(), second.id()));

        assertThat(result.model()).isSameAs(first);
        assertThat(result.fallback()).isFalse();
    }

    @Test
    void failsWhenNoConfiguredProviderIsAvailable() {
        ModelDefinition model = model("anthropic/claude", "anthropic");
        when(catalog.getRequired(model.id())).thenReturn(model);
        when(availability.isAvailable("anthropic")).thenReturn(false);

        assertThatThrownBy(() -> service.resolve(agent(model.id())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No configured provider");
    }

    private static AgentDefinition agent(String... models) {
        return new AgentDefinition(
                "test",
                "Test",
                "test",
                "prompt",
                AgentMode.SUBAGENT,
                List.of(models),
                ThinkingLevel.HIGH,
                "low",
                false,
                false,
                List.of("read_file"));
    }

    private static ModelDefinition model(String id, String provider) {
        return new ModelDefinition(
                id,
                id,
                provider,
                id.substring(id.indexOf('/') + 1),
                true,
                true,
                100,
                20,
                null,
                null,
                null);
    }
}

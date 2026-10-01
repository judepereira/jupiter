package com.judepereira.jupiter.agent.catalog;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.Persistence.AgentModelPreference;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentPreferenceServiceTest {
    private final AppStateService state = mock(AppStateService.class);
    private final AgentDefinitionService agents = mock(AgentDefinitionService.class);
    private final ModelCatalogService catalog = mock(ModelCatalogService.class);
    private final ProviderAvailabilityService availability = mock(ProviderAvailabilityService.class);
    private final ModelPickerService picker = mock(ModelPickerService.class);
    private final AgentPreferenceService service = new AgentPreferenceService(state, agents, catalog, availability,
            picker);
    private final AgentDefinition agent = new AgentDefinition("agent", "Agent", "description", "prompt",
            AgentMode.AGENT, List.of(), ThinkingLevel.MEDIUM, "low", false, false, List.of());

    @Test
    void savesArbitraryEligibleConfiguredModelAndThinking() {
        var model = new ModelDefinition("custom", "Custom", "openai", "custom", true, true, 100, 20, null, null, null,
                null, null, List.of("text"), List.of("text"));
        when(agents.getRequired("agent")).thenReturn(agent);
        when(catalog.getRequired("custom")).thenReturn(model);
        when(availability.isAvailable("openai")).thenReturn(true);
        service.save("agent", "custom", "HIGH");
        verify(state).upsertAgentModelPreference(new AgentModelPreference("agent", "custom", "HIGH"));
    }

    @Test
    void rejectsUnavailableOrIneligibleModel() {
        var model = new ModelDefinition("custom", "Custom", "openai", "custom", true, false, 100, 20, null, null, null,
                null, null, List.of("text"), List.of("text"));
        when(agents.getRequired("agent")).thenReturn(agent);
        when(catalog.getRequired("custom")).thenReturn(model);
        assertThatThrownBy(() -> service.save("agent", "custom", null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eligible");
    }

    @Test
    void resetDelegatesToPersistentState() {
        when(agents.getRequired("agent")).thenReturn(agent);
        service.reset("agent");
        verify(state).resetAgentModelPreference("agent");
    }
}

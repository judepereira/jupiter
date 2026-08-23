package com.judepereira.jupiter.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.config.AgentProperties;
import com.judepereira.jupiter.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter.persistence.TestAppStateSupport;
import com.judepereira.jupiter.testsupport.ModelCatalogTestSupport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class UiControllerModelLabelTests {

    @Test
    void resolveModelLabelUsesDisplayNameAndFallsBackToRawId() {
        UiController controller = TestAppStateSupport.controller(mock(CodingAgentHarness.class), new AgentProperties(), ModelCatalogTestSupport.modelCatalogService());

        assertThat(controller.resolveModelLabel("openai/gpt-5.5")).isEqualTo("GPT-5.5");
        assertThat(controller.resolveModelLabel("openai/stale-model")).isEqualTo("openai/stale-model");
        assertThat(controller.resolveModelLabel(null)).isNull();
    }
}

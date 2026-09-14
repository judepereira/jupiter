package com.judepereira.jupiter.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.judepereira.jupiter.agent.config.AgentProperties;
import com.judepereira.jupiter.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter.persistence.Persistence.ChatMessageMetadata;
import com.judepereira.jupiter.persistence.Persistence.ChatMessageView;
import com.judepereira.jupiter.persistence.TestAppStateSupport;
import com.judepereira.jupiter.testsupport.ModelCatalogTestSupport;
import java.util.List;
import org.junit.jupiter.api.Test;

class UiControllerModelLabelTests {

    @Test
    void presentationLabelsNormalAndFallbackModelsExactly() {
        ChatPresentationService presentation = new ChatPresentationService();
        ChatMessageView normal =
                new ChatMessageView(
                        "assistant",
                        "ok",
                        1,
                        false,
                        "id",
                        null,
                        List.of(),
                        new ChatMessageMetadata("a", "Agent", "openai/gpt-5.6-sol", null, null));
        ChatMessageView fallback =
                new ChatMessageView(
                        "assistant",
                        "ok",
                        1,
                        false,
                        "id",
                        null,
                        List.of(),
                        new ChatMessageMetadata(
                                "a", "Agent", "openai/gpt-5.6-sol", null, "openai/gpt-5.6"));
        assertThat(presentation.toChatMessage(normal, controller -> controller))
                .extracting(ChatPresentationService.ChatMessage::modelLabel)
                .isEqualTo("openai/gpt-5.6-sol");
        assertThat(presentation.toChatMessage(fallback, controller -> controller))
                .extracting(ChatPresentationService.ChatMessage::modelLabel)
                .isEqualTo("Preferred openai/gpt-5.6 · Used openai/gpt-5.6-sol");
    }

    @Test
    void resolveModelLabelUsesDisplayNameAndFallsBackToRawId() {
        UiController controller =
                TestAppStateSupport.controller(
                        mock(CodingAgentHarness.class),
                        new AgentProperties(),
                        ModelCatalogTestSupport.modelCatalogService());

        assertThat(controller.resolveModelLabel("openai/gpt-5.6-sol")).isEqualTo("GPT-5.6 Sol");
        assertThat(controller.resolveModelLabel("openai/stale-model"))
                .isEqualTo("openai/stale-model");
        assertThat(controller.resolveModelLabel(null)).isNull();
    }
}

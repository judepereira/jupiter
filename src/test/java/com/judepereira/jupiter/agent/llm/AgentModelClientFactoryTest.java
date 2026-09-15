package com.judepereira.jupiter.agent.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.judepereira.jupiter.agent.llm.anthropic.AnthropicAgentModelClient;
import com.judepereira.jupiter.agent.llm.openai.OpenAiAgentModelClient;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

class AgentModelClientFactoryTest {
    private final ApplicationContext context = mock(ApplicationContext.class);
    private final AgentModelClientFactory factory = new AgentModelClientFactory(context);

    @Test
    void routesSupportedProvidersAfterNormalization() {
        OpenAiAgentModelClient openAi = mock(OpenAiAgentModelClient.class);
        AnthropicAgentModelClient anthropic = mock(AnthropicAgentModelClient.class);
        when(context.getBean(OpenAiAgentModelClient.class)).thenReturn(openAi);
        when(context.getBean(AnthropicAgentModelClient.class)).thenReturn(anthropic);

        assertThat(factory.getClient(" OpenAI ")).isSameAs(openAi);
        assertThat(factory.getClient("ANTHROPIC")).isSameAs(anthropic);
    }

    @Test
    void rejectsMissingAndUnknownProviders() {
        assertThatThrownBy(() -> factory.getClient(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> factory.getClient("   ")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> factory.getClient("gemini")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported model provider");
    }
}

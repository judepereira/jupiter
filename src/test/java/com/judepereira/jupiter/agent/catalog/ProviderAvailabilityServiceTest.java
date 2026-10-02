package com.judepereira.jupiter.agent.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.judepereira.jupiter.agent.config.AnthropicProperties;
import com.judepereira.jupiter.agent.config.OpenAiProperties;
import com.judepereira.jupiter.openai.oauth.OpenAiOAuthService;
import org.junit.jupiter.api.Test;

class ProviderAvailabilityServiceTest {
    private final OpenAiOAuthService openAi = mock(OpenAiOAuthService.class);
    private final OpenAiProperties openAiProperties = new OpenAiProperties();
    private final AnthropicProperties anthropicProperties = new AnthropicProperties();

    @Test
    void openAiIsAvailableWithTrimmedNonBlankApiKeyEvenWhenOAuthIsDisconnected() {
        openAiProperties.setApiKey("  sk-test  ");
        when(openAi.currentView()).thenReturn(
                new OpenAiOAuthService.OpenAiOAuthView(false, false, "not connected", null, null, null, null));

        assertThat(service().isAvailable("openai")).isTrue();
        assertThat(openAiProperties.trimmedApiKey()).isEqualTo("sk-test");
    }

    @Test
    void blankApiKeyDoesNotMakeDisconnectedOpenAiAvailable() {
        openAiProperties.setApiKey("  \t");
        when(openAi.currentView()).thenReturn(
                new OpenAiOAuthService.OpenAiOAuthView(false, false, "not connected", null, null, null, null));

        assertThat(service().isAvailable("openai")).isFalse();
    }

    @Test
    void oauthConnectionMakesOpenAiAvailableWithoutApiKey() {
        when(openAi.currentView())
                .thenReturn(new OpenAiOAuthService.OpenAiOAuthView(true, false, "connected", null, null, null, null));

        assertThat(service().isAvailable("openai")).isTrue();
    }

    @Test
    void anthropicRequiresNonBlankApiKey() {
        assertThat(service().isAvailable("anthropic")).isFalse();
        anthropicProperties.setApiKey("  sk-ant-test  ");
        assertThat(service().isAvailable("anthropic")).isTrue();
    }

    private ProviderAvailabilityService service() {
        return new ProviderAvailabilityService(openAi, openAiProperties, anthropicProperties);
    }
}

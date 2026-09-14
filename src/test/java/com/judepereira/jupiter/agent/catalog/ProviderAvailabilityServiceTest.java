package com.judepereira.jupiter.agent.catalog;

import com.judepereira.jupiter.agent.config.OpenAiProperties;
import com.judepereira.jupiter.anthropic.oauth.AnthropicOAuthService;
import com.judepereira.jupiter.openai.oauth.OpenAiOAuthService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProviderAvailabilityServiceTest {
    private final OpenAiOAuthService openAi = mock(OpenAiOAuthService.class);
    private final AnthropicOAuthService anthropic = mock(AnthropicOAuthService.class);
    private final OpenAiProperties properties = new OpenAiProperties();

    @Test
    void openAiIsAvailableWithTrimmedNonBlankApiKeyEvenWhenOAuthIsDisconnected() {
        properties.setApiKey("  sk-test  ");
        when(openAi.currentView()).thenReturn(new OpenAiOAuthService.OpenAiOAuthView(false, false, "not connected", null, null, null, null));

        assertThat(new ProviderAvailabilityService(openAi, anthropic, properties).isAvailable("openai")).isTrue();
        assertThat(properties.trimmedApiKey()).isEqualTo("sk-test");
    }

    @Test
    void blankApiKeyDoesNotMakeDisconnectedOpenAiAvailable() {
        properties.setApiKey("  \t");
        when(openAi.currentView()).thenReturn(new OpenAiOAuthService.OpenAiOAuthView(false, false, "not connected", null, null, null, null));

        assertThat(new ProviderAvailabilityService(openAi, anthropic, properties).isAvailable("openai")).isFalse();
    }

    @Test
    void oauthConnectionMakesOpenAiAvailableWithoutApiKey() {
        when(openAi.currentView()).thenReturn(new OpenAiOAuthService.OpenAiOAuthView(true, false, "connected", null, null, null, null));

        assertThat(new ProviderAvailabilityService(openAi, anthropic, properties).isAvailable("openai")).isTrue();
    }
}

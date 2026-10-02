package com.judepereira.jupiter.agent.catalog;

import com.judepereira.jupiter.agent.config.AnthropicProperties;
import com.judepereira.jupiter.agent.config.OpenAiProperties;
import com.judepereira.jupiter.openai.oauth.OpenAiOAuthService;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class ProviderAvailabilityService {
    private final OpenAiOAuthService openAi;
    private final OpenAiProperties openAiProperties;
    private final AnthropicProperties anthropicProperties;

    public ProviderAvailabilityService(OpenAiOAuthService openAi, OpenAiProperties openAiProperties,
            AnthropicProperties anthropicProperties) {
        this.openAi = openAi;
        this.openAiProperties = openAiProperties;
        this.anthropicProperties = anthropicProperties;
    }

    public boolean isAvailable(String provider) {
        return switch (provider) {
            case "openai" -> openAi.currentView().connected() || openAiProperties.hasApiKey();
            case "anthropic" -> anthropicProperties.hasApiKey();
            default -> false;
        };
    }

    public Set<String> availableProviders() {
        Set<String> result = new LinkedHashSet<>();
        if (isAvailable("openai"))
            result.add("openai");
        if (isAvailable("anthropic"))
            result.add("anthropic");
        return Collections.unmodifiableSet(result);
    }
}

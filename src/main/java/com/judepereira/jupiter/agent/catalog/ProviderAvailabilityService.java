package com.judepereira.jupiter.agent.catalog;

import com.judepereira.jupiter.agent.config.OpenAiProperties;
import com.judepereira.jupiter.anthropic.oauth.AnthropicOAuthService;
import com.judepereira.jupiter.openai.oauth.OpenAiOAuthService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

@Service
public class ProviderAvailabilityService {
    private final OpenAiOAuthService openAi;
    private final AnthropicOAuthService anthropic;
    private final OpenAiProperties openAiProperties;

    public ProviderAvailabilityService(OpenAiOAuthService openAi, AnthropicOAuthService anthropic,
                                       OpenAiProperties openAiProperties) {
        this.openAi = openAi;
        this.anthropic = anthropic;
        this.openAiProperties = openAiProperties;
    }

    public boolean isAvailable(String provider) {
        return switch (provider) {
            case "openai" -> openAi.currentView().connected() || openAiProperties.hasApiKey();
            case "anthropic" -> anthropic.isConnected();
            default -> false;
        };
    }

    public Set<String> availableProviders() {
        Set<String> result = new LinkedHashSet<>();
        if (isAvailable("openai")) result.add("openai");
        if (isAvailable("anthropic")) result.add("anthropic");
        return java.util.Collections.unmodifiableSet(result);
    }
}

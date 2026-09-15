package com.judepereira.jupiter.agent.llm;

import com.judepereira.jupiter.agent.llm.anthropic.AnthropicAgentModelClient;
import com.judepereira.jupiter.agent.llm.openai.OpenAiAgentModelClient;
import java.util.Locale;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component
public class AgentModelClientFactory {

    private final ApplicationContext ctx;

    public AgentModelClientFactory(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    public AgentModelClient getClient(String provider) {
        if (ctx == null) {
            throw new IllegalStateException("Application context is required to resolve a model client");
        }
        if (provider == null || provider.isBlank()) {
            throw new IllegalStateException("Model provider is required");
        }
        String normalizedProvider = provider.trim().toLowerCase(Locale.ROOT);
        return switch (normalizedProvider) {
            case "openai" -> ctx.getBean(OpenAiAgentModelClient.class);
            case "anthropic" -> ctx.getBean(AnthropicAgentModelClient.class);
            default -> throw new IllegalStateException("Unsupported model provider: " + provider);
        };
    }
}

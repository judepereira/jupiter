package com.judepereira.jupiter.agent.llm;

import com.judepereira.jupiter.agent.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AgentModelClientFactory {

    private final ApplicationContext ctx;
    private final AgentProperties props;

    public AgentModelClient getClient() {
        String provider = props.getProvider();
        if (provider == null) provider = "openai";
        switch (provider.toLowerCase().trim()) {
            case "openai":
                return ctx.getBean(com.judepereira.jupiter.agent.llm.openai.OpenAiAgentModelClient.class);
            default:
                throw new IllegalStateException("Unsupported agent.provider: " + provider);
        }
    }
}

package com.judepereira.jupiter2.agent.llm;

import com.judepereira.jupiter2.agent.config.AgentProperties;
import com.judepereira.jupiter2.agent.llm.openai.OpenAiAgentModelClient;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component
public class AgentModelClientFactory {

    private final ApplicationContext ctx;
    private final AgentProperties props;

    public AgentModelClientFactory(ApplicationContext ctx, AgentProperties props) {
        this.ctx = ctx;
        this.props = props;
    }

    public AgentModelClient getClient() {
        String provider = props.getProvider();
        if (provider == null) provider = "openai";
        switch (provider.toLowerCase().trim()) {
            case "openai":
                return ctx.getBean(com.judepereira.jupiter2.agent.llm.openai.OpenAiAgentModelClient.class);
            default:
                throw new IllegalStateException("Unsupported agent.provider: " + provider);
        }
    }
}

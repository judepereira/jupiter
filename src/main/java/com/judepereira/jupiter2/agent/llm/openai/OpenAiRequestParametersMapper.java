package com.judepereira.jupiter2.agent.llm.openai;

import com.judepereira.jupiter2.agent.catalog.ThinkingLevel;
import com.judepereira.jupiter2.agent.llm.AgentModelOptions;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;

public final class OpenAiRequestParametersMapper {

    public OpenAiChatRequestParameters toRequestParameters(AgentModelOptions options) {
        if (options == null || !options.supportsReasoning() || options.thinkingLevel() == null) {
            return null;
        }
        return OpenAiChatRequestParameters.builder()
                .reasoningEffort(reasoningEffort(options.thinkingLevel()))
                .build();
    }

    private static String reasoningEffort(ThinkingLevel thinkingLevel) {
        return switch (thinkingLevel) {
            case LOW -> "low";
            case MEDIUM -> "medium";
            case HIGH -> "high";
        };
    }
}

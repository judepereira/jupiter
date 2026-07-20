package com.judepereira.jupiter2.agent.llm.openai;

import com.judepereira.jupiter2.agent.catalog.ThinkingLevel;
import com.judepereira.jupiter2.agent.llm.AgentModelOptions;
import dev.langchain4j.model.openai.OpenAiResponsesChatRequestParameters;

public final class OpenAiRequestParametersMapper {

    public OpenAiResponsesChatRequestParameters toRequestParameters(AgentModelOptions options) {
        if (options == null) {
            return null;
        }
        boolean hasReasoning = options.supportsReasoning() && options.thinkingLevel() != null;
        boolean hasTextVerbosity = options.textVerbosity() != null && !options.textVerbosity().isBlank();
        if (!hasReasoning && !hasTextVerbosity) {
            return null;
        }
        var builder = OpenAiResponsesChatRequestParameters.builder();
        if (hasReasoning) {
            builder.reasoningEffort(reasoningEffort(options.thinkingLevel()));
        }
        if (hasTextVerbosity) {
            builder.textVerbosity(options.textVerbosity());
        }
        return builder.build();
    }

    private static String reasoningEffort(ThinkingLevel thinkingLevel) {
        return switch (thinkingLevel) {
            case LOW -> "low";
            case MEDIUM -> "medium";
            case HIGH -> "high";
        };
    }
}

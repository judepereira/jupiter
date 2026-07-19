package com.judepereira.jupiter2.agent.llm.openai;

import com.judepereira.jupiter2.agent.llm.AgentModelOptions;
import com.judepereira.jupiter2.agent.llm.dto.Message;
import com.judepereira.jupiter2.agent.llm.dto.ToolDefinition;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiResponsesChatRequestParameters;

import java.util.List;

public final class LangChain4jChatRequestFactory {

    private final LangChain4jMessageMapper messageMapper;
    private final LangChain4jToolSpecificationMapper toolSpecificationMapper;
    private final OpenAiRequestParametersMapper requestParametersMapper;

    public LangChain4jChatRequestFactory() {
        this(new LangChain4jMessageMapper(), new LangChain4jToolSpecificationMapper(), new OpenAiRequestParametersMapper());
    }

    public LangChain4jChatRequestFactory(LangChain4jMessageMapper messageMapper,
                                         LangChain4jToolSpecificationMapper toolSpecificationMapper,
                                         OpenAiRequestParametersMapper requestParametersMapper) {
        this.messageMapper = messageMapper;
        this.toolSpecificationMapper = toolSpecificationMapper;
        this.requestParametersMapper = requestParametersMapper;
    }

    public ChatRequest create(String modelName, List<Message> conversation, List<ToolDefinition> tools, AgentModelOptions options) {
        List<ChatMessage> messages = messageMapper.toChatMessages(conversation);
        List<ToolSpecification> toolSpecifications = toolSpecificationMapper.toToolSpecifications(tools);
        OpenAiResponsesChatRequestParameters parameters = requestParametersMapper.toRequestParameters(options);

        ChatRequest.Builder builder = ChatRequest.builder()
                .modelName(modelName)
                .messages(messages);

        if (!toolSpecifications.isEmpty()) {
            builder.toolSpecifications(toolSpecifications);
        }
        if (parameters != null) {
            builder.parameters(parameters);
        }
        return builder.build();
    }
}

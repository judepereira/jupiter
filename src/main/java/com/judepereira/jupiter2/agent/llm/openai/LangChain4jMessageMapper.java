package com.judepereira.jupiter2.agent.llm.openai;

import com.judepereira.jupiter2.agent.llm.dto.Message;
import com.judepereira.jupiter2.agent.llm.dto.ModelResponse;
import com.judepereira.jupiter2.agent.llm.dto.ToolCall;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LangChain4jMessageMapper {

    private final ToolArgumentsCodec toolArgumentsCodec;

    public LangChain4jMessageMapper() {
        this(new ToolArgumentsCodec());
    }

    public LangChain4jMessageMapper(ToolArgumentsCodec toolArgumentsCodec) {
        this.toolArgumentsCodec = toolArgumentsCodec;
    }

    public List<ChatMessage> toChatMessages(List<Message> conversation) {
        List<ChatMessage> messages = new ArrayList<>(conversation.size());
        Map<String, String> toolNamesById = new LinkedHashMap<>();

        for (int i = 0; i < conversation.size(); i++) {
            Message message = conversation.get(i);
            switch (message.getRole()) {
                case SYSTEM -> messages.add(new SystemMessage(message.getContent()));
                case USER -> messages.add(new UserMessage(message.getContent()));
                case ASSISTANT -> {
                    List<ToolCall> toolCalls = message.getToolCalls();
                    if (toolCalls == null || toolCalls.isEmpty()) {
                        messages.add(new AiMessage(message.getContent() == null ? "" : message.getContent()));
                    } else {
                        List<dev.langchain4j.agent.tool.ToolExecutionRequest> requests = new ArrayList<>(toolCalls.size());
                        for (int j = 0; j < toolCalls.size(); j++) {
                            ToolCall toolCall = toolCalls.get(j);
                            String toolCallId = normalizeToolCallId(toolCall.getToolCallId(), i, j);
                            String toolName = requireToolName(toolCall.getToolName());
                            toolNamesById.put(toolCallId, toolName);
                            requests.add(dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                                    .id(toolCallId)
                                    .name(toolName)
                                    .arguments(toolArgumentsCodec.serialize(toolCall.getArguments()))
                                    .build());
                        }
                        messages.add(new AiMessage(message.getContent() == null ? "" : message.getContent(), requests));
                    }
                }
                case TOOL -> {
                    String toolCallId = requireToolCallId(message.getToolCallId());
                    String toolName = toolNamesById.get(toolCallId);
                    if (toolName == null || toolName.isBlank()) {
                        throw new IllegalStateException("Missing tool name for tool call id: " + toolCallId);
                    }
                    messages.add(new ToolExecutionResultMessage(toolCallId, toolName, message.getContent() == null ? "" : message.getContent()));
                }
            }
        }

        return messages;
    }

    public ModelResponse toModelResponse(ChatResponse response) {
        if (response == null || response.aiMessage() == null) {
            throw new IllegalStateException("OpenAI response did not contain an assistant message");
        }

        AiMessage aiMessage = response.aiMessage();
        if (aiMessage.hasToolExecutionRequests() && !aiMessage.toolExecutionRequests().isEmpty()) {
            return new ModelResponse(aiMessage.text(), toToolCall(aiMessage.toolExecutionRequests().get(0)));
        }
        return new ModelResponse(aiMessage.text(), null);
    }

    public ToolCall toToolCall(dev.langchain4j.agent.tool.ToolExecutionRequest request) {
        return new ToolCall(request.id(), request.name(), toolArgumentsCodec.parse(request.arguments()));
    }

    private static String normalizeToolCallId(String toolCallId, int messageIndex, int toolIndex) {
        return toolCallId == null || toolCallId.isBlank() ? "tool-" + messageIndex + "-" + toolIndex : toolCallId;
    }

    private static String requireToolCallId(String toolCallId) {
        if (toolCallId == null || toolCallId.isBlank()) {
            throw new IllegalStateException("toolCallId is required for TOOL messages");
        }
        return toolCallId;
    }

    private static String requireToolName(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalStateException("toolName is required for tool calls");
        }
        return toolName;
    }
}

package com.judepereira.jupiter2.agent.llm.openai;

import com.judepereira.jupiter2.agent.config.AgentProperties;
import com.judepereira.jupiter2.agent.config.OpenAiProperties;
import com.judepereira.jupiter2.agent.llm.AgentModelClient;
import com.judepereira.jupiter2.agent.llm.AgentModelOptions;
import com.judepereira.jupiter2.agent.llm.dto.Message;
import com.judepereira.jupiter2.agent.llm.dto.ModelResponse;
import com.judepereira.jupiter2.agent.llm.dto.ToolCall;
import com.judepereira.jupiter2.agent.llm.dto.ToolDefinition;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Component
public class OpenAiAgentModelClient implements AgentModelClient {

    private final OpenAiProperties openAiProperties;
    private final AgentProperties agentProperties;
    private final LangChain4jChatRequestFactory chatRequestFactory;
    private final LangChain4jMessageMapper messageMapper;
    private final Map<String, ChatModel> chatModels = new ConcurrentHashMap<>();
    private final Map<String, StreamingChatModel> streamingChatModels = new ConcurrentHashMap<>();

    public OpenAiAgentModelClient(OpenAiProperties openAiProperties, AgentProperties agentProperties) {
        this.openAiProperties = openAiProperties;
        this.agentProperties = agentProperties;
        this.chatRequestFactory = new LangChain4jChatRequestFactory();
        this.messageMapper = new LangChain4jMessageMapper();
    }

    @Override
    public ModelResponse chat(List<Message> conversation, List<ToolDefinition> tools) {
        return chat(conversation, tools, null);
    }

    @Override
    public ModelResponse chat(List<Message> conversation, List<ToolDefinition> tools, AgentModelOptions options) {
        String modelName = resolveModelName(options);
        ChatRequest request = chatRequestFactory.create(modelName, conversation, tools, options);
        try {
            return messageMapper.toModelResponse(chatModel(modelName).chat(request));
        } catch (Exception e) {
            throw new IllegalStateException("OpenAI request failed", e);
        }
    }

    @Override
    public ModelResponse chatStreaming(List<Message> conversation, List<ToolDefinition> tools, AgentModelOptions options, Consumer<String> onDelta) {
        String modelName = resolveModelName(options);
        ChatRequest request = chatRequestFactory.create(modelName, conversation, tools, options);
        AtomicReference<ModelResponse> response = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<ToolCall> toolCall = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        StreamingChatResponseHandler handler = new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                if (onDelta != null && partialResponse != null && !partialResponse.isEmpty()) {
                    onDelta.accept(partialResponse);
                }
            }

            @Override
            public void onCompleteToolCall(CompleteToolCall completeToolCall) {
                toolCall.compareAndSet(null, messageMapper.toToolCall(completeToolCall.toolExecutionRequest()));
            }

            @Override
            public void onCompleteResponse(ChatResponse chatResponse) {
                response.set(messageMapper.toModelResponse(chatResponse));
                if (toolCall.get() == null) {
                    toolCall.compareAndSet(null, response.get().getToolCall());
                }
                done.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
                error.set(throwable);
                done.countDown();
            }
        };

        try {
            streamingChatModel(modelName).chat(request, handler);
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OpenAI streaming request interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("OpenAI streaming request failed", e);
        }

        if (error.get() != null) {
            throw new IllegalStateException("OpenAI streaming request failed", error.get());
        }

        if (toolCall.get() != null) {
            return new ModelResponse(response.get() == null ? null : response.get().getAssistantText(), toolCall.get());
        }
        return response.get();
    }

    @Override
    public ModelResponse chatStreaming(List<Message> conversation, List<ToolDefinition> tools, Consumer<String> onDelta) {
        return chatStreaming(conversation, tools, null, onDelta);
    }

    private ChatModel chatModel(String modelName) {
        return chatModels.computeIfAbsent(modelName, this::buildChatModel);
    }

    private StreamingChatModel streamingChatModel(String modelName) {
        return streamingChatModels.computeIfAbsent(modelName, this::buildStreamingChatModel);
    }

    protected ChatModel buildChatModel(String modelName) {
        return OpenAiChatModel.builder()
                .apiKey(requireApiKey())
                .modelName(modelName)
                .build();
    }

    protected StreamingChatModel buildStreamingChatModel(String modelName) {
        return OpenAiStreamingChatModel.builder()
                .apiKey(requireApiKey())
                .modelName(modelName)
                .build();
    }


    private String resolveModelName(AgentModelOptions options) {
        if (options != null && options.apiModelId() != null && !options.apiModelId().isBlank()) {
            return options.apiModelId();
        }
        String model = agentProperties.getModel();
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("agent.model is required when no per-turn model is provided");
        }
        return model;
    }

    private String requireApiKey() {
        String apiKey = openAiProperties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OpenAI API key (openai.api-key) is required to call OpenAI provider");
        }
        return apiKey;
    }
}

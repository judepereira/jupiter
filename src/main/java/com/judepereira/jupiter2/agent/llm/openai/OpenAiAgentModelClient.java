package com.judepereira.jupiter2.agent.llm.openai;

import com.judepereira.jupiter2.agent.config.AgentProperties;
import com.judepereira.jupiter2.agent.config.OpenAiProperties;
import com.judepereira.jupiter2.agent.llm.AgentModelClient;
import com.judepereira.jupiter2.agent.llm.AgentModelOptions;
import com.judepereira.jupiter2.agent.llm.dto.Message;
import com.judepereira.jupiter2.agent.llm.dto.ModelResponse;
import com.judepereira.jupiter2.agent.llm.dto.ToolCall;
import com.judepereira.jupiter2.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter2.openai.oauth.OpenAiOAuthService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesStreamingChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.security.MessageDigest;
import java.util.function.Consumer;

@Component
public class OpenAiAgentModelClient implements AgentModelClient {

    private static final String CHATGPT_BACKEND_URL = "https://chatgpt.com/backend-api/codex";
    private static final String OPENAI_API_BASE_URL = "https://api.openai.com/v1";
    private static final String CHATGPT_ACCOUNT_HEADER = "ChatGPT-Account-ID";

    private final OpenAiProperties openAiProperties;
    private final AgentProperties agentProperties;
    private final OpenAiOAuthService openAiOAuthService;
    private final LangChain4jChatRequestFactory chatRequestFactory;
    private final LangChain4jMessageMapper messageMapper;
    private final Map<String, ChatModel> chatModels = new ConcurrentHashMap<>();
    private final Map<String, StreamingChatModel> streamingChatModels = new ConcurrentHashMap<>();

    public OpenAiAgentModelClient(OpenAiProperties openAiProperties, AgentProperties agentProperties) {
        this(openAiProperties, agentProperties, null);
    }

    @Autowired
    public OpenAiAgentModelClient(OpenAiProperties openAiProperties, AgentProperties agentProperties, OpenAiOAuthService openAiOAuthService) {
        this.openAiProperties = openAiProperties;
        this.agentProperties = agentProperties;
        this.openAiOAuthService = openAiOAuthService;
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
        ResolvedAuth auth = resolveAuth();
        ChatRequest request = chatRequestFactory.create(modelName, prepareConversation(conversation, auth), tools, options);
        try {
            return messageMapper.toModelResponse(chatModel(modelName, auth).chat(request));
        } catch (Exception e) {
            throw new IllegalStateException("OpenAI request failed", e);
        }
    }

    @Override
    public ModelResponse chatStreaming(List<Message> conversation, List<ToolDefinition> tools, AgentModelOptions options, Consumer<String> onDelta) {
        String modelName = resolveModelName(options);
        ResolvedAuth auth = resolveAuth();
        ChatRequest request = chatRequestFactory.create(modelName, prepareConversation(conversation, auth), tools, options);
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
            streamingChatModel(modelName, auth).chat(request, handler);
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
        return chatModel(modelName, resolveAuth());
    }

    private ChatModel chatModel(String modelName, ResolvedAuth auth) {
        return chatModels.computeIfAbsent(cacheKey(modelName, auth), ignored -> buildChatModel(modelName, auth.credential(), auth.baseUrl(), auth.accountId()));
    }

    private StreamingChatModel streamingChatModel(String modelName) {
        return streamingChatModel(modelName, resolveAuth());
    }

    private StreamingChatModel streamingChatModel(String modelName, ResolvedAuth auth) {
        return streamingChatModels.computeIfAbsent(cacheKey(modelName, auth), ignored -> buildStreamingChatModel(modelName, auth.credential(), auth.baseUrl(), auth.accountId()));
    }

    protected ChatModel buildChatModel(String modelName) {
        return buildChatModel(modelName, resolveAuth());
    }

    protected ChatModel buildChatModel(String modelName, String credential) {
        return buildChatModel(modelName, credential, OPENAI_API_BASE_URL, Optional.empty());
    }

    protected ChatModel buildChatModel(String modelName, String credential, String baseUrl, Optional<String> accountId) {
        return buildChatModel(modelName, new ResolvedAuth(credential, baseUrl, accountId, accountId.isPresent() ? AuthMode.CHATGPT : AuthMode.API_KEY));
    }

    protected ChatModel buildChatModel(String modelName, ResolvedAuth auth) {
        var builder = OpenAiResponsesChatModel.builder()
                .apiKey(auth.credential())
                .modelName(modelName);
        if (auth.baseUrl() != null) {
            builder.baseUrl(auth.baseUrl());
        }
        if (auth.accountId().isPresent()) {
            builder.httpClientBuilder(new ChatGPTAccountHeaderHttpClientBuilder(auth.accountId().get()));
        }
        return builder.build();
    }

    protected StreamingChatModel buildStreamingChatModel(String modelName) {
        return buildStreamingChatModel(modelName, resolveAuth());
    }

    protected StreamingChatModel buildStreamingChatModel(String modelName, String credential) {
        return buildStreamingChatModel(modelName, credential, OPENAI_API_BASE_URL, Optional.empty());
    }

    protected StreamingChatModel buildStreamingChatModel(String modelName, String credential, String baseUrl, Optional<String> accountId) {
        return buildStreamingChatModel(modelName, new ResolvedAuth(credential, baseUrl, accountId, accountId.isPresent() ? AuthMode.CHATGPT : AuthMode.API_KEY));
    }

    protected StreamingChatModel buildStreamingChatModel(String modelName, ResolvedAuth auth) {
        var builder = OpenAiResponsesStreamingChatModel.builder()
                .apiKey(auth.credential())
                .modelName(modelName);
        if (auth.baseUrl() != null) {
            builder.baseUrl(auth.baseUrl());
        }
        if (auth.accountId().isPresent()) {
            builder.httpClientBuilder(new ChatGPTAccountHeaderHttpClientBuilder(auth.accountId().get()));
        }
        return builder.build();
    }

    private List<Message> prepareConversation(List<Message> conversation, ResolvedAuth auth) {
        if (conversation.isEmpty() || auth.mode() != AuthMode.CHATGPT) {
            return conversation;
        }

        Message first = conversation.get(0);
        if (first.getRole() != Message.Role.SYSTEM) {
            return conversation;
        }

        List<Message> transformed = new java.util.ArrayList<>(conversation);
        transformed.set(0, new Message(Message.Role.USER, first.getContent()));
        return transformed;
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

    private String cacheKey(String modelName, ResolvedAuth auth) {
        return modelName + "|" + auth.mode() + "|" + (auth.baseUrl() == null ? OPENAI_API_BASE_URL : auth.baseUrl()) + "|" + auth.accountId().orElse("") + "|" + credentialMarker(auth.credential());
    }

    private String credentialMarker(String credential) {
        return "cred:" + fingerprint(credential);
    }

    private ResolvedAuth resolveAuth() {
        if (openAiOAuthService != null) {
            Optional<String> accessToken = openAiOAuthService.currentAccessToken();
            if (accessToken.isPresent()) {
                return new ResolvedAuth(accessToken.get(), CHATGPT_BACKEND_URL, openAiOAuthService.currentAccountId(), AuthMode.CHATGPT);
            }
        }
        return new ResolvedAuth(requireApiKey(), OPENAI_API_BASE_URL, Optional.empty(), AuthMode.API_KEY);
    }

    private String fingerprint(String credential) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(credential.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to fingerprint OpenAI credential", e);
        }
    }

    private String requireApiKey() {
        String apiKey = openAiProperties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OpenAI API key (openai.api-key) is required to call OpenAI provider");
        }
        return apiKey;
    }

    private record ResolvedAuth(String credential, String baseUrl, Optional<String> accountId, AuthMode mode) {
    }

    private enum AuthMode {
        API_KEY,
        CHATGPT
    }

    private static final class ChatGPTAccountHeaderHttpClientBuilder implements HttpClientBuilder {
        private final JdkHttpClientBuilder delegate = JdkHttpClient.builder();
        private final String accountId;

        private ChatGPTAccountHeaderHttpClientBuilder(String accountId) {
            this.accountId = accountId;
        }

        @Override
        public Duration connectTimeout() {
            return delegate.connectTimeout();
        }

        @Override
        public HttpClientBuilder connectTimeout(Duration duration) {
            delegate.connectTimeout(duration);
            return this;
        }

        @Override
        public Duration readTimeout() {
            return delegate.readTimeout();
        }

        @Override
        public HttpClientBuilder readTimeout(Duration duration) {
            delegate.readTimeout(duration);
            return this;
        }

        @Override
        public HttpClient build() {
            return new ChatGPTAccountHeaderHttpClient(delegate.build(), accountId);
        }
    }

    private static final class ChatGPTAccountHeaderHttpClient implements HttpClient {
        private final HttpClient delegate;
        private final String accountId;

        private ChatGPTAccountHeaderHttpClient(HttpClient delegate, String accountId) {
            this.delegate = delegate;
            this.accountId = accountId;
        }

        @Override
        public SuccessfulHttpResponse execute(HttpRequest request) {
            return delegate.execute(withAccountHeader(request));
        }

        @Override
        public void execute(HttpRequest request, ServerSentEventListener listener) {
            delegate.execute(withAccountHeader(request), listener);
        }

        @Override
        public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
            delegate.execute(withAccountHeader(request), parser, listener);
        }

        private HttpRequest withAccountHeader(HttpRequest request) {
            Map<String, List<String>> headers = new LinkedHashMap<>(request.headers());
            headers.put(CHATGPT_ACCOUNT_HEADER, List.of(accountId));
            HttpRequest.Builder builder = HttpRequest.builder()
                    .method(request.method())
                    .url(request.url())
                    .headers(headers);
            if (!request.formDataFields().isEmpty()) {
                builder.formDataFields(request.formDataFields());
            }
            if (!request.formDataFiles().isEmpty()) {
                builder.formDataFiles(request.formDataFiles());
            }
            if (request.body() != null) {
                builder.body(request.body());
            }
            return builder.build();
        }
    }
}

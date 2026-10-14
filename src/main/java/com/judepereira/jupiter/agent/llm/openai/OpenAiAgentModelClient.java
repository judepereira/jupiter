package com.judepereira.jupiter.agent.llm.openai;

import com.judepereira.jupiter.agent.config.AgentProperties;
import com.judepereira.jupiter.agent.config.OpenAiProperties;
import com.judepereira.jupiter.agent.harness.StreamCancelledException;
import com.judepereira.jupiter.agent.llm.AgentModelClient;
import com.judepereira.jupiter.agent.llm.AgentModelOptions;
import com.judepereira.jupiter.agent.llm.dto.Message;
import com.judepereira.jupiter.agent.llm.dto.ModelResponse;
import com.judepereira.jupiter.agent.llm.dto.ModelResponseMetadata;
import com.judepereira.jupiter.agent.llm.dto.ToolCall;
import com.judepereira.jupiter.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter.openai.oauth.OpenAiOAuthService;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesStreamingChatModel;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class OpenAiAgentModelClient implements AgentModelClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiAgentModelClient.class);
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

    @Autowired
    public OpenAiAgentModelClient(OpenAiProperties openAiProperties, AgentProperties agentProperties,
            OpenAiOAuthService openAiOAuthService) {
        this.openAiProperties = openAiProperties;
        this.agentProperties = agentProperties;
        this.openAiOAuthService = openAiOAuthService;
        this.messageMapper = new LangChain4jMessageMapper(new ToolArgumentsCodec());
        this.chatRequestFactory = new LangChain4jChatRequestFactory(messageMapper,
                new LangChain4jToolSpecificationMapper(), new OpenAiRequestParametersMapper());
    }

    @Override
    public ModelResponse chat(List<Message> conversation, List<ToolDefinition> tools) {
        return chat(conversation, tools, null);
    }

    @Override
    public ModelResponse chat(List<Message> conversation, List<ToolDefinition> tools, AgentModelOptions options) {
        String modelName = resolveModelName(options);
        ResolvedAuth auth = resolveAuth();
        ChatRequest request = chatRequestFactory.create(modelName, prepareConversation(conversation, auth), tools,
                options);
        return executeWithRetry(() -> messageMapper.toModelResponse(chatModel(modelName, auth).chat(request)),
                "OpenAI request failed");
    }

    @Override
    public ModelResponse chatStreaming(List<Message> conversation, List<ToolDefinition> tools,
            AgentModelOptions options, Consumer<String> onDelta) {
        return chatStreamingInternal(conversation, tools, options, onDelta, retry -> {
        }, false);
    }

    @Override
    public ModelResponse chatStreaming(List<Message> conversation, List<ToolDefinition> tools,
            AgentModelOptions options, Consumer<String> onDelta, Consumer<AgentModelClient.RetryNotice> onRetry) {
        return chatStreamingInternal(conversation, tools, options, onDelta, onRetry, true);
    }

    private ModelResponse chatStreamingInternal(List<Message> conversation, List<ToolDefinition> tools,
            AgentModelOptions options, Consumer<String> onDelta, Consumer<AgentModelClient.RetryNotice> onRetry,
            boolean retryAfterOutput) {
        String modelName = resolveModelName(options);
        ResolvedAuth auth = resolveAuth();
        ChatRequest request = chatRequestFactory.create(modelName, prepareConversation(conversation, auth), tools,
                options);
        return executeStreamingWithRetry(modelName, auth, request, onDelta, onRetry, retryAfterOutput);
    }

    @Override
    public ModelResponse chatStreaming(List<Message> conversation, List<ToolDefinition> tools,
            Consumer<String> onDelta) {
        return chatStreaming(conversation, tools, null, onDelta);
    }

    private ModelResponse executeStreamingWithRetry(String modelName, ResolvedAuth auth, ChatRequest request,
            Consumer<String> onDelta, Consumer<AgentModelClient.RetryNotice> onRetry, boolean retryAfterOutput) {
        OpenAiRetryPolicy retryPolicy = retryPolicy();
        int retriesUsed = 0;
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("OpenAI streaming request interrupted");
            }
            StreamingAttemptState attemptState = new StreamingAttemptState(onDelta);
            Throwable failure = null;
            try {
                streamingChatModel(modelName, auth).chat(request, attemptState.handler());
                attemptState.await();
                failure = attemptState.error();
            } catch (InterruptedException e) {
                attemptState.close();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("OpenAI streaming request interrupted", e);
            } catch (StreamCancelledException e) {
                attemptState.close();
                throw e;
            } catch (Exception e) {
                failure = e;
            } finally {
                attemptState.close();
            }

            if (attemptState.consumerFailure() != null) {
                Throwable consumerFailure = attemptState.consumerFailure();
                if (containsCancellation(consumerFailure)) {
                    throw new StreamCancelledException();
                }
                throw new IllegalStateException("OpenAI streaming response consumer failed", consumerFailure);
            }
            if (failure == null) {
                return attemptState.finish();
            }
            if (containsInterruption(failure)) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("OpenAI streaming request interrupted", failure);
            }
            if (containsCancellation(failure)) {
                throw new StreamCancelledException();
            }
            attemptState.close();
            if (retryPolicy.shouldRetry(failure) && retriesUsed < retryPolicy.maxRetries()
                    && (retryAfterOutput || !attemptState.hasOutput())) {
                retriesUsed++;
                long delayMillis = retryPolicy.delayMillis(retriesUsed);
                logRetry(retriesUsed, delayMillis, failure);
                try {
                    onRetry.accept(new AgentModelClient.RetryNotice(retriesUsed, delayMillis, failure));
                } catch (RuntimeException callbackFailure) {
                    if (containsCancellation(callbackFailure)) {
                        throw new StreamCancelledException();
                    }
                    throw new IllegalStateException("OpenAI retry callback failed", callbackFailure);
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("OpenAI streaming request interrupted");
                }
                try {
                    retryPolicy.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("OpenAI streaming request interrupted", e);
                }
                continue;
            }
            throw attemptState.streamingFailure(failure);
        }
    }

    private void logRetry(int attempt, long delayMillis, Throwable cause) {
        log.warn("Transient OpenAI request failure; retrying attempt {} in {} ms", attempt, delayMillis, cause);
    }

    private <T> T executeWithRetry(Callable<T> operation, String failureMessage) {
        OpenAiRetryPolicy retryPolicy = retryPolicy();
        int retriesUsed = 0;
        while (true) {
            try {
                return operation.call();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failureMessage, e);
            } catch (StreamCancelledException e) {
                throw e;
            } catch (Exception e) {
                if (retryPolicy.shouldRetry(e) && retriesUsed < retryPolicy.maxRetries()) {
                    retriesUsed++;
                    try {
                        retryPolicy.sleep(retryPolicy.delayMillis(retriesUsed));
                        continue;
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(failureMessage, interruptedException);
                    }
                }
                throw new IllegalStateException(failureMessage, e);
            }
        }
    }

    private OpenAiRetryPolicy retryPolicy() {
        return new OpenAiRetryPolicy(openAiProperties.getRetry());
    }

    private ChatModel chatModel(String modelName) {
        return chatModel(modelName, resolveAuth());
    }

    private ChatModel chatModel(String modelName, ResolvedAuth auth) {
        return chatModels.computeIfAbsent(cacheKey(modelName, auth),
                ignored -> buildChatModel(modelName, auth.credential(), auth.baseUrl(), auth.accountId()));
    }

    private StreamingChatModel streamingChatModel(String modelName) {
        return streamingChatModel(modelName, resolveAuth());
    }

    private StreamingChatModel streamingChatModel(String modelName, ResolvedAuth auth) {
        return streamingChatModels.computeIfAbsent(cacheKey(modelName, auth),
                ignored -> buildStreamingChatModel(modelName, auth.credential(), auth.baseUrl(), auth.accountId()));
    }

    protected ChatModel buildChatModel(String modelName) {
        return buildChatModel(modelName, resolveAuth());
    }

    protected ChatModel buildChatModel(String modelName, String credential) {
        return buildChatModel(modelName, credential, OPENAI_API_BASE_URL, Optional.empty());
    }

    protected ChatModel buildChatModel(String modelName, String credential, String baseUrl,
            Optional<String> accountId) {
        return buildChatModel(modelName, new ResolvedAuth(credential, baseUrl, accountId,
                accountId.isPresent() ? AuthMode.CHATGPT : AuthMode.API_KEY));
    }

    protected ChatModel buildChatModel(String modelName, ResolvedAuth auth) {
        var builder = OpenAiResponsesChatModel.builder().apiKey(auth.credential()).modelName(modelName);
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

    protected StreamingChatModel buildStreamingChatModel(String modelName, String credential, String baseUrl,
            Optional<String> accountId) {
        return buildStreamingChatModel(modelName, new ResolvedAuth(credential, baseUrl, accountId,
                accountId.isPresent() ? AuthMode.CHATGPT : AuthMode.API_KEY));
    }

    protected StreamingChatModel buildStreamingChatModel(String modelName, ResolvedAuth auth) {
        var builder = OpenAiResponsesStreamingChatModel.builder().apiKey(auth.credential()).modelName(modelName);
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

        List<Message> transformed = new ArrayList<>(conversation.size());
        for (Message message : conversation) {
            if (message.getRole() == Message.Role.SYSTEM) {
                transformed.add(new Message(Message.Role.USER, message.getContent(), message.getToolCallId(),
                        message.getToolCalls(), null));
            } else {
                transformed.add(message);
            }
        }
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
        return modelName + "|" + auth.mode() + "|" + (auth.baseUrl() == null ? OPENAI_API_BASE_URL : auth.baseUrl())
                + "|" + auth.accountId().orElse("") + "|" + credentialMarker(auth.credential());
    }

    private String credentialMarker(String credential) {
        return "cred:" + fingerprint(credential);
    }

    private ResolvedAuth resolveAuth() {
        if (openAiOAuthService != null) {
            Optional<String> accessToken = openAiOAuthService.currentAccessToken();
            if (accessToken.isPresent()) {
                return new ResolvedAuth(accessToken.get(), CHATGPT_BACKEND_URL, openAiOAuthService.currentAccountId(),
                        AuthMode.CHATGPT);
            }
        }
        return new ResolvedAuth(requireApiKey(), OPENAI_API_BASE_URL, Optional.empty(), AuthMode.API_KEY);
    }

    private String fingerprint(String credential) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(credential.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to fingerprint OpenAI credential", e);
        }
    }

    private String requireApiKey() {
        String apiKey = openAiProperties.effectiveApiKey();
        if (!openAiProperties.hasApiKey()) {
            throw new IllegalStateException("OpenAI API key (openai.api-key) is required to call OpenAI provider");
        }
        return apiKey;
    }

    private record ResolvedAuth(String credential, String baseUrl, Optional<String> accountId, AuthMode mode) {
    }

    private final class OpenAiRetryPolicy {
        private final int maxRetries;
        private final Duration initialBackoff;
        private final Duration maxBackoff;

        private OpenAiRetryPolicy(OpenAiProperties.Retry retry) {
            this.maxRetries = Math.max(0, retry.getMaxRetries());
            this.initialBackoff = retry.getInitialBackoff();
            this.maxBackoff = retry.getMaxBackoff();
        }

        private long delayMillis(int retryNumber) {
            long initial = initialBackoff.toMillis();
            int shift = Math.max(0, retryNumber - 1);
            long scaled = shift >= 63 || initial > (Long.MAX_VALUE >> shift) ? Long.MAX_VALUE : initial << shift;
            return Math.min(maxBackoff.toMillis(), scaled);
        }

        private void sleep(long delayMillis) throws InterruptedException {
            Thread.sleep(delayMillis);
        }

        private int maxRetries() {
            return maxRetries;
        }

        private boolean shouldRetry(Throwable throwable) {
            return isTransient(throwable);
        }
    }

    private final class StreamingAttemptState {
        private final Consumer<String> onDelta;
        private final AtomicReference<ModelResponse> response = new AtomicReference<>();
        private final AtomicReference<ToolCall> toolCall = new AtomicReference<>();
        private final AtomicReference<Throwable> error = new AtomicReference<>();
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final AtomicBoolean output = new AtomicBoolean();
        private final AtomicReference<Throwable> consumerFailure = new AtomicReference<>();
        private final CountDownLatch done = new CountDownLatch(1);

        private StreamingAttemptState(Consumer<String> onDelta) {
            this.onDelta = onDelta;
        }

        private StreamingChatResponseHandler handler() {
            return new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partialResponse) {
                    synchronized (StreamingAttemptState.this) {
                        if (active.get() && partialResponse != null && !partialResponse.isEmpty()) {
                            output.set(true);
                            if (onDelta != null) {
                                try {
                                    onDelta.accept(partialResponse);
                                } catch (RuntimeException e) {
                                    consumerFailure.compareAndSet(null, e);
                                    active.set(false);
                                    done.countDown();
                                }
                            }
                        }
                    }
                }

                @Override
                public void onCompleteToolCall(CompleteToolCall completeToolCall) {
                    synchronized (StreamingAttemptState.this) {
                        if (active.get()) {
                            toolCall.compareAndSet(null,
                                    messageMapper.toToolCall(completeToolCall.toolExecutionRequest()));
                            output.set(true);
                        }
                    }
                }

                @Override
                public void onCompleteResponse(ChatResponse chatResponse) {
                    synchronized (StreamingAttemptState.this) {
                        if (active.get()) {
                            response.set(messageMapper.toModelResponse(chatResponse));
                            if (toolCall.get() == null && response.get() != null) {
                                toolCall.compareAndSet(null, response.get().getToolCall());
                            }
                            active.set(false);
                            done.countDown();
                        }
                    }
                }

                @Override
                public void onError(Throwable throwable) {
                    synchronized (StreamingAttemptState.this) {
                        if (active.get()) {
                            error.set(throwable);
                            active.set(false);
                            done.countDown();
                        }
                    }
                }
            };
        }

        private void await() throws InterruptedException {
            done.await();
        }

        private Throwable error() {
            return error.get();
        }

        private boolean hasOutput() {
            return output.get();
        }

        private Throwable consumerFailure() {
            return consumerFailure.get();
        }

        private void close() {
            synchronized (this) {
                active.set(false);
            }
        }

        private ModelResponse finish() {
            if (response.get() != null) {
                return toolCall.get() == null
                        ? response.get()
                        : new ModelResponse(response.get().getAssistantText(), toolCall.get(),
                                response.get().getMetadata(), null);
            }
            return new ModelResponse(null, toolCall.get(), ModelResponseMetadata.empty(), null);
        }

        private IllegalStateException streamingFailure(Throwable throwable) {
            Throwable root = throwable;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            String message = root.getMessage();
            if (message == null || message.isBlank()) {
                message = throwable.getMessage();
            }
            return new IllegalStateException(message == null || message.isBlank()
                    ? "OpenAI streaming request failed"
                    : "OpenAI streaming request failed: " + message, throwable);
        }
    }

    private boolean containsInterruption(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof InterruptedException
                    || (current instanceof InterruptedIOException && !(current instanceof SocketTimeoutException))) {
                return true;
            }
        }
        return false;
    }

    private boolean containsCancellation(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof StreamCancelledException) {
                return true;
            }
        }
        return false;
    }

    private boolean isTransient(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof RateLimitException || current instanceof InternalServerException) {
                return true;
            }
            // LangChain4j reports HTTP-200 Responses API response.failed events with this
            // message,
            // losing the server status/code that would otherwise identify the transient
            // overload.
            if (current.getClass() == RuntimeException.class
                    && "Response failed: Our servers are currently overloaded. Please try again later."
                            .equals(current.getMessage())) {
                return true;
            }
            if (current instanceof HttpException httpException
                    && (httpException.statusCode() == 429 || httpException.statusCode() / 100 == 5)) {
                return true;
            }
            if (isConnectivityFailure(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isConnectivityFailure(Throwable throwable) {
        return throwable instanceof IOException || throwable instanceof ConnectException
                || throwable instanceof SocketTimeoutException || throwable instanceof UnknownHostException;
    }

    private enum AuthMode {
        API_KEY, CHATGPT
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
            HttpRequest.Builder builder = HttpRequest.builder().method(request.method()).url(request.url())
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

package com.judepereira.jupiter.agent.llm.openai;

import com.judepereira.jupiter.agent.config.AgentProperties;
import com.judepereira.jupiter.agent.config.OpenAiProperties;
import com.judepereira.jupiter.agent.catalog.ThinkingLevel;
import com.judepereira.jupiter.agent.llm.AgentModelOptions;
import com.judepereira.jupiter.agent.llm.dto.Message;
import com.judepereira.jupiter.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter.agent.llm.dto.ToolSchema;
import com.judepereira.jupiter.openai.oauth.OpenAiOAuthService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiResponsesChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static com.judepereira.jupiter.agent.llm.dto.ToolParameter.string;

public class OpenAiAgentModelClientTest {

    private static OpenAiProperties openAiProperties() {
        return openAiProperties(10, Duration.ofSeconds(1), Duration.ofSeconds(120));
    }

    private static OpenAiProperties openAiProperties(int maxRetries, Duration initialBackoff, Duration maxBackoff) {
        OpenAiProperties openAiProperties = new OpenAiProperties();
        openAiProperties.setApiKey("api-key-123");
        OpenAiProperties.Retry retry = new OpenAiProperties.Retry();
        retry.setMaxRetries(maxRetries);
        retry.setInitialBackoff(initialBackoff);
        retry.setMaxBackoff(maxBackoff);
        openAiProperties.setRetry(retry);
        return openAiProperties;
    }

    @Test
    public void source_does_not_reference_raw_http_or_mapping_helpers() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/judepereira/jupiter/agent/llm/openai/OpenAiAgentModelClient.java"));

        assertFalse(source.contains("HttpURLConnection"));
        assertFalse(source.contains("java.net.http"));
        assertFalse(source.contains("ObjectMapper"));
        assertFalse(source.contains("TypeReference"));
        assertFalse(source.contains("JsonObjectSchema"));
    }

    @Test
    public void chat_honors_per_turn_model_and_reasoning_and_parses_tool_calls() {
        AtomicReference<ChatRequest> capturedRequest = new AtomicReference<>();
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            capturedRequest.set(invocation.getArgument(0));
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("assistant", List.of(ToolExecutionRequest.builder()
                            .id("call-1")
                            .name("read_file")
                            .arguments("{\"path\":\"notes.txt\"}")
                            .build())))
                    .id("response-1")
                    .modelName("per-turn-model")
                    .tokenUsage(OpenAiTokenUsage.builder().inputTokenCount(12).outputTokenCount(8).totalTokenCount(20).build())
                    .finishReason(FinishReason.TOOL_EXECUTION)
                    .build();
        });

        RecordingClient client = new RecordingClient(chatModel, null);
        var response = client.chat(
                List.of(new Message(Message.Role.USER, "read it", null, null)),
                List.of(new ToolDefinition("read_file", "Read a file", ToolSchema.object(string("path", "path")).required("path"))),
                new AgentModelOptions("turn-model", "per-turn-model", ThinkingLevel.HIGH, true, null)
        );

        assertEquals(List.of("per-turn-model"), client.chatModelNames());
        assertEquals("per-turn-model", capturedRequest.get().modelName());
        assertInstanceOf(OpenAiResponsesChatRequestParameters.class, capturedRequest.get().parameters());
        assertEquals("high", ((OpenAiResponsesChatRequestParameters) capturedRequest.get().parameters()).reasoningEffort());
        assertEquals(1, capturedRequest.get().toolSpecifications().size());
        assertEquals("read_file", capturedRequest.get().toolSpecifications().get(0).name());
        assertEquals("assistant", response.getAssistantText());
        assertEquals(12, response.getMetadata().inputTokenCount());
        assertEquals(8, response.getMetadata().outputTokenCount());
        assertEquals(20, response.getMetadata().totalTokenCount());
        assertEquals("response-1", response.getMetadata().responseId());
        assertEquals("per-turn-model", response.getMetadata().modelId());
        assertEquals("TOOL_EXECUTION", response.getMetadata().finishReason());
        assertNotNull(response.getToolCall());
        assertEquals("call-1", response.getToolCall().getToolCallId());
        assertEquals("read_file", response.getToolCall().getToolName());
        assertEquals("notes.txt", response.getToolCall().getArguments().get("path"));
        verify(chatModel, times(1)).chat(any(ChatRequest.class));
    }

    @Test
    public void api_key_mode_preserves_a_leading_system_message() {
        AtomicReference<ChatRequest> capturedRequest = new AtomicReference<>();
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            capturedRequest.set(invocation.getArgument(0));
            return ChatResponse.builder().aiMessage(AiMessage.from("assistant")).build();
        });

        RecordingClient client = new RecordingClient(chatModel, null);
        var response = client.chat(
                List.of(new Message(Message.Role.SYSTEM, "sys", null, null), new Message(Message.Role.USER, "u", null, null)),
                List.of()
        );

        assertEquals("assistant", response.getAssistantText());
        assertInstanceOf(SystemMessage.class, capturedRequest.get().messages().get(0));
        assertEquals("sys", ((SystemMessage) capturedRequest.get().messages().get(0)).text());
        assertInstanceOf(UserMessage.class, capturedRequest.get().messages().get(1));
        assertEquals("u", ((UserMessage) capturedRequest.get().messages().get(1)).singleText());
    }

    @Test
    public void oauth_mode_rewrites_every_system_message_to_user_message() {
        AtomicReference<ChatRequest> capturedRequest = new AtomicReference<>();
        OpenAiOAuthService oauthService = mock(OpenAiOAuthService.class);
        when(oauthService.currentAccessToken()).thenReturn(Optional.of("oauth-access-token"));
        when(oauthService.currentAccountId()).thenReturn(Optional.of("acct-123"));
        OpenAiProperties openAiProperties = new OpenAiProperties();
        openAiProperties.setApiKey("api-key-123");

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            capturedRequest.set(invocation.getArgument(0));
            return ChatResponse.builder().aiMessage(AiMessage.from("oauth")).build();
        });

        class OAuthRecordingClient extends OpenAiAgentModelClient {
            private OAuthRecordingClient() {
                super(openAiProperties, new AgentProperties(), oauthService);
            }

            @Override
            protected ChatModel buildChatModel(String modelName, String credential, String baseUrl, Optional<String> accountId) {
                return chatModel;
            }
        }

        OAuthRecordingClient client = new OAuthRecordingClient();
        var response = client.chat(
                List.of(
                        new Message(Message.Role.SYSTEM, "sys-1", null, null),
                        new Message(Message.Role.USER, "u-1", null, null),
                        new Message(Message.Role.SYSTEM, "sys-2", null, null),
                        new Message(Message.Role.ASSISTANT, "a-1", null, null)
                ),
                List.of()
        );

        assertEquals("oauth", response.getAssistantText());
        assertInstanceOf(UserMessage.class, capturedRequest.get().messages().get(0));
        assertEquals("sys-1", ((UserMessage) capturedRequest.get().messages().get(0)).singleText());
        assertInstanceOf(UserMessage.class, capturedRequest.get().messages().get(1));
        assertEquals("u-1", ((UserMessage) capturedRequest.get().messages().get(1)).singleText());
        assertInstanceOf(UserMessage.class, capturedRequest.get().messages().get(2));
        assertEquals("sys-2", ((UserMessage) capturedRequest.get().messages().get(2)).singleText());
        assertInstanceOf(AiMessage.class, capturedRequest.get().messages().get(3));
        assertEquals("a-1", ((AiMessage) capturedRequest.get().messages().get(3)).text());
        assertTrue(capturedRequest.get().messages().stream().noneMatch(SystemMessage.class::isInstance));
        verify(oauthService, times(1)).currentAccessToken();
        verify(oauthService, times(1)).currentAccountId();
    }

    @Test
    public void base_overloads_construct_real_chat_and_streaming_models_without_recursing() {
        class DirectConstructionClient extends OpenAiAgentModelClient {
            private DirectConstructionClient() {
                super(openAiProperties(), new AgentProperties(), null);
            }

            private static OpenAiProperties openAiProperties() {
                OpenAiProperties openAiProperties = new OpenAiProperties();
                openAiProperties.setApiKey("api-key-123");
                return openAiProperties;
            }

            private ChatModel chat(String modelName, String credential, String baseUrl, Optional<String> accountId) {
                return super.buildChatModel(modelName, credential, baseUrl, accountId);
            }

            private StreamingChatModel streaming(String modelName, String credential, String baseUrl, Optional<String> accountId) {
                return super.buildStreamingChatModel(modelName, credential, baseUrl, accountId);
            }
        }

        DirectConstructionClient client = new DirectConstructionClient();

        assertNotNull(client.chat("gpt-5.4", "api-key-123", "https://api.openai.com/v1", Optional.empty()));
        assertNotNull(client.streaming("gpt-5.4", "api-key-123", "https://api.openai.com/v1", Optional.empty()));
        assertNotNull(client.chat("gpt-5.4", "oauth-token", "https://chatgpt.com/backend-api/codex", Optional.of("acct-123")));
        assertNotNull(client.streaming("gpt-5.4", "oauth-token", "https://chatgpt.com/backend-api/codex", Optional.of("acct-123")));
    }

    @Test
    public void streaming_honors_per_turn_model_and_parses_streamed_tool_calls() {
        AtomicReference<ChatRequest> capturedRequest = new AtomicReference<>();
        StreamingChatModel streamingModel = mock(StreamingChatModel.class);
        doAnswer(invocation -> {
            capturedRequest.set(invocation.getArgument(0));
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onPartialResponse("hel");
            handler.onPartialResponse("lo");
            handler.onCompleteToolCall(new CompleteToolCall(0, ToolExecutionRequest.builder()
                    .id("stream-call")
                    .name("write_file")
                    .arguments("{\"path\":\"out.txt\"}")
                    .build()));
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from("done"))
                    .id("stream-response-1")
                    .modelName("stream-model")
                    .tokenUsage(OpenAiTokenUsage.builder().inputTokenCount(30).outputTokenCount(15).totalTokenCount(45).build())
                    .finishReason(FinishReason.TOOL_EXECUTION)
                    .build());
            return null;
        }).when(streamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        RecordingClient client = new RecordingClient(null, streamingModel);
        List<String> deltas = new ArrayList<>();
        var response = client.chatStreaming(
                List.of(new Message(Message.Role.USER, "stream it", null, null)),
                List.of(new ToolDefinition("write_file", "Write a file", ToolSchema.object(string("path", "path")).required("path"))),
                new AgentModelOptions("turn-model", "stream-model", ThinkingLevel.MEDIUM, true, null),
                deltas::add
        );

        assertEquals(List.of("stream-model"), client.streamingModelNames());
        assertEquals("stream-model", capturedRequest.get().modelName());
        assertInstanceOf(OpenAiResponsesChatRequestParameters.class, capturedRequest.get().parameters());
        assertEquals("medium", ((OpenAiResponsesChatRequestParameters) capturedRequest.get().parameters()).reasoningEffort());
        assertEquals(List.of("hel", "lo"), deltas);
        assertEquals("done", response.getAssistantText());
        assertEquals(30, response.getMetadata().inputTokenCount());
        assertEquals(15, response.getMetadata().outputTokenCount());
        assertEquals(45, response.getMetadata().totalTokenCount());
        assertEquals("stream-response-1", response.getMetadata().responseId());
        assertEquals("stream-model", response.getMetadata().modelId());
        assertEquals("TOOL_EXECUTION", response.getMetadata().finishReason());
        assertNotNull(response.getToolCall());
        assertEquals("stream-call", response.getToolCall().getToolCallId());
        assertEquals("write_file", response.getToolCall().getToolName());
        assertEquals("out.txt", response.getToolCall().getArguments().get("path"));
        verify(streamingModel, times(1)).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    }

    @Test
    public void oauth_access_token_preempts_api_key_and_uses_a_separate_cache_entry() {
        OpenAiOAuthService oauthService = mock(OpenAiOAuthService.class);
        when(oauthService.currentAccessToken()).thenReturn(Optional.empty(), Optional.of("oauth-access-token"));
        when(oauthService.currentAccountId()).thenReturn(Optional.of("acct-123"));

        ChatModel apiKeyModel = mock(ChatModel.class);
        ChatModel oauthModel = mock(ChatModel.class);
        when(apiKeyModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("api-key")).build());
        when(oauthModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("oauth")).build());

        TrackingClient client = new TrackingClient(apiKeyModel, oauthModel, oauthService);

        assertEquals("api-key", client.chat(List.of(new Message(Message.Role.USER, "first", null, null)), List.of()).getAssistantText());
        assertEquals("oauth", client.chat(List.of(new Message(Message.Role.USER, "second", null, null)), List.of()).getAssistantText());

        assertEquals(List.of("gpt-5.4", "gpt-5.4"), client.chatModelNames());
        assertEquals(List.of("api-key-123", "oauth-access-token"), client.chatModelCredentials());
        assertEquals(List.of("https://api.openai.com/v1", "https://chatgpt.com/backend-api/codex"), client.chatModelBaseUrls());
        assertEquals(List.of(Optional.empty(), Optional.of("acct-123")), client.chatModelAccountIds());
        verify(apiKeyModel, times(1)).chat(any(ChatRequest.class));
        verify(oauthModel, times(1)).chat(any(ChatRequest.class));
        verify(oauthService, times(2)).currentAccessToken();
        verify(oauthService, times(1)).currentAccountId();
    }

    @Test
    public void non_streaming_retries_transient_failures_then_succeeds() {
        OpenAiProperties openAiProperties = openAiProperties(2, Duration.ZERO, Duration.ZERO);
        ChatModel chatModel = mock(ChatModel.class);
        AtomicInteger attempts = new AtomicInteger();
        when(chatModel.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            if (attempts.getAndIncrement() == 0) {
                throw new IOException("transient");
            }
            return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
        });

        RecordingClient client = new RecordingClient(chatModel, null, openAiProperties);

        assertEquals("ok", client.chat(List.of(new Message(Message.Role.USER, "retry", null, null)), List.of()).getAssistantText());
        assertEquals(2, attempts.get());
        verify(chatModel, times(2)).chat(any(ChatRequest.class));
    }

    @Test
    public void streaming_retries_a_transient_upstream_failure_before_any_partial_output() {
        OpenAiProperties openAiProperties = openAiProperties(2, Duration.ZERO, Duration.ZERO);
        StreamingChatModel streamingModel = mock(StreamingChatModel.class);
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            if (attempts.getAndIncrement() == 0) {
                handler.onError(new IOException("temporary"));
                return null;
            }
            handler.onPartialResponse("he");
            handler.onPartialResponse("llo");
            handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("done")).build());
            return null;
        }).when(streamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        RecordingClient client = new RecordingClient(null, streamingModel, openAiProperties);
        List<String> deltas = new ArrayList<>();

        assertEquals("done", client.chatStreaming(List.of(new Message(Message.Role.USER, "stream", null, null)), List.of(), deltas::add).getAssistantText());
        assertEquals(List.of("he", "llo"), deltas);
        assertEquals(2, attempts.get());
        verify(streamingModel, times(2)).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    }

    @Test
    public void streaming_does_not_retry_after_a_partial_delta_has_already_been_emitted() {
        OpenAiProperties openAiProperties = openAiProperties(2, Duration.ZERO, Duration.ZERO);
        StreamingChatModel streamingModel = mock(StreamingChatModel.class);
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            attempts.incrementAndGet();
            handler.onPartialResponse("he");
            handler.onError(new IOException("after-partial"));
            return null;
        }).when(streamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        RecordingClient client = new RecordingClient(null, streamingModel, openAiProperties);
        List<String> deltas = new ArrayList<>();

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> client.chatStreaming(List.of(new Message(Message.Role.USER, "stream", null, null)), List.of(), deltas::add));

        assertEquals("OpenAI streaming request failed", exception.getMessage());
        assertEquals(List.of("he"), deltas);
        assertEquals(1, attempts.get());
        verify(streamingModel, times(1)).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    }

    @Test
    public void retry_count_is_capped_by_configured_max_retries() {
        OpenAiProperties openAiProperties = openAiProperties(1, Duration.ZERO, Duration.ZERO);
        ChatModel chatModel = mock(ChatModel.class);
        AtomicInteger attempts = new AtomicInteger();
        when(chatModel.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            attempts.incrementAndGet();
            throw new IOException("still failing");
        });

        RecordingClient client = new RecordingClient(chatModel, null, openAiProperties);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> client.chat(List.of(new Message(Message.Role.USER, "retry", null, null)), List.of()));

        assertEquals("OpenAI request failed", exception.getMessage());
        assertEquals(2, attempts.get());
        verify(chatModel, times(2)).chat(any(ChatRequest.class));
    }

    private static final class RecordingClient extends OpenAiAgentModelClient {
        private final List<String> chatModelNames = new ArrayList<>();
        private final List<String> streamingModelNames = new ArrayList<>();
        private final ChatModel chatModel;
        private final StreamingChatModel streamingChatModel;

        private RecordingClient(ChatModel chatModel, StreamingChatModel streamingChatModel) {
            this(chatModel, streamingChatModel, openAiProperties());
        }

        private RecordingClient(ChatModel chatModel, StreamingChatModel streamingChatModel, OpenAiProperties openAiProperties) {
            super(openAiProperties, new AgentProperties(), null);
            this.chatModel = chatModel;
            this.streamingChatModel = streamingChatModel;
        }

        private static OpenAiProperties openAiProperties() {
            return openAiProperties(10, Duration.ofSeconds(1), Duration.ofSeconds(120));
        }

        private static OpenAiProperties openAiProperties(int maxRetries, Duration initialBackoff, Duration maxBackoff) {
            OpenAiProperties openAiProperties = new OpenAiProperties();
            openAiProperties.setApiKey("api-key-123");
            OpenAiProperties.Retry retry = new OpenAiProperties.Retry();
            retry.setMaxRetries(maxRetries);
            retry.setInitialBackoff(initialBackoff);
            retry.setMaxBackoff(maxBackoff);
            openAiProperties.setRetry(retry);
            return openAiProperties;
        }

        @Override
        protected ChatModel buildChatModel(String modelName, String credential, String baseUrl, Optional<String> accountId) {
            chatModelNames.add(modelName);
            return chatModel;
        }

        @Override
        protected StreamingChatModel buildStreamingChatModel(String modelName, String credential, String baseUrl, Optional<String> accountId) {
            streamingModelNames.add(modelName);
            return streamingChatModel;
        }

        private List<String> chatModelNames() {
            return chatModelNames;
        }

        private List<String> streamingModelNames() {
            return streamingModelNames;
        }
    }

    private static final class TrackingClient extends OpenAiAgentModelClient {
        private final List<String> chatModelNames = new ArrayList<>();
        private final List<String> chatModelCredentials = new ArrayList<>();
        private final List<String> chatModelBaseUrls = new ArrayList<>();
        private final List<Optional<String>> chatModelAccountIds = new ArrayList<>();
        private final ChatModel apiKeyModel;
        private final ChatModel oauthModel;
        private int buildCount;

        private TrackingClient(ChatModel apiKeyModel, ChatModel oauthModel, OpenAiOAuthService oauthService) {
            super(apiKeyProperties(), new AgentProperties(), oauthService);
            this.apiKeyModel = apiKeyModel;
            this.oauthModel = oauthModel;
        }

        private static OpenAiProperties apiKeyProperties() {
            OpenAiProperties openAiProperties = new OpenAiProperties();
            openAiProperties.setApiKey("api-key-123");
            return openAiProperties;
        }

        @Override
        protected ChatModel buildChatModel(String modelName, String credential, String baseUrl, Optional<String> accountId) {
            chatModelNames.add(modelName);
            chatModelCredentials.add(credential);
            chatModelBaseUrls.add(baseUrl);
            chatModelAccountIds.add(accountId);
            return buildCount++ == 0 ? apiKeyModel : oauthModel;
        }

        private List<String> chatModelNames() {
            return chatModelNames;
        }

        private List<String> chatModelCredentials() {
            return chatModelCredentials;
        }

        private List<String> chatModelBaseUrls() {
            return chatModelBaseUrls;
        }

        private List<Optional<String>> chatModelAccountIds() {
            return chatModelAccountIds;
        }
    }
}

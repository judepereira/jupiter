package com.judepereira.jupiter.agent.llm.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.catalog.ThinkingLevel;
import com.judepereira.jupiter.agent.config.AgentProperties;
import com.judepereira.jupiter.agent.config.AnthropicProperties;
import com.judepereira.jupiter.agent.llm.AgentModelOptions;
import com.judepereira.jupiter.agent.llm.dto.Message;
import com.judepereira.jupiter.agent.llm.dto.ModelResponse;
import com.judepereira.jupiter.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter.agent.llm.dto.ToolSchema;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

class AnthropicAgentModelClientTests {
    @Test
    void requestUsesApiKeyHeadersAndMapsMessages() throws Exception {
        AtomicReference<HttpRequest> request = new AtomicReference<>();
        HttpClient http = mock(HttpClient.class);
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenAnswer(i -> {
            request.set(i.getArgument(0));
            return response(200,
                    "{\"id\":\"r\",\"model\":\"claude\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"usage\":{\"input_tokens\":2,\"output_tokens\":3},\"stop_reason\":\"end_turn\"}");
        });
        AnthropicProperties p = new AnthropicProperties();
        p.setApiKey("sk-ant-test");
        p.setBaseUrl("https://example.test/messages");
        AgentProperties a = new AgentProperties();
        a.setModel("claude-default");
        var client = new AnthropicAgentModelClient(p, a, new ObjectMapper(), http);
        var result = client.chat(List.of(new Message(Message.Role.SYSTEM, "sys", null, null, null),
                new Message(Message.Role.USER, "hello", null, null, null)), List.of());
        assertThat(request.get().headers().firstValue("x-api-key")).contains("sk-ant-test");
        assertThat(request.get().headers().firstValue("Authorization")).isEmpty();
        assertThat(request.get().headers().firstValue("anthropic-version")).contains("2023-06-01");
        assertThat(request.get().headers().firstValue("anthropic-beta")).isEmpty();
        assertThat(request.get().timeout()).contains(Duration.ofSeconds(120));
        var publisher = request.get().bodyPublisher().orElseThrow();
        var bytes = new ByteArrayOutputStream();
        publisher.subscribe(new Flow.Subscriber<>() {
            public void onSubscribe(Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }
            public void onNext(ByteBuffer b) {
                while (b.hasRemaining())
                    bytes.write(b.get());
            }
            public void onError(Throwable t) {
            }
            public void onComplete() {
            }
        });
        JsonNode body = new ObjectMapper().readTree(bytes.toString(StandardCharsets.UTF_8));
        assertThat(result.getAssistantText()).isEqualTo("ok");
        assertThat(body.path("system").asText()).isEqualTo("sys");
        assertThat(body.path("messages").get(0).path("content").get(0).path("text").asText()).isEqualTo("hello");
        assertThat(body.has("thinking")).isFalse();
    }

    @Test
    void configuredBetaIsSent() throws Exception {
        AnthropicProperties properties = propertiesWithKey();
        properties.setBeta("prompt-caching-2024-07-31");
        try (HttpClient http = mock(HttpClient.class)) {
            AtomicReference<HttpRequest> request = new AtomicReference<>();
            when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenAnswer(i -> {
                request.set(i.getArgument(0));
                return response(200, "{\"content\":[],\"usage\":{}}");
            });
            new AnthropicAgentModelClient(properties, new AgentProperties(), new ObjectMapper(), http)
                    .chat(List.of(new Message(Message.Role.USER, "x", null, null, null)), List.of());
            assertThat(request.get().headers().firstValue("anthropic-beta")).contains("prompt-caching-2024-07-31");
        }
    }

    @Test
    void missingApiKeyFailsBeforeSending() {
        assertMissingKeyDoesNotSend(null);
    }

    @Test
    void blankApiKeyFailsBeforeSending() {
        assertMissingKeyDoesNotSend("  \t");
    }

    private static void assertMissingKeyDoesNotSend(String key) {
        AnthropicProperties properties = new AnthropicProperties();
        if (key != null) {
            properties.setApiKey(key);
        }
        try (HttpClient http = mock(HttpClient.class)) {
            var client = new AnthropicAgentModelClient(properties, new AgentProperties(), new ObjectMapper(), http);
            assertThatThrownBy(
                    () -> client.chat(List.of(new Message(Message.Role.USER, "x", null, null, null)), List.of()))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Anthropic API key is required");
            if (key != null) {
                assertThatThrownBy(
                        () -> client.chat(List.of(new Message(Message.Role.USER, "x", null, null, null)), List.of()))
                        .hasMessageNotContaining(key);
            }
            verifyNoInteractions(http);
        }
    }

    @Test
    void reasoningUsesAdaptiveThinkingAndMappedEffortWithoutChangingMaxTokens() throws Exception {
        AtomicReference<HttpRequest> request = new AtomicReference<>();
        HttpClient http = mock(HttpClient.class);
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenAnswer(i -> {
            request.set(i.getArgument(0));
            return response(200, "{\"content\":[],\"usage\":{}}");
        });
        AnthropicProperties properties = new AnthropicProperties();
        properties.setApiKey("sk-ant-test");
        properties.setMaxOutputTokens(1234);
        AgentProperties agents = new AgentProperties();
        agents.setModel("claude");
        var client = new AnthropicAgentModelClient(properties, agents, new ObjectMapper(), http);

        for (ThinkingLevel level : ThinkingLevel.values()) {
            client.chat(List.of(new Message(Message.Role.USER, "x", null, null, null)), List.of(),
                    new AgentModelOptions("id", "claude", level, true, null));
            JsonNode body = requestBody(request.get());
            assertThat(body.path("thinking").path("type").asText()).isEqualTo("adaptive");
            assertThat(body.path("output_config").path("effort").asText())
                    .isEqualTo(level.name().toLowerCase(Locale.ROOT));
            assertThat(body.path("max_tokens").asInt()).isEqualTo(1234);
        }
    }

    @Test
    void reasoningFieldsAreAbsentWhenDisabled() throws Exception {
        AtomicReference<HttpRequest> request = new AtomicReference<>();
        HttpClient http = mock(HttpClient.class);
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenAnswer(i -> {
            request.set(i.getArgument(0));
            return response(200, "{\"content\":[],\"usage\":{}}");
        });
        var client = new AnthropicAgentModelClient(propertiesWithKey(), new AgentProperties(), new ObjectMapper(),
                http);
        client.chat(List.of(new Message(Message.Role.USER, "x", null, null, null)), List.of(),
                new AgentModelOptions("id", "claude", null, false, null));
        JsonNode body = requestBody(request.get());
        assertThat(body.has("thinking")).isFalse();
        assertThat(body.has("output_config")).isFalse();
    }

    @Test
    void requestDisablesParallelToolUseOnlyWhenToolsArePresent() throws Exception {
        AtomicReference<HttpRequest> request = new AtomicReference<>();
        HttpClient http = mock(HttpClient.class);
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenAnswer(i -> {
            request.set(i.getArgument(0));
            return response(200, "{\"content\":[],\"usage\":{}}");
        });
        AnthropicProperties properties = new AnthropicProperties();
        properties.setApiKey("sk-ant-test");
        AgentProperties agents = new AgentProperties();
        agents.setModel("claude");
        var client = new AnthropicAgentModelClient(properties, agents, new ObjectMapper(), http);
        ToolDefinition tool = ToolDefinition.builtIn("read", "Read", ToolSchema.object());

        client.chat(List.of(new Message(Message.Role.USER, "x", null, null, null)), List.of(tool));
        JsonNode withTools = requestBody(request.get());
        assertThat(withTools.path("tool_choice").path("type").asText()).isEqualTo("auto");
        assertThat(withTools.path("tool_choice").path("disable_parallel_tool_use").asBoolean()).isTrue();

        client.chat(List.of(new Message(Message.Role.USER, "x", null, null, null)), List.of());
        assertThat(requestBody(request.get()).has("tool_choice")).isFalse();
    }

    @Test
    void unexpectedMultipleToolCallsFailLoudly() throws Exception {
        HttpClient http = mock(HttpClient.class);
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response(200,
                "{\"content\":[{\"type\":\"tool_use\",\"id\":\"a\",\"name\":\"one\",\"input\":{}},{\"type\":\"tool_use\",\"id\":\"b\",\"name\":\"two\",\"input\":{}}]}"));
        var client = new AnthropicAgentModelClient(propertiesWithKey(), new AgentProperties(), new ObjectMapper(),
                http);

        assertThatThrownBy(() -> client.chat(List.of(new Message(Message.Role.USER, "x", null, null, null)), List.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("multiple tool calls");
    }

    private static AnthropicProperties propertiesWithKey() {
        AnthropicProperties properties = new AnthropicProperties();
        properties.setApiKey("sk-ant-test");
        return properties;
    }

    private static JsonNode requestBody(HttpRequest request) throws Exception {
        var publisher = request.bodyPublisher().orElseThrow();
        var bytes = new ByteArrayOutputStream();
        publisher.subscribe(new Flow.Subscriber<>() {
            public void onSubscribe(Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }
            public void onNext(ByteBuffer b) {
                while (b.hasRemaining())
                    bytes.write(b.get());
            }
            public void onError(Throwable t) {
            }
            public void onComplete() {
            }
        });
        return new ObjectMapper().readTree(bytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void interruptedRequestRestoresInterruptAndFailsClearly() throws Exception {
        HttpClient http = mock(HttpClient.class);
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenThrow(new InterruptedException());
        AnthropicProperties properties = new AnthropicProperties();
        properties.setApiKey("sk-ant-test");
        properties.setRequestTimeout(Duration.ofSeconds(7));
        var client = new AnthropicAgentModelClient(properties, new AgentProperties(), new ObjectMapper(), http);

        Thread.interrupted();
        try {
            assertThatThrownBy(
                    () -> client.chat(List.of(new Message(Message.Role.USER, "x", null, null, null)), List.of()))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Anthropic request cancelled");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void streamingPreservesIndexedThinkingTextToolAndRedactedBlocks() throws Exception {
        HttpClient http = mock(HttpClient.class);
        String s = "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"i\",\"model\":\"m\",\"usage\":{\"input_tokens\":4}}}\n"
                + "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\"}}\n"
                + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"plan \"}}\n"
                + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"first\"}}\n"
                + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig\"}}\n"
                + "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n"
                + "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\"}}\n"
                + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"hello\"}}\n"
                + "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":1}\n"
                + "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"tool_use\",\"id\":\"t\",\"name\":\"read\"}}\n"
                + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"x\\\"\"}}\n"
                + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\":1\"}}\n"
                + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"}\"}}\n"
                + "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":2}\n"
                + "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":3,\"content_block\":{\"type\":\"redacted_thinking\",\"data\":\"opaque\"}}\n"
                + "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":3}\n"
                + "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":6}}\n"
                + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n";
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response(200, s.lines()));
        AnthropicProperties p = new AnthropicProperties();
        p.setApiKey("sk-ant-test");
        p.setBaseUrl("https://example.test");
        var client = new AnthropicAgentModelClient(p, new AgentProperties(), new ObjectMapper(), http);
        List<String> callback = new ArrayList<>();
        var result = client.chatStreaming(List.of(new Message(Message.Role.USER, "x", null, null, null)), List.of(),
                callback::add);

        assertThat(result.getAssistantText()).isEqualTo("hello");
        assertThat(callback).containsExactly("hello");
        assertThat(result.getToolCall().getArguments()).containsEntry("x", 1);
        assertThat(result.getProviderContent()).extracting(JsonNode::toString).containsExactly(
                "{\"type\":\"thinking\",\"thinking\":\"plan first\",\"signature\":\"sig\"}",
                "{\"type\":\"text\",\"text\":\"hello\"}",
                "{\"type\":\"tool_use\",\"id\":\"t\",\"name\":\"read\",\"input\":{\"x\":1}}",
                "{\"type\":\"redacted_thinking\",\"data\":\"opaque\"}");
        assertThat(result.getMetadata().totalTokenCount()).isEqualTo(10);
    }

    @Test
    void streamingMalformedToolJsonFailsAtBlockStop() {
        String stream = """
                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t","name":"read"}}
                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{bad"}}
                event: content_block_stop
                data: {"type":"content_block_stop","index":0}
                event: message_stop
                data: {"type":"message_stop"}
                """;
        assertThatThrownBy(() -> streaming(stream)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Malformed Anthropic tool input");
    }

    @Test
    void streamingMultipleToolStartsFailLoudly() {
        String stream = """
                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"a","name":"one"}}
                event: content_block_start
                data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"b","name":"two"}}
                """;
        assertThatThrownBy(() -> streaming(stream)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("multiple tool calls");
    }

    private ModelResponse streaming(String stream) {
        HttpClient http = mock(HttpClient.class);
        try {
            when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response(200, stream.lines()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return new AnthropicAgentModelClient(propertiesWithKey(), new AgentProperties(), new ObjectMapper(), http)
                .chatStreaming(List.of(new Message(Message.Role.USER, "x", null, null, null)), List.of(), x -> {
                });
    }

    private static HttpResponse<String> response(int status, String body) {
        return new HttpResponse<>() {
            public int statusCode() {
                return status;
            }
            public String body() {
                return body;
            }
            public HttpRequest request() {
                return null;
            }
            public Optional<HttpResponse<String>> previousResponse() {
                return Optional.empty();
            }
            public HttpHeaders headers() {
                return HttpHeaders.of(Map.of(), (x, y) -> true);
            }
            public URI uri() {
                return URI.create("https://example.test");
            }
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
            public Optional<SSLSession> sslSession() {
                return Optional.empty();
            }
        };
    }
    private static HttpResponse<Stream<String>> response(int status, Stream<String> body) {
        return new HttpResponse<>() {
            public int statusCode() {
                return status;
            }
            public Stream<String> body() {
                return body;
            }
            public HttpRequest request() {
                return null;
            }
            public Optional<HttpResponse<Stream<String>>> previousResponse() {
                return Optional.empty();
            }
            public HttpHeaders headers() {
                return HttpHeaders.of(Map.of(), (x, y) -> true);
            }
            public URI uri() {
                return URI.create("https://example.test");
            }
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
            public Optional<SSLSession> sslSession() {
                return Optional.empty();
            }
        };
    }
}

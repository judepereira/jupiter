package com.judepereira.jupiter.agent.llm.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.config.AgentProperties;
import com.judepereira.jupiter.agent.config.AnthropicProperties;
import com.judepereira.jupiter.agent.llm.dto.Message;
import com.judepereira.jupiter.anthropic.oauth.AnthropicOAuthService;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.net.http.*;
import javax.net.ssl.SSLSession;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AnthropicAgentModelClientTests {
    @Test void requestUsesOauthHeadersAndMapsMessages() throws Exception {
        AtomicReference<HttpRequest> request = new AtomicReference<>();
        HttpClient http = mock(HttpClient.class);
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenAnswer(i -> { request.set(i.getArgument(0)); return response(200, "{\"id\":\"r\",\"model\":\"claude\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"usage\":{\"input_tokens\":2,\"output_tokens\":3},\"stop_reason\":\"end_turn\"}"); });
        AnthropicOAuthService oauth = mock(AnthropicOAuthService.class); when(oauth.currentAccessToken()).thenReturn(Optional.of("oauth-token"));
        AnthropicProperties p = new AnthropicProperties(); p.setBaseUrl("https://example.test/messages");
        AgentProperties a = new AgentProperties(); a.setModel("claude-default");
        var client = new AnthropicAgentModelClient(p, a, oauth, new ObjectMapper(), http);
        var result = client.chat(List.of(new Message(Message.Role.SYSTEM, "sys", null, null), new Message(Message.Role.USER, "hello", null, null)), List.of());
        assertThat(request.get().headers().firstValue("Authorization")).contains("Bearer oauth-token");
        assertThat(request.get().headers().firstValue("anthropic-version")).contains("2023-06-01");
        assertThat(request.get().headers().firstValue("anthropic-beta")).contains("oauth-2025-04-20");
        assertThat(request.get().headers().firstValue("x-api-key")).isEmpty();
        assertThat(request.get().timeout()).contains(java.time.Duration.ofSeconds(120));
        var publisher = request.get().bodyPublisher().orElseThrow(); var bytes = new ByteArrayOutputStream(); publisher.subscribe(new Flow.Subscriber<>() { public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); } public void onNext(java.nio.ByteBuffer b) { while (b.hasRemaining()) bytes.write(b.get()); } public void onError(Throwable t) {} public void onComplete() {} }); JsonNode body = new ObjectMapper().readTree(bytes.toString(StandardCharsets.UTF_8));
        assertThat(result.getAssistantText()).isEqualTo("ok");
        assertThat(body.path("system").asText()).isEqualTo("sys");
        assertThat(body.path("messages").get(0).path("content").get(0).path("text").asText()).isEqualTo("hello");
    }

    @Test
    void interruptedRequestRestoresInterruptAndFailsClearly() throws Exception {
        HttpClient http = mock(HttpClient.class);
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenThrow(new InterruptedException());
        AnthropicOAuthService oauth = mock(AnthropicOAuthService.class);
        when(oauth.currentAccessToken()).thenReturn(Optional.of("token"));
        AnthropicProperties properties = new AnthropicProperties();
        properties.setRequestTimeout(java.time.Duration.ofSeconds(7));
        var client = new AnthropicAgentModelClient(properties, new AgentProperties(), oauth, new ObjectMapper(), http);

        Thread.interrupted();
        try {
            assertThatThrownBy(() -> client.chat(List.of(new Message(Message.Role.USER, "x", null, null)), List.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Anthropic request cancelled");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test void streamingAccumulatesTextToolAndUsage() throws Exception {
        HttpClient http = mock(HttpClient.class);
        String s = "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"i\",\"model\":\"m\",\"usage\":{\"input_tokens\":4}}}\n" +
                "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"content_block\":{\"type\":\"tool_use\",\"id\":\"t\",\"name\":\"read\"}}\n" +
                "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"hi\"}}\n" +
                "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"x\\\":1}\"}}\n" +
                "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":6}}\n" +
                "event: message_stop\ndata: {\"type\":\"message_stop\"}\n";
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response(200, s.lines()));
        AnthropicOAuthService oauth = mock(AnthropicOAuthService.class); when(oauth.currentAccessToken()).thenReturn(Optional.of("t"));
        AnthropicProperties p = new AnthropicProperties(); p.setBaseUrl("https://example.test");
        var client = new AnthropicAgentModelClient(p, new AgentProperties(), oauth, new ObjectMapper(), http);
        var result = client.chatStreaming(List.of(new Message(Message.Role.USER, "x", null, null)), List.of(), x -> {});
        assertThat(result.getAssistantText()).isEqualTo("hi"); assertThat(result.getToolCall().getToolName()).isEqualTo("read");
        assertThat(result.getToolCall().getArguments()).containsEntry("x", 1); assertThat(result.getMetadata().totalTokenCount()).isEqualTo(10);
    }

    private static HttpResponse<String> response(int status, String body) { return new HttpResponse<>() { public int statusCode(){return status;} public String body(){return body;} public HttpRequest request(){return null;} public Optional<HttpResponse<String>> previousResponse(){return Optional.empty();} public HttpHeaders headers(){return HttpHeaders.of(java.util.Map.of(),(x,y)->true);} public URI uri(){return URI.create("https://example.test");} public HttpClient.Version version(){return HttpClient.Version.HTTP_1_1;} public Optional<SSLSession> sslSession(){return Optional.empty();} }; }
    private static HttpResponse<java.util.stream.Stream<String>> response(int status, java.util.stream.Stream<String> body) { return new HttpResponse<>() { public int statusCode(){return status;} public java.util.stream.Stream<String> body(){return body;} public HttpRequest request(){return null;} public Optional<HttpResponse<java.util.stream.Stream<String>>> previousResponse(){return Optional.empty();} public HttpHeaders headers(){return HttpHeaders.of(java.util.Map.of(),(x,y)->true);} public URI uri(){return URI.create("https://example.test");} public HttpClient.Version version(){return HttpClient.Version.HTTP_1_1;} public Optional<SSLSession> sslSession(){return Optional.empty();} }; }
}

package com.judepereira.jupiter.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

final class McpHttpTestClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final String url;
    private final String token;
    private String sessionId;
    private int requestId;

    McpHttpTestClient(String url, String token) {
        this.url = url.endsWith("/mcp") ? url : url + "/mcp";
        this.token = token;
    }

    JsonNode initialize(String clientName) throws Exception {
        JsonNode response = send("initialize", Map.of("protocolVersion", "2025-03-26", "capabilities", Map.of(),
                "clientInfo", Map.of("name", clientName, "version", "1")), true);
        send("notifications/initialized", Map.of(), false);
        return response;
    }

    JsonNode call(String name, Map<String, ?> arguments) throws Exception {
        return send("tools/call", Map.of("name", name, "arguments", arguments), true);
    }

    JsonNode tools() throws Exception {
        return send("tools/list", Map.of(), true);
    }

    int httpStatus(String method) throws Exception {
        return post(JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 99, "method", method, "params", Map.of())))
                .statusCode();
    }

    private JsonNode send(String method, Map<String, ?> params, boolean request) throws Exception {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        if (request) {
            message.put("id", ++requestId);
        }
        message.put("method", method);
        if (!params.isEmpty()) {
            message.put("params", params);
        }
        HttpResponse<String> response = post(JSON.writeValueAsString(message));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new AssertionError("MCP HTTP " + response.statusCode() + ": " + response.body());
        }
        sessionId = response.headers().firstValue("Mcp-Session-Id").orElse(sessionId);
        String body = response.body();
        String data = body.lines().filter(line -> line.startsWith("data:")).map(line -> line.substring(5).trim())
                .findFirst().orElse(body);
        return data.isBlank() ? JSON.createObjectNode() : JSON.readTree(data);
    }

    private HttpResponse<String> post(String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-03-26");
        if (sessionId != null) {
            request.header("Mcp-Session-Id", sessionId);
        }
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}

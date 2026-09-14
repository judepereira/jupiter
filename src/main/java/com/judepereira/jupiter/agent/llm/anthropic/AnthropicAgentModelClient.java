package com.judepereira.jupiter.agent.llm.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.judepereira.jupiter.agent.catalog.ThinkingLevel;
import com.judepereira.jupiter.agent.config.AgentProperties;
import com.judepereira.jupiter.agent.config.AnthropicProperties;
import com.judepereira.jupiter.agent.llm.AgentModelClient;
import com.judepereira.jupiter.agent.llm.AgentModelOptions;
import com.judepereira.jupiter.agent.llm.dto.Message;
import com.judepereira.jupiter.agent.llm.dto.ModelResponse;
import com.judepereira.jupiter.agent.llm.dto.ModelResponseMetadata;
import com.judepereira.jupiter.agent.llm.dto.ToolCall;
import com.judepereira.jupiter.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter.agent.llm.dto.ToolParameter;
import com.judepereira.jupiter.agent.llm.dto.ToolSchema;
import com.judepereira.jupiter.anthropic.oauth.AnthropicOAuthService;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;

@Component
public class AnthropicAgentModelClient implements AgentModelClient {
    private final AnthropicProperties properties;
    private final AgentProperties agentProperties;
    private final AnthropicOAuthService oauth;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public AnthropicAgentModelClient(AnthropicProperties properties, AgentProperties agentProperties,
                                     AnthropicOAuthService oauth, ObjectMapper mapper, HttpClient httpClient) {
        this.properties = properties;
        this.agentProperties = agentProperties;
        this.oauth = oauth;
        this.mapper = mapper;
        this.httpClient = httpClient;
    }

    @Override
    public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools) {
        return chat(messages, tools, null);
    }

    @Override
    public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools, AgentModelOptions options) {
        return parse(send(body(messages, tools, options, false)));
    }

    @Override
    public ModelResponse chatStreaming(List<Message> messages, List<ToolDefinition> tools,
                                       Consumer<String> onText) {
        return chatStreaming(messages, tools, null, onText);
    }

    @Override
    public ModelResponse chatStreaming(List<Message> messages, List<ToolDefinition> tools,
                                       AgentModelOptions options, Consumer<String> onText) {
        HttpResponse<Stream<String>> response;
        try {
            response = httpClient.send(request(body(messages, tools, options, true), token()),
                    HttpResponse.BodyHandlers.ofLines());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Anthropic streaming request cancelled", e);
        } catch (IOException e) {
            throw new IllegalStateException("Anthropic streaming request failed", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Anthropic streaming request failed with status " + response.statusCode());
        }

        try (Stream<String> lines = response.body()) {
            String event = null;
            StringBuilder text = new StringBuilder();
            StringBuilder toolJson = new StringBuilder();
            ToolState tool = new ToolState();
            Usage usage = new Usage();
            String id = null;
            String model = null;
            String stopReason = null;

            for (String line : (Iterable<String>) lines::iterator) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("Anthropic streaming request cancelled");
                }
                if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                    continue;
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                JsonNode node = read(line.substring(5).trim(), "malformed Anthropic SSE event");
                String type = node.path("type").asText(event == null ? "" : event);
                switch (type) {
                    case "message_start" -> {
                        JsonNode message = node.path("message");
                        id = value(message, "id");
                        model = value(message, "model");
                        usage.read(message.path("usage"));
                    }
                    case "content_block_start" -> {
                        JsonNode block = node.path("content_block");
                        if ("tool_use".equals(value(block, "type"))) {
                            tool.id = value(block, "id");
                            tool.name = value(block, "name");
                        }
                    }
                    case "content_block_delta" -> {
                        JsonNode delta = node.path("delta");
                        if ("text_delta".equals(value(delta, "type"))) {
                            String part = value(delta, "text");
                            text.append(part);
                            if (onText != null) {
                                onText.accept(part);
                            }
                        } else if ("input_json_delta".equals(value(delta, "type"))) {
                            toolJson.append(value(delta, "partial_json"));
                        }
                    }
                    case "message_delta" -> {
                        stopReason = value(node.path("delta"), "stop_reason");
                        usage.read(node.path("usage"));
                    }
                    case "message_stop" ->
                            { return response(text.toString(), tool, toolJson.toString(), usage, id, model, stopReason); }
                    case "ping", "content_block_stop" -> { }
                    default -> throw new IllegalStateException("Unknown Anthropic SSE event: " + type);
                }
                event = null;
            }
            throw new IllegalStateException("Anthropic stream ended before message_stop");
        }
    }

    private JsonNode send(JsonNode body) {
        try {
            HttpResponse<String> response = httpClient.send(request(body, token()),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Anthropic request failed with status " + response.statusCode());
            }
            return read(response.body(), "malformed Anthropic response");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Anthropic request cancelled", e);
        } catch (IOException e) {
            throw new IllegalStateException("Anthropic request failed", e);
        }
    }

    private String token() {
        return oauth.currentAccessToken().filter(token -> !token.isBlank())
                .orElseThrow(() -> new IllegalStateException("Anthropic OAuth access token is required"));
    }

    private HttpRequest request(JsonNode body, String token) {
        return HttpRequest.newBuilder(URI.create(properties.getBaseUrl()))
                .header("Authorization", "Bearer " + token)
                .header("anthropic-version", properties.getVersion())
                .header("anthropic-beta", properties.getBeta())
                .header("Content-Type", "application/json")
                .timeout(properties.getRequestTimeout())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
    }

    private ObjectNode body(List<Message> messages, List<ToolDefinition> tools,
                            AgentModelOptions options, boolean stream) {
        ObjectNode body = mapper.createObjectNode();
        String model = options == null ? null : options.apiModelId();
        if (model == null || model.isBlank()) {
            model = agentProperties.getModel();
        }
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("agent.model is required for Anthropic");
        }
        body.put("model", model);
        body.put("max_tokens", maxTokens(options));
        body.put("stream", stream);

        List<JsonNode> converted = new ArrayList<>();
        for (Message message : messages) {
            if (message.getRole() == Message.Role.SYSTEM) {
                body.put("system", message.getContent());
            } else {
                converted.add(message(message));
            }
        }
        body.set("messages", mapper.valueToTree(converted));

        var toolNodes = body.putArray("tools");
        for (ToolDefinition tool : tools) {
            ObjectNode node = toolNodes.addObject();
            node.put("name", tool.getName());
            if (tool.getDescription() != null) {
                node.put("description", tool.getDescription());
            }
            node.set("input_schema", schema(tool.getSchema()));
        }
        if (options != null && options.supportsReasoning() && options.thinkingLevel() != null) {
            body.putObject("thinking").put("type", "enabled")
                    .put("budget_tokens", budget(options.thinkingLevel()));
        }
        return body;
    }

    private int maxTokens(AgentModelOptions options) {
        int configured = properties.getMaxOutputTokens();
        if (options != null && options.supportsReasoning() && options.thinkingLevel() != null) {
            return Math.max(configured, budget(options.thinkingLevel()) + 1024);
        }
        return configured;
    }

    private int budget(ThinkingLevel level) {
        return switch (level) {
            case LOW -> 1024;
            case MEDIUM -> 4096;
            case HIGH -> 8192;
        };
    }

    private JsonNode message(Message message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", message.getRole() == Message.Role.ASSISTANT ? "assistant" : "user");
        var content = node.putArray("content");
        if (message.getRole() == Message.Role.TOOL) {
            ObjectNode result = content.addObject();
            result.put("type", "tool_result");
            result.put("tool_use_id", message.getToolCallId());
            result.put("content", Objects.requireNonNullElse(message.getContent(), ""));
            return node;
        }
        if (message.getContent() != null && !message.getContent().isBlank()) {
            content.addObject().put("type", "text").put("text", message.getContent());
        }
        if (message.getToolCalls() != null) {
            for (ToolCall call : message.getToolCalls()) {
                ObjectNode tool = content.addObject();
                tool.put("type", "tool_use");
                tool.put("id", call.getToolCallId());
                tool.put("name", call.getToolName());
                tool.set("input", mapper.valueToTree(call.getArguments()));
            }
        }
        return node;
    }

    private JsonNode schema(ToolSchema schema) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "object");
        if (schema.description() != null) node.put("description", schema.description());
        var properties = node.putObject("properties");
        for (ToolParameter parameter : schema.properties()) properties.set(parameter.name(), parameter(parameter));
        node.set("required", mapper.valueToTree(schema.required()));
        if (schema.additionalProperties() != null) node.put("additionalProperties", schema.additionalProperties());
        return node;
    }

    private JsonNode parameter(ToolParameter parameter) {
        ObjectNode node = mapper.createObjectNode();
        if (parameter.description() != null) node.put("description", parameter.description());
        node.put("type", switch (parameter) {
            case ToolParameter.StringParameter ignored -> "string";
            case ToolParameter.IntegerParameter ignored -> "integer";
            case ToolParameter.NumberParameter ignored -> "number";
            case ToolParameter.BooleanParameter ignored -> "boolean";
            case ToolParameter.EnumParameter ignored -> "string";
            case ToolParameter.ObjectParameter ignored -> "object";
            case ToolParameter.ArrayParameter ignored -> "array";
        });
        if (parameter instanceof ToolParameter.EnumParameter enumParameter) {
            node.set("enum", mapper.valueToTree(enumParameter.values()));
        } else if (parameter instanceof ToolParameter.ObjectParameter objectParameter) {
            return schema(objectParameter.schema());
        } else if (parameter instanceof ToolParameter.ArrayParameter arrayParameter) {
            node.set("items", parameter(arrayParameter.items()));
        }
        return node;
    }

    private ModelResponse parse(JsonNode node) {
        StringBuilder text = new StringBuilder();
        ToolState tool = new ToolState();
        for (JsonNode block : node.path("content")) {
            if ("text".equals(value(block, "type"))) text.append(value(block, "text"));
            if ("tool_use".equals(value(block, "type"))) {
                tool.id = value(block, "id");
                tool.name = value(block, "name");
                tool.args = block.path("input");
            }
        }
        Usage usage = new Usage();
        usage.read(node.path("usage"));
        return response(text.toString(), tool, null, usage, value(node, "id"), value(node, "model"),
                value(node, "stop_reason"));
    }

    private ModelResponse response(String text, ToolState tool, String rawArguments, Usage usage,
                                   String id, String model, String stopReason) {
        Map<String, Object> arguments = tool.id == null ? null
                : tool.args != null && !tool.args.isMissingNode() ? mapper.convertValue(tool.args, Map.class)
                : parseObject(rawArguments);
        ToolCall call = tool.id == null ? null : new ToolCall(tool.id, tool.name, arguments);
        Integer total = usage.in == null || usage.out == null ? null : usage.in + usage.out;
        return new ModelResponse(text.isEmpty() ? null : text, call,
                new ModelResponseMetadata(usage.in, usage.out, total, null, null, null, id, model,
                        stopReason, Map.of()));
    }

    private Map<String, Object> parseObject(String input) {
        try {
            return input == null || input.isBlank() ? Map.of() : mapper.readValue(input, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("Malformed Anthropic tool input", e);
        }
    }

    private JsonNode read(String input, String message) {
        try {
            return mapper.readTree(input);
        } catch (Exception e) {
            throw new IllegalStateException(message, e);
        }
    }

    private String value(JsonNode node, String field) {
        return node == null || node.path(field).isMissingNode() || node.path(field).isNull()
                ? null : node.path(field).asText();
    }

    private static final class ToolState {
        private String id;
        private String name;
        private JsonNode args;
    }

    private static final class Usage {
        private Integer in;
        private Integer out;

        private void read(JsonNode node) {
            if (node.has("input_tokens")) in = node.get("input_tokens").asInt();
            if (node.has("output_tokens")) out = node.get("output_tokens").asInt();
        }
    }
}

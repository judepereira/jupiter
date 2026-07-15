package com.judepereira.jupiter2.agent.llm.openai;

import com.judepereira.jupiter2.agent.config.OpenAiProperties;
import com.judepereira.jupiter2.agent.config.AgentProperties;
import com.judepereira.jupiter2.agent.llm.AgentModelClient;
import com.judepereira.jupiter2.agent.llm.dto.Message;
import com.judepereira.jupiter2.agent.llm.dto.ModelResponse;
import com.judepereira.jupiter2.agent.llm.dto.ToolCall;
import com.judepereira.jupiter2.agent.llm.dto.ToolDefinition;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
public class OpenAiAgentModelClient implements AgentModelClient {

    private final OpenAiProperties openAiProperties;
    private final AgentProperties agentProperties;
    private volatile ChatModel model;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public ModelResponse chat(List<Message> conversation, List<ToolDefinition> tools) {
        Map<String, String> toolNamesById = new HashMap<>();
        List<ChatMessage> msgs = new ArrayList<>(conversation.size());

        for (int i = 0; i < conversation.size(); i++) {
            Message m = conversation.get(i);
            switch (m.getRole()) {
                case SYSTEM -> msgs.add(SystemMessage.systemMessage(m.getContent()));
                case USER -> msgs.add(UserMessage.userMessage(m.getContent()));
                case ASSISTANT -> {
                    var toolCalls = m.getToolCalls();
                    if (toolCalls != null && !toolCalls.isEmpty()) {
                        List<ToolExecutionRequest> reqs = new ArrayList<>(toolCalls.size());
                        for (int j = 0; j < toolCalls.size(); j++) {
                            ToolCall tc = toolCalls.get(j);
                            String toolCallId = normalizeToolCallId(tc.getToolCallId(), i, j);
                            String toolName = requireToolName(tc.getToolName());
                            toolNamesById.put(toolCallId, toolName);
                            reqs.add(ToolExecutionRequest.builder()
                                    .id(toolCallId)
                                    .name(toolName)
                                    .arguments(toJsonArguments(tc.getArguments()))
                                    .build());
                        }
                        msgs.add(AiMessage.aiMessage(m.getContent() == null ? "" : m.getContent(), reqs));
                    } else {
                        msgs.add(AiMessage.aiMessage(m.getContent()));
                    }
                }
                case TOOL -> {
                    String toolCallId = requireToolCallId(m.getToolCallId());
                    String toolName = toolNamesById.get(toolCallId);
                    if (toolName == null || toolName.isBlank()) {
                        throw new IllegalStateException("Missing tool name for tool call id: " + toolCallId);
                    }
                    msgs.add(ToolExecutionResultMessage.from(toolCallId, toolName, m.getContent()));
                }
            }
        }

        ensureModelInitialized();

        // build ChatRequest including tool specifications
        ChatRequest.Builder reqBuilder = ChatRequest.builder().messages(msgs);
        if (tools != null && !tools.isEmpty()) {
            var specList = tools.stream().map(t -> {
                try {
                    // build a JSON representation for the ToolSpecification and parse via langchain4j
                    // ToolDefinition.schema may be a per-field map (e.g., {"path":{...}}).
                    // If it looks like a full JSON Schema object (contains key "type"), use as-is.
                    Map<String, Object> provided = t.getSchema() == null ? Map.of() : t.getSchema();
                    Object parametersObj;
                    if (provided.containsKey("type")) {
                        // already a JSON Schema object
                        parametersObj = provided;
                    } else {
                        // wrap into an object schema with properties
                        parametersObj = Map.of("type", "object", "properties", provided);
                    }
                    String paramsJson = objectMapper.writeValueAsString(parametersObj);
                    String fullJson = "{\"name\":\"" + escapeJson(t.getName()) + "\",\"description\":\"" + escapeJson(t.getDescription() == null ? "" : t.getDescription()) + "\",\"parameters\":" + paramsJson + "}";
                    return ToolSpecification.fromJson(fullJson);
                } catch (Exception e) {
                    // fallback: create a minimal specification
                    return ToolSpecification.builder().name(t.getName()).description(t.getDescription()).build();
                }
            }).collect(Collectors.toList());
            reqBuilder = reqBuilder.toolSpecifications(specList);
        }

        var response = model.chat(reqBuilder.build());

        String assistantText = response.aiMessage() != null ? response.aiMessage().text() : null;

        ToolCall toolCall = null;
        if (response.aiMessage() != null && response.aiMessage().hasToolExecutionRequests()) {
            var reqs = response.aiMessage().toolExecutionRequests();
            if (reqs != null && !reqs.isEmpty()) {
                ToolExecutionRequest first = reqs.get(0);
                Map<String, Object> argsMap = null;
                try {
                    String argsJson = first.arguments();
                    if (argsJson != null && !argsJson.isBlank()) {
                        argsMap = objectMapper.readValue(argsJson, new TypeReference<Map<String, Object>>() {});
                    }
                } catch (Exception e) {
                    // ignore parse errors, leave argsMap null
                }
                if (argsMap == null) argsMap = Map.of();
                toolCall = new ToolCall(first.id(), first.name(), argsMap);
            }
        }

        return new ModelResponse(assistantText, toolCall);
    }

    @Override
    public ModelResponse chatStreaming(List<Message> conversation, List<ToolDefinition> tools, Consumer<String> onDelta) {
        // Implement true OpenAI streaming even when tools are provided.
        // We'll send a Chat Completions request with stream=true and include
        // OpenAI-compatible tools when available. We parse SSE frames and
        // accumulate both text deltas and tool-call fragments.
        String apiKey = openAiProperties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OpenAI API key (openai.api-key) is required to call OpenAI provider");
        }

        String modelName = agentProperties.getModel();
        if (modelName == null || modelName.isBlank()) modelName = "gpt-4o-mini";

        // build messages array
        try {
            var url = new java.net.URL("https://api.openai.com/v1/chat/completions");
            var conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "text/event-stream, application/json");

            // build JSON body
            var root = new java.util.HashMap<String, Object>();
            root.put("model", modelName);
            root.put("stream", true);
            root.put("messages", buildRawMessages(conversation));

            // include tools as OpenAI-compatible "tools" array when present
            if (tools != null && !tools.isEmpty()) {
                java.util.List<java.util.Map<String, Object>> toolsList = new java.util.ArrayList<>();
                for (ToolDefinition t : tools) {
                    try {
                        Map<String, Object> provided = t.getSchema() == null ? Map.of() : t.getSchema();
                        Object parametersObj;
                        if (provided.containsKey("type")) {
                            parametersObj = provided;
                        } else {
                            parametersObj = Map.of("type", "object", "properties", provided);
                        }
                        java.util.Map<String, Object> functionObj = new java.util.HashMap<>();
                        functionObj.put("name", t.getName());
                        functionObj.put("description", t.getDescription() == null ? "" : t.getDescription());
                        functionObj.put("parameters", parametersObj);

                        java.util.Map<String, Object> toolObj = new java.util.HashMap<>();
                        toolObj.put("type", "function");
                        toolObj.put("function", functionObj);
                        toolsList.add(toolObj);
                    } catch (Exception e) {
                        java.util.Map<String, Object> functionObj = new java.util.HashMap<>();
                        functionObj.put("name", t.getName());
                        functionObj.put("description", t.getDescription() == null ? "" : t.getDescription());
                        java.util.Map<String, Object> toolObj = new java.util.HashMap<>();
                        toolObj.put("type", "function");
                        toolObj.put("function", functionObj);
                        toolsList.add(toolObj);
                    }
                }
                root.put("tools", toolsList);
            }

            String body = objectMapper.writeValueAsString(root);
            try (var os = conn.getOutputStream()) {
                os.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            StringBuilder finalText = new StringBuilder();
            java.util.Map<String, ToolPartial> partials = new java.util.LinkedHashMap<>();

            try (var is = conn.getInputStream();
                 var br = new java.io.BufferedReader(new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    // SSE frames are prefixed with "data: "
                    if (line.startsWith("data:")) {
                        String data = line.substring(5).trim();
                        if (data.equals("[DONE]")) {
                            break;
                        }
                        try {
                            var node = objectMapper.readTree(data);
                            var choices = node.get("choices");
                            if (choices != null && choices.isArray() && choices.size() > 0) {
                                var delta = choices.get(0).get("delta");
                                if (delta != null) {
                                    var content = delta.get("content");
                                    if (content != null && !content.isNull()) {
                                        String txt = content.asText();
                                        finalText.append(txt);
                                        try { onDelta.accept(txt); } catch (Exception ignored) {}
                                    }

    // support tool call fragments (modern and legacy shapes)
                                    var toolCalls = delta.get("tool_calls");
                                    if (toolCalls != null && toolCalls.isArray()) {
                                        for (int i = 0; i < toolCalls.size(); i++) {
                                            var tc = toolCalls.get(i);
                                            String key = stableToolKey(tc, i);
                                            ToolPartial p = partials.computeIfAbsent(key, k -> new ToolPartial());
                                            String toolCallId = extractToolCallIdFromToolCallNode(tc);
                                            if (toolCallId != null && !toolCallId.isBlank()) p.toolCallId = toolCallId;
                                            if (p.toolCallId == null || p.toolCallId.isBlank()) p.toolCallId = key;
                                            String name = extractNameFromToolCallNode(tc);
                                            if (name != null && !name.isBlank()) p.name = name;
                                            String argsText = extractArgumentsTextFromToolCallNode(tc);
                                            if (argsText != null && !argsText.isBlank()) p.arguments.append(argsText);
                                        }
                                    }

                                    var func = delta.get("function_call");
                                    if (func != null && !func.isNull()) {
                                        String id = "__fn__";
                                        ToolPartial p = partials.computeIfAbsent(id, k -> new ToolPartial());
                                        if (func.has("name")) p.name = func.get("name").asText();
                                        if (func.has("arguments")) {
                                            var argNode = func.get("arguments");
                                            if (argNode != null && !argNode.isNull()) {
                                                if (argNode.isTextual()) p.arguments.append(argNode.asText());
                                                else p.arguments.append(argNode.toString());
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {
                            // ignore partial parse errors but allow caller to continue
                        }
                    }
                }
            } catch (java.io.IOException ioe) {
                // try to read error stream for diagnostics
                try (var es = conn.getErrorStream()) {
                    if (es != null) {
                        String err = new String(es.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        throw new RuntimeException("OpenAI streaming error: " + err, ioe);
                    }
                } catch (Exception ignored) {}
                throw ioe;
            }

            if (!partials.isEmpty()) {
                // find first partial that has a non-blank name
                ToolPartial chosen = null;
                for (ToolPartial p : partials.values()) {
                    if (p.name != null && !p.name.isBlank()) { chosen = p; break; }
                }
                if (chosen != null) {
                    Map<String, Object> argsMap = Map.of();
                    if (chosen.arguments.length() > 0) {
                        try {
                            argsMap = objectMapper.readValue(chosen.arguments.toString(), new TypeReference<Map<String, Object>>() {});
                        } catch (Exception e) {
                            // leave argsMap empty on parse failure
                        }
                    }
                    return new ModelResponse(null, new ToolCall(chosen.toolCallId, chosen.name, argsMap));
                }
                // no partial contained a name, fall through to return finalText or null
            }

            return new ModelResponse(finalText.length() == 0 ? null : finalText.toString(), null);
        } catch (Exception e) {
            // On any streaming failure fall back to non-streaming chat to remain functional
            ModelResponse r = chat(conversation, tools);
            String text = r.getAssistantText();
            // emit fallback text only when there's assistant text and no tool call
            if (r.getToolCall() == null && text != null && !text.isBlank()) onDelta.accept(text);
            return r;
        }
    }

    // package-private helper for accumulating tool call fragments
    static class ToolPartial {
        String toolCallId = null;
        String name = null;
        StringBuilder arguments = new StringBuilder();
    }

    // package-private helper to accumulate tool-call fragments from a delta node
    static void accumulateToolPartials(JsonNode delta, java.util.Map<String, ToolPartial> partials) {
        if (delta == null || partials == null) return;
        var toolCalls = delta.get("tool_calls");
        if (toolCalls != null && toolCalls.isArray()) {
            for (int i = 0; i < toolCalls.size(); i++) {
                var tc = toolCalls.get(i);
                String key = stableToolKey(tc, i);
                ToolPartial p = partials.computeIfAbsent(key, k -> new ToolPartial());
                String toolCallId = extractToolCallIdFromToolCallNode(tc);
                if (toolCallId != null && !toolCallId.isBlank()) p.toolCallId = toolCallId;
                if (p.toolCallId == null || p.toolCallId.isBlank()) p.toolCallId = key;
                String name = extractNameFromToolCallNode(tc);
                if (name != null && !name.isBlank()) p.name = name;
                String argsText = extractArgumentsTextFromToolCallNode(tc);
                if (argsText != null && !argsText.isBlank()) p.arguments.append(argsText);
            }
        }

        var func = delta.get("function_call");
        if (func != null && !func.isNull()) {
            String id = "__fn__";
            ToolPartial p = partials.computeIfAbsent(id, k -> new ToolPartial());
            if (func.has("name")) p.name = func.get("name").asText();
            if (func.has("arguments")) {
                var argNode = func.get("arguments");
                if (argNode != null && !argNode.isNull()) {
                    if (argNode.isTextual()) p.arguments.append(argNode.asText());
                    else p.arguments.append(argNode.toString());
                }
            }
        }
    }

    // package-private helper to choose the first partial that contains a name
    static ToolPartial chooseNamedPartial(java.util.Map<String, ToolPartial> partials) {
        if (partials == null || partials.isEmpty()) return null;
        for (ToolPartial p : partials.values()) {
            if (p.name != null && !p.name.isBlank()) return p;
        }
        return null;
    }

    // stable grouping key for tool call partials: prefer index, then id, then loop index
    private static String stableToolKey(JsonNode tc, int loopIndex) {
        if (tc == null) return String.valueOf(loopIndex);
        if (tc.has("index") && !tc.get("index").isNull()) return tc.get("index").asText();
        if (tc.has("id") && !tc.get("id").isNull()) return tc.get("id").asText();
        return String.valueOf(loopIndex);
    }

    // extract name supporting modern shape (function.name) and legacy (name)
    private static String extractNameFromToolCallNode(JsonNode tc) {
        if (tc == null) return null;
        var fn = tc.get("function");
        if (fn != null && !fn.isNull() && fn.has("name") && !fn.get("name").isNull()) {
            return fn.get("name").asText();
        }
        if (tc.has("name") && !tc.get("name").isNull()) return tc.get("name").asText();
        return null;
    }

    private static String extractToolCallIdFromToolCallNode(JsonNode tc) {
        if (tc == null) return null;
        if (tc.has("id") && !tc.get("id").isNull()) return tc.get("id").asText();
        return null;
    }

    // extract arguments text supporting modern shape (function.arguments) and legacy (arguments)
    private static String extractArgumentsTextFromToolCallNode(JsonNode tc) {
        if (tc == null) return null;
        var fn = tc.get("function");
        JsonNode argNode = null;
        if (fn != null && !fn.isNull() && fn.has("arguments")) argNode = fn.get("arguments");
        else if (tc.has("arguments")) argNode = tc.get("arguments");
        if (argNode == null || argNode.isNull()) return null;
        if (argNode.isTextual()) return argNode.asText();
        return argNode.toString();
    }

    private List<Map<String, Object>> buildRawMessages(List<Message> conversation) {
        List<Map<String, Object>> msgs = new ArrayList<>(conversation.size());
        for (int i = 0; i < conversation.size(); i++) {
            Message m = conversation.get(i);
            Map<String, Object> msg = new LinkedHashMap<>();
            switch (m.getRole()) {
                case SYSTEM -> {
                    msg.put("role", "system");
                    msg.put("content", m.getContent());
                }
                case USER -> {
                    msg.put("role", "user");
                    msg.put("content", m.getContent());
                }
                case ASSISTANT -> {
                    msg.put("role", "assistant");
                    var toolCalls = m.getToolCalls();
                    if (toolCalls != null && !toolCalls.isEmpty()) {
                        msg.put("content", m.getContent() == null ? "" : m.getContent());
                        msg.put("tool_calls", buildRawToolCalls(toolCalls, i));
                    } else {
                        msg.put("content", m.getContent());
                    }
                }
                case TOOL -> {
                    msg.put("role", "tool");
                    msg.put("tool_call_id", requireToolCallId(m.getToolCallId()));
                    msg.put("content", m.getContent());
                }
            }
            msgs.add(msg);
        }
        return msgs;
    }

    private List<Map<String, Object>> buildRawToolCalls(List<ToolCall> toolCalls, int messageIndex) {
        List<Map<String, Object>> rawToolCalls = new ArrayList<>(toolCalls.size());
        for (int i = 0; i < toolCalls.size(); i++) {
            ToolCall toolCall = toolCalls.get(i);
            String toolCallId = normalizeToolCallId(toolCall.getToolCallId(), messageIndex, i);
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", requireToolName(toolCall.getToolName()));
            function.put("arguments", toJsonArguments(toolCall.getArguments()));

            Map<String, Object> rawToolCall = new LinkedHashMap<>();
            rawToolCall.put("id", toolCallId);
            rawToolCall.put("type", "function");
            rawToolCall.put("function", function);
            rawToolCalls.add(rawToolCall);
        }
        return rawToolCalls;
    }

    private String toJsonArguments(Map<String, Object> arguments) {
        try {
            return objectMapper.writeValueAsString(arguments == null ? Map.of() : arguments);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize tool call arguments", e);
        }
    }

    private static String normalizeToolCallId(String toolCallId, int messageIndex, int toolIndex) {
        if (toolCallId != null && !toolCallId.isBlank()) return toolCallId;
        return "tool-" + messageIndex + "-" + toolIndex;
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

    private void ensureModelInitialized() {
        if (this.model != null) return;
        synchronized (this) {
            if (this.model != null) return;
            String apiKey = openAiProperties.getApiKey();
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("OpenAI API key (openai.api-key) is required to call OpenAI provider");
            }
            ChatModel tmp = null;
            try {
                Object builder = OpenAiChatModel.builder();
                try {
                    var m = builder.getClass().getMethod("apiKey", String.class);
                    m.invoke(builder, apiKey);
                } catch (NoSuchMethodException ignored) {
                    // ignore if not present
                }
                // try modelName first so configured model is respected, then common fallback names
                String[] modelMethodNames = new String[]{"modelName", "model", "modelId"};
                for (String name : modelMethodNames) {
                    try {
                        var mm = builder.getClass().getMethod(name, String.class);
                        mm.invoke(builder, agentProperties.getModel());
                        break;
                    } catch (NoSuchMethodException ignored) {
                    }
                }
                var buildMethod = builder.getClass().getMethod("build");
                Object built = buildMethod.invoke(builder);
                tmp = (ChatModel) built;
            } catch (Exception e) {
                // fallback to default builder
                tmp = OpenAiChatModel.builder().build();
            }
            this.model = tmp;
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}

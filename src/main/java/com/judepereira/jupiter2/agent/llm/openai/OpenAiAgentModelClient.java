package com.judepereira.jupiter2.agent.llm.openai;

import com.judepereira.jupiter2.agent.config.OpenAiProperties;
import com.judepereira.jupiter2.agent.config.AgentProperties;
import com.judepereira.jupiter2.agent.llm.AgentModelClient;
import com.judepereira.jupiter2.agent.llm.dto.Message;
import com.judepereira.jupiter2.agent.llm.dto.ModelResponse;
import com.judepereira.jupiter2.agent.llm.dto.ToolCall;
import com.judepereira.jupiter2.agent.llm.dto.ToolDefinition;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class OpenAiAgentModelClient implements AgentModelClient {

    private final OpenAiProperties openAiProperties;
    private final AgentProperties agentProperties;
    private volatile ChatModel model;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenAiAgentModelClient(OpenAiProperties openAiProperties, AgentProperties agentProperties) {
        this.openAiProperties = openAiProperties;
        this.agentProperties = agentProperties;
        this.model = null; // build lazily when chat(...) is invoked
    }

    @Override
    public ModelResponse chat(List<Message> conversation, List<ToolDefinition> tools) {
        List<ChatMessage> msgs = conversation.stream().map(m -> {
            return switch (m.getRole()) {
                case SYSTEM -> SystemMessage.systemMessage(m.getContent());
                case ASSISTANT -> AiMessage.aiMessage(m.getContent());
                default -> UserMessage.userMessage(m.getContent());
            };
        }).collect(Collectors.toList());

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
                toolCall = new ToolCall(first.name(), argsMap);
            }
        }

        return new ModelResponse(assistantText, toolCall);
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

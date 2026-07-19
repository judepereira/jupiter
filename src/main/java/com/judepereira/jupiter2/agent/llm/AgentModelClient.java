package com.judepereira.jupiter2.agent.llm;

import com.judepereira.jupiter2.agent.llm.AgentModelOptions;
import com.judepereira.jupiter2.agent.llm.dto.Message;
import com.judepereira.jupiter2.agent.llm.dto.ModelResponse;
import com.judepereira.jupiter2.agent.llm.dto.ToolDefinition;

import java.util.List;
import java.util.function.Consumer;

public interface AgentModelClient {

    /**
     * Send conversation messages and available tools to the model and receive a response.
     * The response may contain assistant text and optionally a tool call request.
     */
    ModelResponse chat(List<Message> conversation, List<ToolDefinition> tools);

    default ModelResponse chat(List<Message> conversation, List<ToolDefinition> tools, AgentModelOptions options) {
        return chat(conversation, tools);
    }

    /**
     * Streaming variant. Default implementation calls chat(...) and emits the final assistant text as a single
     * delta to keep simple/non-streaming clients working.
     */
    default ModelResponse chatStreaming(List<Message> conversation, List<ToolDefinition> tools, Consumer<String> onDelta) {
        ModelResponse r = chat(conversation, tools);
        String t = r.getAssistantText();
        if (t != null && !t.isBlank()) {
            onDelta.accept(t);
        }
        return r;
    }

    default ModelResponse chatStreaming(List<Message> conversation, List<ToolDefinition> tools, AgentModelOptions options, Consumer<String> onDelta) {
        return chatStreaming(conversation, tools, onDelta);
    }
}

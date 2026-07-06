package com.judepereira.jupiter2.agent.llm;

import com.judepereira.jupiter2.agent.llm.dto.Message;
import com.judepereira.jupiter2.agent.llm.dto.ModelResponse;
import com.judepereira.jupiter2.agent.llm.dto.ToolDefinition;

import java.util.List;

public interface AgentModelClient {

    /**
     * Send conversation messages and available tools to the model and receive a response.
     * The response may contain assistant text and optionally a tool call request.
     */
    ModelResponse chat(List<Message> conversation, List<ToolDefinition> tools);
}

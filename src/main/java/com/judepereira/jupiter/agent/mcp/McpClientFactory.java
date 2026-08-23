package com.judepereira.jupiter.agent.mcp;

import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpClientListener;

import java.util.Map;

public interface McpClientFactory {
    McpClient create(String clientKey, String url, Map<String, String> headers, McpClientListener listener);
}

package com.judepereira.jupiter.agent.mcp;

import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpClientListener;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

@Component
final class DefaultMcpClientFactory implements McpClientFactory {
    @Override
    public McpClient create(String clientKey, String url, Map<String, String> headers, McpClientListener listener) {
        return DefaultMcpClient.builder()
                .key(clientKey)
                .transport(StreamableHttpMcpTransport.builder()
                        .url(url)
                        .customHeaders(headers)
                        .timeout(Duration.ofSeconds(10))
                        .build())
                .cacheToolList(false)
                .addListener(listener)
                .build();
    }
}

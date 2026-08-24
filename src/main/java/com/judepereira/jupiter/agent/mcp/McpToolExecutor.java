package com.judepereira.jupiter.agent.mcp;

import com.judepereira.jupiter.agent.tools.ToolExecutionContext;
import com.judepereira.jupiter.agent.tools.ToolExecutionResult;
import com.judepereira.jupiter.agent.tools.ToolProgressSink;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.McpClient;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.util.Map;

@RequiredArgsConstructor
public final class McpToolExecutor {
    private final McpClient client;
    private final String serverSlug;
    private final String toolSlug;
    private final String mcpToolName;

    public ToolExecutionResult execute(Map<String, Object> args, ToolExecutionContext context) throws Exception {
        String arguments = McpToolJson.toJson(args);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id(context.getToolCallId())
                .name(toolSlug)
                .arguments(arguments)
                .build();

        var result = client.executeTool(request);
        String text = result.resultText();
        if (text == null && result.result() != null) {
            text = result.result().toString();
        }
        return new ToolExecutionResult(!result.isError(), text, result.attributes());
    }

    public void close() {
        try {
            client.close();
        } catch (Exception ignored) {
            // closing is best-effort here; the manager owns lifecycle and will retry on reload
        }
    }

    public String mcpToolName() {
        return mcpToolName;
    }
}

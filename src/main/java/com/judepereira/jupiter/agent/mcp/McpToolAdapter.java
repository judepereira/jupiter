package com.judepereira.jupiter.agent.mcp;

import com.judepereira.jupiter.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter.agent.llm.dto.ToolParameter;
import com.judepereira.jupiter.agent.llm.dto.ToolSchema;
import com.judepereira.jupiter.agent.tools.ToolExecutionContext;
import com.judepereira.jupiter.agent.tools.ToolExecutionResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class McpToolAdapter implements McpProjectToolExecutor {
    private final String modelToolName;
    private final String serverSlug;
    private final String toolSlug;
    private final String remoteToolName;
    private final McpClient client;
    private final ToolDefinition toolDefinition;

    private McpToolAdapter(String modelToolName, String serverSlug, String toolSlug, String remoteToolName, McpClient client, ToolDefinition toolDefinition) {
        this.modelToolName = modelToolName;
        this.serverSlug = serverSlug;
        this.toolSlug = toolSlug;
        this.remoteToolName = remoteToolName;
        this.client = client;
        this.toolDefinition = toolDefinition;
    }

    static McpToolAdapter from(McpClient client, String serverSlug, ToolSpecification specification) {
        String toolSlug = McpTemplateResolver.slugify(specification.name());
        String modelToolName = "mcp__" + serverSlug + "__" + toolSlug;
        return new McpToolAdapter(modelToolName, serverSlug, toolSlug, specification.name(), client,
                new ToolDefinition(modelToolName, specification.description(), toSchema(specification)));
    }

    ToolDefinition definition() {
        return toolDefinition;
    }

    @Override
    public String modelToolName() {
        return modelToolName;
    }

    @Override
    public String serverSlug() {
        return serverSlug;
    }

    @Override
    public String toolSlug() {
        return toolSlug;
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> args, ToolExecutionContext context) throws Exception {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id(context.getToolCallId())
                .name(remoteToolName)
                .arguments(McpToolJson.toJson(args))
                .build();
        var result = client.executeTool(request);
        String text = result.resultText();
        if (text == null && result.result() != null) {
            text = result.result().toString();
        }
        return new ToolExecutionResult(!result.isError(), text, result.attributes());
    }

    private static ToolSchema toSchema(ToolSpecification specification) {
        if (specification.parameters() == null) {
            return ToolSchema.object();
        }
        List<ToolParameter> properties = new ArrayList<>();
        for (var entry : specification.parameters().properties().entrySet()) {
            properties.add(toParameter(entry.getKey(), entry.getValue()));
        }
        return new ToolSchema(specification.parameters().description(), properties, specification.parameters().required(), specification.parameters().additionalProperties());
    }

    /**
     * Minimal schema conversion: primitive/object/enum shapes are preserved.
     * More advanced LangChain4j schema nodes are flattened to strings/objects.
     * That keeps the runtime compiling and exposes basic object schemas.
     */
    private static ToolParameter toParameter(String name, dev.langchain4j.model.chat.request.json.JsonSchemaElement element) {
        if (element instanceof dev.langchain4j.model.chat.request.json.JsonStringSchema stringSchema) {
            return ToolParameter.string(name, stringSchema.description());
        }
        if (element instanceof dev.langchain4j.model.chat.request.json.JsonIntegerSchema integerSchema) {
            return ToolParameter.integer(name, integerSchema.description());
        }
        if (element instanceof dev.langchain4j.model.chat.request.json.JsonNumberSchema numberSchema) {
            return ToolParameter.number(name, numberSchema.description());
        }
        if (element instanceof dev.langchain4j.model.chat.request.json.JsonBooleanSchema booleanSchema) {
            return ToolParameter.bool(name, booleanSchema.description());
        }
        if (element instanceof dev.langchain4j.model.chat.request.json.JsonEnumSchema enumSchema) {
            return ToolParameter.enumeration(name, enumSchema.description(), enumSchema.enumValues());
        }
        if (element instanceof dev.langchain4j.model.chat.request.json.JsonObjectSchema objectSchema) {
            return ToolParameter.object(name, objectSchema.description(), toSchema(objectSchema));
        }
        return ToolParameter.string(name, element.description());
    }

    private static ToolSchema toSchema(dev.langchain4j.model.chat.request.json.JsonObjectSchema schema) {
        List<ToolParameter> properties = new ArrayList<>();
        for (var entry : schema.properties().entrySet()) {
            properties.add(toParameter(entry.getKey(), entry.getValue()));
        }
        return new ToolSchema(schema.description(), properties, schema.required(), schema.additionalProperties());
    }
}

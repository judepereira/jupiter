package com.judepereira.jupiter.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.llm.dto.ToolParameter;
import com.judepereira.jupiter.agent.tools.ToolExecutionContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


class McpToolAdapterTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void serializesNestedArgumentsWithoutDoubleEncoding() throws Exception {
        McpClient client = mock(McpClient.class);
        when(client.executeTool(org.mockito.ArgumentMatchers.any(ToolExecutionRequest.class)))
                .thenAnswer(invocation -> {
                    ToolExecutionRequest request = invocation.getArgument(0);
                    JsonNode arguments = JSON.readTree(request.arguments());
                    assertTrue(arguments.get("items").isArray());
                    assertTrue(arguments.get("items").get(0).isObject());
                    assertEquals("value", arguments.get("items").get(0).get("name").asText());
                    return ToolExecutionResult.builder().resultText("ok").build();
                });

        var adapter = adapter(client);
        var result = adapter.execute(Map.of("items", List.of(Map.of("name", "value"))), context());

        assertTrue(result.isSuccess());
        assertEquals("ok", result.getText());
    }

    @Test
    void retainsTextResultsAndAttributes() throws Exception {
        McpClient client = mock(McpClient.class);
        when(client.executeTool(org.mockito.ArgumentMatchers.any())).thenReturn(ToolExecutionResult.builder()
                .isError(true).resultText("failure").attributes(Map.of("code", 7)).build());

        var result = adapter(client).execute(Map.of(), context());

        assertFalse(result.isSuccess());
        assertEquals("failure", result.getText());
        assertEquals(7, result.getMachine().get("code"));
    }

    @Test
    void serializesStructuredNestedResultsAsJson() throws Exception {
        McpClient client = mock(McpClient.class);
        ToolExecutionResult execution = mock(ToolExecutionResult.class);
        when(execution.result()).thenReturn(Map.of("items", List.of(Map.of("id", 3))));
        when(execution.resultText()).thenReturn(null);
        when(execution.isError()).thenReturn(false);
        when(client.executeTool(org.mockito.ArgumentMatchers.any())).thenReturn(execution);

        var result = adapter(client).execute(Map.of(), context());

        assertEquals("{\"items\":[{\"id\":3}]}", result.getText());
    }

    private static McpToolAdapter adapter(McpClient client) {
        return McpToolAdapter.from(client, "server", ToolSpecification.builder().name("tool").build());
    }

    private static ToolExecutionContext context() {
        return new ToolExecutionContext(Path.of("."), false, false, 30, null, null, null,
                "call", Map.of(), null, null);
    }

    @Test
    void passesNativeSchemaToLangChain4jMapperUnderAliasName() {
        JsonObjectSchema nativeSchema = JsonObjectSchema.builder()
                .description("remote input")
                .addProperty("items", JsonArraySchema.builder()
                        .items(JsonObjectSchema.builder()
                                .addProperty("value", JsonStringSchema.builder().build())
                                .build())
                        .build())
                .build();
        ToolSpecification remote = ToolSpecification.builder()
                .name("remote-tool")
                .description("remote description")
                .parameters(nativeSchema)
                .build();

        var definition = McpToolAdapter.from(null, "server", remote).definition();
        var mapped = new com.judepereira.jupiter.agent.llm.openai.LangChain4jToolSpecificationMapper()
                .toToolSpecifications(List.of(definition)).getFirst();

        assertEquals("mcp__server__remote_tool", mapped.name());
        assertEquals("remote description", mapped.description());
        assertSame(nativeSchema, mapped.parameters());
    }

    @Test
    void preserves_recursive_array_schemas() {
        JsonArraySchema labels = JsonArraySchema.builder()
                .description("labels")
                .items(JsonStringSchema.builder().description("label").build())
                .build();
        JsonArraySchema records = JsonArraySchema.builder()
                .description("records")
                .items(JsonObjectSchema.builder()
                        .description("record")
                        .addProperty("name", JsonStringSchema.builder().build())
                        .build())
                .build();

        var definition = McpToolAdapter.from(null, "server", ToolSpecification.builder()
                .name("tool")
                .parameters(JsonObjectSchema.builder()
                        .addProperty("labels", labels)
                        .addProperty("records", records)
                        .build())
                .build()).definition();

        ToolParameter.ArrayParameter labelsParameter = assertInstanceOf(ToolParameter.ArrayParameter.class,
                definition.getSchema().properties().getFirst());
        assertInstanceOf(ToolParameter.StringParameter.class, labelsParameter.items());
        ToolParameter.ArrayParameter recordsParameter = assertInstanceOf(ToolParameter.ArrayParameter.class,
                definition.getSchema().properties().get(1));
        ToolParameter.ObjectParameter record = assertInstanceOf(ToolParameter.ObjectParameter.class, recordsParameter.items());
        assertInstanceOf(ToolParameter.StringParameter.class, record.schema().properties().getFirst());
    }
}

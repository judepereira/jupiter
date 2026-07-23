package com.judepereira.jupiter2.agent.tools;

import com.judepereira.jupiter2.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter2.agent.llm.dto.ToolSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ToolRegistryTest {

    @Test
    public void non_task_output_over_5_kib_is_truncated(@TempDir Path tmp) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        String text = "x".repeat(6 * 1024);
        registry.register(tool("read_file", text));

        ToolExecutionResult result = registry.executeByName("read_file", Map.of(), new ToolExecutionContext(tmp, true, true, 5));

        String suffix = "\n\n[tool_output_truncated: output exceeded 5 KiB. Use startLine/endLine to read a smaller range.]";
        assertTrue(result.getText().endsWith(suffix));
        assertTrue(result.getText().getBytes(StandardCharsets.UTF_8).length <= 5120);
        assertTrue(result.getText().startsWith("x"));
        assertEquals(true, result.isSuccess());
        assertEquals(Map.of("ok", true), result.getMachine());
    }

    @Test
    public void task_output_over_5_kib_is_not_truncated(@TempDir Path tmp) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        String text = "x".repeat(6 * 1024);
        registry.register(tool("task", text));

        ToolExecutionResult result = registry.executeByName("task", Map.of(), new ToolExecutionContext(tmp, true, true, 5));

        assertEquals(text, result.getText());
        assertTrue(result.getText().getBytes(StandardCharsets.UTF_8).length > 5120);
    }

    @Test
    public void multi_byte_output_is_truncated_on_code_point_boundaries(@TempDir Path tmp) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        String text = "😀".repeat(4000);
        registry.register(tool("search_code", text));

        ToolExecutionResult result = registry.executeByName("search_code", Map.of(), new ToolExecutionContext(tmp, true, true, 5));

        String suffix = "\n\n[tool_output_truncated: output exceeded 5 KiB. Narrow path, include, or pattern.]";
        assertTrue(result.getText().endsWith(suffix));
        assertTrue(result.getText().getBytes(StandardCharsets.UTF_8).length <= 5120);

        String prefix = result.getText().substring(0, result.getText().length() - suffix.length());
        assertTrue(prefix.codePoints().allMatch(cp -> cp == 0x1F600));
    }

    @Test
    public void null_text_remains_null(@TempDir Path tmp) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool("read_file", null));

        ToolExecutionResult result = registry.executeByName("read_file", Map.of(), new ToolExecutionContext(tmp, true, true, 5));

        assertNull(result.getText());
        assertEquals(true, result.isSuccess());
        assertEquals(Map.of("ok", true), result.getMachine());
    }

    private static AgentTool tool(String name, String text) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(name, "", ToolSchema.object());
            }

            @Override
            public ToolExecutionResult execute(Map<String, Object> args, ToolExecutionContext context) {
                return new ToolExecutionResult(true, text, Map.of("ok", true));
            }
        };
    }
}

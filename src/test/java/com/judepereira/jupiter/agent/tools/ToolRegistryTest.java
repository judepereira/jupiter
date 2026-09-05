package com.judepereira.jupiter.agent.tools;

import com.judepereira.jupiter.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter.agent.llm.dto.ToolSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ToolRegistryTest {

    @Test
    public void non_task_output_over_limit_is_truncated(@TempDir Path tmp) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        String text = "x".repeat(40 * 1024);
        registry.register(tool("read_file", text));

        ToolExecutionResult result = registry.executeByName("read_file", Map.of(), new ToolExecutionContext(tmp, true, true, 5, null, null, null, null, null, null, null));

        String suffix = "\n\n[tool_output_truncated: output exceeded 16 KiB. Use startLine/endLine to read a smaller range.]";
        assertTrue(result.getText().endsWith(suffix));
        assertTrue(result.getText().getBytes(StandardCharsets.UTF_8).length <= 16 * 1024);
        assertTrue(result.getText().startsWith("x"));
        assertTrue(result.isSuccess());
        assertEquals(Map.of("ok", true), result.getMachine());
    }

    @Test
    public void task_output_is_not_truncated(@TempDir Path tmp) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        String text = "x".repeat(40 * 1024);
        registry.register(tool("task", text));

        ToolExecutionResult result = registry.executeByName("task", Map.of(), new ToolExecutionContext(tmp, true, true, 5, null, null, null, null, null, null, null));

        assertEquals(text, result.getText());
        assertTrue(result.getText().getBytes(StandardCharsets.UTF_8).length > 16 * 1024);
    }

    @Test
    public void run_command_output_under_new_limit_is_not_truncated(@TempDir Path tmp) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        String text = "x".repeat(8 * 1024);
        registry.register(tool("run_command", text));

        ToolExecutionResult result = registry.executeByName("run_command", Map.of(), new ToolExecutionContext(tmp, true, true, 5, null, null, null, null, null, null, null));

        assertEquals(text, result.getText());
    }

    @Test
    public void multi_byte_output_is_truncated_on_code_point_boundaries(@TempDir Path tmp) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        String text = "😀".repeat(9000);
        registry.register(tool("search_code", text));

        ToolExecutionResult result = registry.executeByName("search_code", Map.of(), new ToolExecutionContext(tmp, true, true, 5, null, null, null, null, null, null, null));

        String suffix = "\n\n[tool_output_truncated: output exceeded 16 KiB. Narrow path, include, or pattern.]";
        assertTrue(result.getText().endsWith(suffix));
        assertTrue(result.getText().getBytes(StandardCharsets.UTF_8).length <= 16 * 1024);

        String prefix = result.getText().substring(0, result.getText().length() - suffix.length());
        assertTrue(prefix.codePoints().allMatch(cp -> cp == 0x1F600));
    }

    @Test
    public void null_text_remains_null(@TempDir Path tmp) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool("read_file", null));

        ToolExecutionResult result = registry.executeByName("read_file", Map.of(), new ToolExecutionContext(tmp, true, true, 5, null, null, null, null, null, null, null));

        assertNull(result.getText());
        assertTrue(result.isSuccess());
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
                return ToolDefinition.builtIn(name, "", ToolSchema.object());
            }

            @Override
            public ToolExecutionResult execute(Map<String, Object> args, ToolExecutionContext context) {
                return new ToolExecutionResult(true, text, Map.of("ok", true));
            }
        };
    }
}

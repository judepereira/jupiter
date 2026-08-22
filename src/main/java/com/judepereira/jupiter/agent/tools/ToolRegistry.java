package com.judepereira.jupiter.agent.tools;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class ToolRegistry {
    private static final int MAX_OUTPUT_BYTES = 16 * 1024;
    private final Map<String, AgentTool> tools = new HashMap<>();

    public void register(AgentTool tool) {
        tools.put(tool.name(), tool);
    }

    public Map<String, AgentTool> all() {
        return Collections.unmodifiableMap(tools);
    }

    public AgentTool get(String name) {
        return tools.get(name);
    }

    public ToolExecutionResult executeByName(String name, Map<String, Object> args, ToolExecutionContext context) throws Exception {
        AgentTool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("Unknown tool: " + name);
        }
        ToolExecutionResult result = tool.execute(args, context);
        return "task".equals(name) ? result : truncateOutput(name, result);
    }

    private ToolExecutionResult truncateOutput(String toolName, ToolExecutionResult result) {
        String text = result.getText();
        if (text == null || text.getBytes(StandardCharsets.UTF_8).length <= MAX_OUTPUT_BYTES) {
            return result;
        }

        String suffix = suffixFor(toolName);
        int prefixBudget = MAX_OUTPUT_BYTES - suffix.getBytes(StandardCharsets.UTF_8).length;
        String truncated = truncateUtf8(text, prefixBudget) + suffix;
        return new ToolExecutionResult(result.isSuccess(), truncated, result.getMachine());
    }

    private static String truncateUtf8(String text, int maxBytes) {
        int bytes = 0;
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            int codePointBytes = utf8Length(codePoint);
            if (bytes + codePointBytes > maxBytes) {
                break;
            }
            bytes += codePointBytes;
            index += Character.charCount(codePoint);
        }
        return text.substring(0, index);
    }

    private static int utf8Length(int codePoint) {
        if (codePoint <= 0x7F) {
            return 1;
        }
        if (codePoint <= 0x7FF) {
            return 2;
        }
        if (codePoint <= 0xFFFF) {
            return 3;
        }
        return 4;
    }

    private static String suffixFor(String toolName) {
        return switch (toolName) {
            case "read_file" -> "\n\n[tool_output_truncated: output exceeded 16 KiB. Use startLine/endLine to read a smaller range.]";
            case "search_code" -> "\n\n[tool_output_truncated: output exceeded 16 KiB. Narrow path, include, or pattern.]";
            case "list_files" -> "\n\n[tool_output_truncated: output exceeded 16 KiB. Narrow path or include.]";
            case "run_command" -> "\n\n[tool_output_truncated: output exceeded 16 KiB. Refine the command or redirect full output to a file.]";
            default -> "\n\n[tool_output_truncated: output exceeded 16 KiB.]";
        };
    }
}

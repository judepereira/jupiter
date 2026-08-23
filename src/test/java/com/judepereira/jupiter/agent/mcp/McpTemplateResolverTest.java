package com.judepereira.jupiter.agent.mcp;

import com.judepereira.jupiter.persistence.Persistence.McpServerHeader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpTemplateResolverTest {

    @Test
    void resolves_project_env_before_system_env() {
        McpTemplateResolver resolver = new McpTemplateResolver(name -> "system-" + name);

        String resolved = resolver.resolve("MCP server URL", "https://${env.FOO}/api", Map.of("FOO", "project"));

        assertEquals("https://project/api", resolved);
    }

    @Test
    void falls_back_to_system_env_lookup() {
        McpTemplateResolver resolver = new McpTemplateResolver(name -> "system-" + name);

        String resolved = resolver.resolve("MCP server URL", "https://${env.BAR}/api", Map.of());

        assertEquals("https://system-BAR/api", resolved);
    }

    @Test
    void rejects_line_breaks_without_echoing_the_value() {
        McpTemplateResolver resolver = new McpTemplateResolver(name -> null);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve("MCP header Authorization", "token\r\nsecret", Map.of()));

        assertEquals("MCP header Authorization must not contain line breaks", exception.getMessage());
    }

    @Test
    void resolves_headers_in_order_and_copies_result() {
        McpTemplateResolver resolver = new McpTemplateResolver(name -> null);

        Map<String, String> headers = resolver.resolveHeaders(List.of(
                new McpServerHeader("Authorization", "Bearer abc"),
                new McpServerHeader("X-Test", "value")
        ), Map.of());

        assertEquals(Map.of("Authorization", "Bearer abc", "X-Test", "value"), headers);
        assertThrows(UnsupportedOperationException.class, () -> headers.put("X-Other", "nope"));
    }
}

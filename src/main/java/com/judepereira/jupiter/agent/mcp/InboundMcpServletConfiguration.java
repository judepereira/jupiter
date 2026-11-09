package com.judepereira.jupiter.agent.mcp;

import com.judepereira.jupiter.config.InboundMcpAuthFilter;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@DependsOn("encryptedProjectStartupValidator")
public class InboundMcpServletConfiguration {
    private static final Logger logger = LoggerFactory.getLogger(InboundMcpServletConfiguration.class);
    @Bean
    @DependsOn("encryptedProjectStartupValidator")
    HttpServletStreamableServerTransportProvider inboundMcpTransport(JsonMapper jsonMapper) {
        JacksonMcpJsonMapper mapper = new JacksonMcpJsonMapper(jsonMapper);
        return HttpServletStreamableServerTransportProvider.builder().jsonMapper(mapper).mcpEndpoint("/mcp")
                .contextExtractor(request -> McpTransportContext
                        .create(Map.of("credential", request.getAttribute(InboundMcpAuthFilter.CREDENTIAL_ATTRIBUTE))))
                .build();
    }

    @Bean(destroyMethod = "close")
    McpSyncServer inboundMcpServer(HttpServletStreamableServerTransportProvider transport, JsonMapper jsonMapper,
            InboundMcpToolService tools) {
        JacksonMcpJsonMapper mapper = new JacksonMcpJsonMapper(jsonMapper);
        return McpServer.sync(transport).serverInfo("jupiter", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .addToolFilter((exchange, tool) -> {
                    var credential = (InboundMcpTokenService.Credential) exchange.get("credential");
                    return credential != null && credential.tools().contains(tool.name());
                }).tools(specifications(mapper, tools)).build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> inboundMcpServlet(
            HttpServletStreamableServerTransportProvider transport, McpSyncServer server) {
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration = new ServletRegistrationBean<>(
                transport, "/mcp");
        registration.setName("inboundMcpServlet");
        registration.setLoadOnStartup(1);
        registration.setAsyncSupported(true);
        return registration;
    }

    private List<McpServerFeatures.SyncToolSpecification> specifications(JacksonMcpJsonMapper mapper,
            InboundMcpToolService tools) {
        return List.of(tool(mapper, tools, "list_projects", "List accessible projects", schema()),
                tool(mapper, tools, "list_workspaces", "List workspaces in an accessible project. Requires projectId.",
                        schema("projectId", number(), "required", List.of("projectId"))),
                tool(mapper, tools, "list_sessions", "List sessions in a workspace or project",
                        schema("workspaceId", number(), "projectId", number())),
                tool(mapper, tools, "list_agents",
                        "List available primary agents for an accessible project. Requires projectId.",
                        schema("projectId", number(), "required", List.of("projectId"))),
                tool(mapper, tools, "create_workspace",
                        "Create a workspace and initial session. Requires permission to create workspaces.",
                        schema("projectId", number(), "branchName", string(), "createBranch", bool(), "required",
                                List.of("projectId", "branchName", "createBranch"))),
                tool(mapper, tools, "post_message", "Post a message to a session", schema("sessionId", number(),
                        "workspaceId", number(), "projectId", number(), "branchName", string(), "createBranch", bool(),
                        "message", string(), "sessionName", string(), "agentId", string(), "modelId", string(),
                        "thinkingLevel", string(), "required", List.of("message"), "oneOf",
                        List.of(Map.of("required", List.of("sessionId")), Map.of("required", List.of("workspaceId")),
                                Map.of("required", List.of("projectId", "branchName", "createBranch"))))),
                tool(mapper, tools, "summarize_session", "Summarize an accessible session. Requires sessionId.",
                        schema("sessionId", number(), "required", List.of("sessionId"))));
    }

    private McpServerFeatures.SyncToolSpecification tool(JacksonMcpJsonMapper mapper, InboundMcpToolService service,
            String name, String description, Map<String, Object> schema) {
        McpSchema.Tool definition = McpSchema.Tool.builder().name(name).description(description).inputSchema(schema)
                .build();
        return new McpServerFeatures.SyncToolSpecification(definition, (exchange, request) -> {
            var credential = (InboundMcpTokenService.Credential) exchange.transportContext().get("credential");
            try {
                Object result = service.call(name, request.arguments(), credential);
                Map<String, Object> response = Map.of("result", result);
                return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(mapper.writeValueAsString(response))))
                        .structuredContent(response).build();
            } catch (IllegalArgumentException exception) {
                return error(mapper, "INVALID_ARGUMENT", exception.getMessage());
            } catch (IllegalStateException exception) {
                String message = exception.getMessage();
                if (message != null && message.startsWith("SESSION_BUSY:")) {
                    return error(mapper, "SESSION_BUSY", message.substring("SESSION_BUSY:".length()).trim());
                }
                if ("Resource unavailable".equals(message)) {
                    return error(mapper, "RESOURCE_UNAVAILABLE", message);
                }
                logger.error("Inbound MCP tool failed: {}", name);
                return error(mapper, "EXECUTION_FAILED", "Tool execution failed");
            } catch (RuntimeException exception) {
                logger.error("Inbound MCP tool failed: {}", name);
                return error(mapper, "EXECUTION_FAILED", "Tool execution failed");
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to encode MCP tool result", exception);
            }
        });
    }

    private McpSchema.CallToolResult error(JacksonMcpJsonMapper mapper, String code, String message) {
        Map<String, Object> response = Map.of("error", Map.of("code", code, "message", message));
        try {
            return McpSchema.CallToolResult.builder()
                    .content(List.of(new McpSchema.TextContent(mapper.writeValueAsString(response))))
                    .structuredContent(response).isError(true).build();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to encode MCP error result", exception);
        }
    }

    private static Map<String, Object> schema(Object... properties) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i < properties.length; i += 2) {
            if (properties[i].equals("required") || properties[i].equals("oneOf")) {
                continue;
            }
            fields.put((String) properties[i], (Map<String, Object>) properties[i + 1]);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "object");
        result.put("properties", fields);
        result.put("additionalProperties", false);
        for (int i = 0; i < properties.length; i += 2) {
            if (properties[i].equals("required") || properties[i].equals("oneOf")) {
                result.put((String) properties[i], properties[i + 1]);
            }
        }
        return result;
    }
    private static Map<String, Object> number() {
        return Map.of("type", "integer");
    }
    private static Map<String, Object> string() {
        return Map.of("type", "string");
    }
    private static Map<String, Object> bool() {
        return Map.of("type", "boolean");
    }
}

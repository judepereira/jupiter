package com.judepereira.jupiter.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.judepereira.jupiter.agent.llm.AgentModelClient;
import com.judepereira.jupiter.agent.llm.AgentModelClientFactory;
import com.judepereira.jupiter.agent.llm.dto.Message;
import com.judepereira.jupiter.agent.llm.dto.ModelResponse;
import com.judepereira.jupiter.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter.persistence.AppStateService;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

class InboundMcpE2ETest extends E2ETestSupport {
    private static final String MODEL_MARKER = "Inbound MCP deterministic response";
    private static final String SUMMARY_MARKER = "Inbound MCP deterministic summary";
    private static final Set<String> TOOLS = Set.of("list_projects", "list_workspaces", "list_sessions", "list_agents",
            "create_workspace", "post_message", "summarize_session");

    @Test
    void tokenCanUseRealMcpStreamableHttpEndpoint(@TempDir Path tempDir) throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("home"));
        Path repository = Files.createDirectories(home.resolve("project"));
        initGitRepoWithInitialCommit(repository);
        try (RunningApp app = startApp(home, tempDir.resolve("state.db"), TestAppConfig.class);
                BrowserContext browser = newBrowserContext()) {
            Page page = browser.newPage();
            page.navigate(app.baseUrl());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Settings")).click();
            page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("Inbound MCP")).click();
            Locator settings = page.locator("#inbound-mcp-settings");
            PlaywrightAssertions.assertThat(settings.locator("article")).hasCount(0);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("create token")).click();
            Locator form = page.locator("#inbound-mcp-create-form form");
            form.locator("input[name='name']").fill("e2e client");
            form.locator("input[name='allProjects']").check();
            page.waitForResponse(response -> response.url().endsWith("/create")
                    && response.request().method().equals("POST") && response.status() == 200,
                    form.locator("button[type='submit']")::click);
            String token = settings.locator(".alert-warning code").textContent().trim();
            page.locator("#settings-modal .btn-close").click();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Settings")).click();
            page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("Inbound MCP")).click();
            PlaywrightAssertions.assertThat(page.locator("#inbound-mcp-settings .alert-warning")).hasCount(0);
            McpHttpTestClient client = new McpHttpTestClient(app.baseUrl(), token);
            JsonNode initialized = client.initialize("e2e");
            assertThat(initialized.at("/result/serverInfo").isMissingNode()).isFalse();
            JsonNode listedTools = client.tools().at("/result/tools");
            assertThat(listedTools.findValuesAsText("name")).containsExactlyInAnyOrderElementsOf(TOOLS);
            page.locator("#settings-modal .btn-close").click();

            openProject(page, "MCP E2E Project", repository);
            AppStateService state = app.context().getBean(AppStateService.class);
            long originalSession = state.loadViewData().activeSession().id();
            long originalWorkspace = state.loadViewData().activeWorkspace().id();
            JsonNode projects = call(client, "list_projects", Map.of());
            assertThat(projects.isArray()).isTrue();
            long projectId = projects.get(0).path("id").asLong();
            assertThat(call(client, "list_workspaces", Map.of("projectId", projectId)).findValuesAsText("id"))
                    .contains(Long.toString(originalWorkspace));
            assertThat(call(client, "list_sessions", Map.of("projectId", projectId)).findValuesAsText("id"))
                    .contains(Long.toString(originalSession));
            assertThat(call(client, "list_agents", Map.of("projectId", projectId))).isNotEmpty();
            List<JsonNode> workspacesBefore = call(client, "list_workspaces", Map.of("projectId", projectId))
                    .findValues("id");
            List<JsonNode> sessionsBefore = call(client, "list_sessions", Map.of("projectId", projectId))
                    .findValues("id");
            for (Map<String, ?> invalid : List.<Map<String, ?>>of(Map.of("message", "invalid"),
                    Map.of("workspaceId", originalWorkspace, "sessionId", originalSession, "message", "invalid"),
                    Map.of("sessionId", originalSession, "message", "invalid", "agentId", "missing-agent"),
                    Map.of("sessionId", originalSession, "message", "invalid", "modelId", "missing-model"),
                    Map.of("sessionId", originalSession, "message", "invalid", "thinkingLevel", "invalid"),
                    Map.of("projectId", projectId, "branchName", "invalid-agent", "createBranch", true, "message",
                            "invalid", "agentId", "missing-agent"))) {
                assertThat(client.call("post_message", invalid).path("result").path("isError").asBoolean()).isTrue();
            }
            assertThat(call(client, "list_workspaces", Map.of("projectId", projectId)).findValues("id"))
                    .isEqualTo(workspacesBefore);
            assertThat(call(client, "list_sessions", Map.of("projectId", projectId)).findValues("id"))
                    .isEqualTo(sessionsBefore);

            String branch = "mcp-e2e-workspace";
            JsonNode created = call(client, "create_workspace",
                    Map.of("projectId", projectId, "branchName", branch, "createBranch", true));
            long workspaceId = created.path("workspaceId").asLong();
            long sessionId = created.path("sessionId").asLong();
            assertThat(created.path("name").asText()).isEqualTo(branch);
            assertThat(workspaceId).isPositive();
            PlaywrightAssertions.assertThat(page.locator("#chat-container[data-session-id='" + originalSession + "']"))
                    .isVisible();

            assertThat(
                    call(client, "post_message", Map.of("sessionId", sessionId, "message", "deterministic MCP request"))
                            .path("sessionId").asLong())
                    .isEqualTo(sessionId);
            awaitAnswer(state, sessionId);
            var assistant = state.loadSessionDetail(sessionId).chatMessages().stream()
                    .filter(message -> "assistant".equals(message.role()) && message.text().contains(MODEL_MARKER)
                            && !message.pending() && message.completedTs() != null)
                    .findFirst().orElseThrow();
            var replay = page.request().get(app.baseUrl() + "/ui/chat/stream/" + assistant.id());
            assertThat(replay.text()).contains("event:done", MODEL_MARKER);
            List<?> beforeSummary = state.loadSessionDetail(sessionId).chatMessages();
            JsonNode summary = call(client, "summarize_session", Map.of("sessionId", sessionId));
            assertThat(summary.path("summary").asText()).isEqualTo(SUMMARY_MARKER);
            assertThat(summary.path("cutoffTurnId").asLong()).isPositive();
            assertThat(state.loadSessionDetail(sessionId).chatMessages()).isEqualTo(beforeSummary);

            long namedSession = post(client, Map.of("workspaceId", workspaceId, "message",
                    "workspace-targeted MCP request", "sessionName", "named MCP session"), projectId, workspaceId);
            JsonNode namedSessions = call(client, "list_sessions", Map.of("workspaceId", workspaceId));
            assertThat(namedSessions.findParents("id").stream()
                    .anyMatch(session -> Long.toString(namedSession).equals(session.path("id").asText())
                            && "named MCP session".equals(session.path("name").asText())))
                    .isTrue();
            awaitAnswer(state, namedSession);
            long projectSession = post(client, Map.of("projectId", projectId, "branchName", "mcp-e2e-post-project",
                    "createBranch", true, "message", "project-targeted MCP request"), projectId, -1);
            awaitAnswer(state, projectSession);

            Page reopened = browser.newPage();
            reopened.navigate(app.baseUrl());
            reopened.locator("#chat-container[data-session-id='" + originalSession + "']").waitFor();
            PlaywrightAssertions.assertThat(reopened.locator("#chat-container")).not().containsText(MODEL_MARKER);
            Locator targetWorkspace = reopened.locator(".workspace-group")
                    .filter(new Locator.FilterOptions().setHasText("mcp-e2e-post-project"));
            targetWorkspace.locator(".workspace-item").click();
            targetWorkspace.locator(".session-item[hx-post='/ui/sessions/" + projectSession + "/activate']").click();
            reopened.locator("#chat-container[data-session-id='" + projectSession + "']").waitFor();
            reopened.getByText(MODEL_MARKER).waitFor();
        }
    }

    private static JsonNode call(McpHttpTestClient client, String tool, Map<String, ?> args) throws Exception {
        JsonNode response = client.call(tool, args);
        JsonNode result = response.path("result");
        assertThat(result.path("isError").asBoolean()).as("%s response: %s", tool, response).isFalse();
        return result.path("structuredContent").path("result");
    }

    private static long post(McpHttpTestClient client, Map<String, ?> args, long projectId, long workspaceId)
            throws Exception {
        JsonNode result = call(client, "post_message", args);
        assertThat(result.path("accepted").asBoolean()).isTrue();
        assertThat(result.path("projectId").asLong()).isEqualTo(projectId);
        long targetWorkspace = result.path("workspaceId").asLong();
        if (workspaceId > 0) {
            assertThat(targetWorkspace).isEqualTo(workspaceId);
        }
        long session = result.path("sessionId").asLong();
        assertThat(session).isPositive();
        return session;
    }

    private static void awaitAnswer(AppStateService state, long sessionId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (state.loadSessionDetail(sessionId).chatMessages().stream()
                    .anyMatch(message -> "assistant".equals(message.role()) && message.text().contains(MODEL_MARKER)
                            && !message.pending() && message.completedTs() != null)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for assistant response in session " + sessionId);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestAppConfig {
        @Bean
        @Primary
        AgentModelClientFactory agentModelClientFactory() {
            AgentModelClient client = new AgentModelClient() {
                public ModelResponse chat(List<Message> conversation, List<ToolDefinition> tools) {
                    boolean summary = conversation.stream().anyMatch(message -> message.getContent() != null
                            && message.getContent().toLowerCase(Locale.ROOT).contains("summary"));
                    return new ModelResponse(summary ? SUMMARY_MARKER : MODEL_MARKER, null, null, List.of());
                }

                public ModelResponse chatStreaming(List<Message> conversation, List<ToolDefinition> tools,
                        Consumer<String> onDelta) {
                    onDelta.accept(MODEL_MARKER);
                    return chat(conversation, tools);
                }
            };
            return new AgentModelClientFactory(null) {
                public AgentModelClient getClient(String provider) {
                    return client;
                }
            };
        }
    }
}

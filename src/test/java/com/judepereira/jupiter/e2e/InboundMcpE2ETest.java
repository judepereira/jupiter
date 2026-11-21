package com.judepereira.jupiter.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    @Test
    void tokenCanUseRealMcpStreamableHttpEndpoint(@TempDir Path tempDir) throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("home"));
        Path repository = Files.createDirectories(home.resolve("project"));
        initGitRepoWithInitialCommit(repository);
        try (RunningApp app = startApp(home, tempDir.resolve("state.db"), TestAppConfig.class);
                BrowserContext browser = newBrowserContext();) {
            Page page = browser.newPage();
            page.navigate(app.baseUrl());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Settings")).click();
            page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("Inbound MCP")).click();
            page.locator("#inbound-mcp-settings").waitFor();
            PlaywrightAssertions.assertThat(page.locator("#inbound-mcp-settings article")).hasCount(0);
            PlaywrightAssertions.assertThat(page.locator("#inbound-mcp-create-form")).not().isVisible();
            PlaywrightAssertions
                    .assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("create token")))
                    .isVisible();
            Locator createToggle = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("create token"));
            PlaywrightAssertions.assertThat(createToggle).hasAttribute("aria-expanded", "false");
            createToggle.click();
            PlaywrightAssertions.assertThat(page.locator("#inbound-mcp-create-form")).isVisible();
            Locator createForm = page.locator("#inbound-mcp-create-form form");
            createForm.locator("input[name='name']").fill("e2e client");
            createForm.locator("input[name='allProjects']").check();
            page.waitForResponse(response -> response.url().endsWith("/create")
                    && response.request().method().equals("POST") && response.status() == 200,
                    createForm.locator("button[type='submit']")::click);
            Locator issuedToken = page.locator("#inbound-mcp-settings .alert-warning code");
            issuedToken.waitFor();
            PlaywrightAssertions.assertThat(page.locator("#inbound-mcp-create-form")).not().isVisible();
            PlaywrightAssertions.assertThat(page.locator("#inbound-mcp-settings").getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Reveal"))).hasCount(0);
            PlaywrightAssertions.assertThat(page.locator("#inbound-mcp-settings").getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Rotate"))).hasCount(0);
            String token = issuedToken.textContent().trim();
            Locator credential = page.locator("#inbound-mcp-settings article")
                    .filter(new Locator.FilterOptions().setHas(page.getByRole(AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("e2e client").setExact(true))));
            Locator credentialHeader = credential.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("e2e client").setExact(true));
            PlaywrightAssertions.assertThat(credentialHeader).hasAttribute("aria-expanded", "false");
            PlaywrightAssertions.assertThat(credential.locator("input[name='name']")).hasCount(0);
            credentialHeader.click();
            PlaywrightAssertions.assertThat(credentialHeader).hasAttribute("aria-expanded", "true");
            PlaywrightAssertions.assertThat(page.locator("#inbound-mcp-settings")
                    .getByText("It cannot be viewed again:", new Locator.GetByTextOptions().setExact(false)))
                    .isVisible();
            McpHttpTestClient client = new McpHttpTestClient(app.baseUrl(), token);
            JsonNode initialized = client.initialize("e2e");
            if (initialized.path("result").path("serverInfo").isMissingNode()) {
                throw new AssertionError("MCP initialize response missing serverInfo: " + initialized);
            }
            JsonNode listed = client.tools();
            for (String tool : new String[]{"list_projects", "list_workspaces", "list_sessions", "list_agents",
                    "create_workspace", "post_message", "summarize_session"}) {
                if (!listed.toString().contains(tool)) {
                    throw new AssertionError(("MCP tool missing: " + tool));
                }
            }
            page.locator("#settings-modal .btn-close").click();
            PlaywrightAssertions.assertThat((Locator) page.locator("#settings-modal")).not().isVisible();
            openProject(page, "MCP E2E Project", repository);
            JsonNode projects = client.call("list_projects", Map.of());
            JsonNode projectResults = result(projects, "list_projects").path("result");
            if (!projectResults.isArray() || projectResults.isEmpty()) {
                throw new AssertionError((Object) ("Expected the initialized git project: " + (projects)));
            }
            long projectId = projectResults.get(0).path("id").asLong();
            JsonNode workspaces = client.call("list_workspaces", Map.of("projectId", projectId));
            if (!result(workspaces, "list_workspaces").path("result").isArray()) {
                throw new AssertionError(("Expected workspace list: " + (workspaces)));
            }
            JsonNode sessions = client.call("list_sessions", Map.of("projectId", projectId));
            if (!result(sessions, "list_sessions").path("result").isArray()) {
                throw new AssertionError(("Expected session list: " + (sessions)));
            }
            JsonNode agents = client.call("list_agents", Map.of("projectId", projectId));
            if (!result(agents, "list_agents").path("result").isArray()
                    || result(agents, "list_agents").path("result").isEmpty()) {
                throw new AssertionError(("Expected primary agents: " + (agents)));
            }
            String branch = "mcp-e2e-workspace";
            JsonNode created = client.call("create_workspace",
                    Map.of("projectId", projectId, "branchName", branch, "createBranch", true));
            JsonNode workspace = result(created, "create_workspace").path("result");
            long workspaceId = workspace.path("workspaceId").asLong();
            long sessionId = workspace.path("sessionId").asLong();
            if (workspaceId <= 0L || sessionId <= 0L || !branch.equals(workspace.path("name").asText())) {
                throw new AssertionError(("Workspace did not include its branch and initial session: " + (created)));
            }
            PlaywrightAssertions.assertThat((Locator) page.locator("#settings-modal")).not().isVisible();
            page.locator("#chat-messages-list").waitFor();
            JsonNode posted = client.call("post_message",
                    Map.of("sessionId", sessionId, "message", "deterministic MCP request"));
            if (result(posted, "post_message").path("result").path("sessionId").asLong() != sessionId) {
                throw new AssertionError((Object) ("post_message targeted an unexpected session: " + (posted)));
            }
            AppStateService appState = (AppStateService) app.context().getBean(AppStateService.class);
            waitForCompleted(appState, sessionId);
            List<?> beforeSummary = appState.loadSessionDetail(sessionId).chatMessages();
            JsonNode summary = client.call("summarize_session", Map.of("sessionId", sessionId));
            JsonNode summaryResult = result(summary, "summarize_session").path("result");
            if (!SUMMARY_MARKER.equals(summaryResult.path("summary").asText())
                    || summaryResult.path("cutoffTurnId").asLong() <= 0L) {
                throw new AssertionError(("Unexpected summary result: " + (summary)));
            }
            List<?> afterSummary = appState.loadSessionDetail(sessionId).chatMessages();
            if (!beforeSummary.equals(afterSummary)) {
                throw new AssertionError("Summarization changed persistent conversation messages");
            }
            assertPostMessage(client, appState,
                    Map.of("workspaceId", workspaceId, "message", "workspace-targeted MCP request"), projectId,
                    workspaceId, sessionId);
            String branchTarget = "mcp-e2e-post-project";
            JsonNode projectPost = client.call("post_message", Map.of("projectId", projectId, "branchName",
                    branchTarget, "createBranch", true, "message", "project-targeted MCP request"));
            JsonNode projectPostResult = result(projectPost, "post_message").path("result");
            long projectSessionId = projectPostResult.path("sessionId").asLong();
            if (projectSessionId <= 0L) {
                throw new AssertionError((Object) ("Project-targeted post did not return a session: " + (projectPost)));
            }
            waitForCompleted(appState, projectSessionId);
            JsonNode updatedWorkspaces = client.call("list_workspaces", Map.of("projectId", projectId));
            if (result(updatedWorkspaces, "list_workspaces").path("result").findValuesAsText("name").stream()
                    .noneMatch(branchTarget::equals)) {
                throw new AssertionError(("Project-targeted workspace missing from list: " + (updatedWorkspaces)));
            }
            PlaywrightAssertions.assertThat((Locator) page.locator("#settings-modal")).not().isVisible();
            Page reopened = browser.newPage();
            reopened.navigate(app.baseUrl());
            reopened.getByText(MODEL_MARKER).waitFor();
            PlaywrightAssertions
                    .assertThat((Locator) reopened
                            .locator("#chat-messages-list li[data-role='assistant'] .chat-message-text").last())
                    .containsText(MODEL_MARKER);
        }
    }

    private static void assertPostMessage(McpHttpTestClient client, AppStateService appState, Map<String, ?> arguments,
            long expectedProjectId, long expectedWorkspaceId, long previousSessionId) throws Exception {
        JsonNode response = client.call("post_message", arguments);
        JsonNode result = result(response, "post_message").path("result");
        long sessionId = result.path("sessionId").asLong();
        if (!result.path("accepted").asBoolean() || !"accepted".equals(result.path("status").asText())
                || result.path("projectId").asLong() != expectedProjectId
                || result.path("workspaceId").asLong() != expectedWorkspaceId || sessionId <= 0L
                || sessionId == previousSessionId || appState.loadSessionDetail(sessionId) == null) {
            throw new AssertionError((Object) ("post_message returned an unexpected accepted session: " + (response)));
        }
        JsonNode sessions = client.call("list_sessions", Map.of("workspaceId", expectedWorkspaceId));
        JsonNode listedSessions = result(sessions, "list_sessions").path("result");
        if (!listedSessions.isArray() || listedSessions.findValuesAsText("id").stream()
                .noneMatch(id -> id.equals(Long.toString(sessionId)))) {
            throw new AssertionError((Object) ("Expected newly accepted session in target workspace: " + (sessions)));
        }
        waitForCompleted(appState, sessionId);
    }

    private static void waitForCompleted(AppStateService appState, long sessionId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L);
        while (System.nanoTime() < deadline) {
            boolean completed = appState.loadSessionDetail(sessionId).chatMessages().stream()
                    .anyMatch(message -> "assistant".equals(message.role()) && message.text().contains(MODEL_MARKER)
                            && !message.pending() && message.completedTs() != null);
            if (completed) {
                return;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError(
                (Object) ("Timed out waiting for completed assistant response in session " + sessionId));
    }

    private static JsonNode result(JsonNode response, String tool) {
        JsonNode result = response.path("result");
        if (result.path("isError").asBoolean()) {
            throw new AssertionError((tool + " returned an MCP tool error: " + (response)));
        }
        JsonNode content = result.path("content");
        if (!content.isArray() || content.isEmpty()) {
            throw new AssertionError((tool + " returned no result content: " + (response)));
        }
        try {
            return new ObjectMapper().readTree(content.get(0).path("text").asText());
        } catch (Exception exception) {
            throw new AssertionError(tool + " returned invalid JSON content: " + (response), exception);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestAppConfig {
        @Bean
        @Primary
        AgentModelClientFactory agentModelClientFactory() {
            AgentModelClient client = new AgentModelClient() {
                public ModelResponse chat(List<Message> conversation, List<ToolDefinition> tools) {
                    boolean summaryPrompt = conversation.stream().anyMatch(message -> message.getContent() != null
                            && message.getContent().toLowerCase(Locale.ROOT).contains("summar"));
                    return new ModelResponse(summaryPrompt ? SUMMARY_MARKER : MODEL_MARKER, null, null, List.of());
                }

                public ModelResponse chatStreaming(List<Message> conversation, List<ToolDefinition> tools,
                        Consumer<String> onDelta) {
                    onDelta.accept(MODEL_MARKER);
                    return this.chat(conversation, tools);
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

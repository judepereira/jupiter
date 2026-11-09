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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

class InboundMcpE2ETest extends E2ETestSupport {
    private static final String MODEL_MARKER = "Inbound MCP deterministic response";
    private static final String SUMMARY_MARKER = "Inbound MCP deterministic summary";

    InboundMcpE2ETest() {
    }

    @Test
    void tokenCanUseRealMcpStreamableHttpEndpoint(@TempDir Path tempDir) throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("home"));
        Path repository = Files.createDirectories(home.resolve("project"));
        InboundMcpE2ETest.initGitRepoWithInitialCommit(repository);
        try (E2ETestSupport.RunningApp app = InboundMcpE2ETest.startApp(home, tempDir.resolve("state.db"),
                TestAppConfig.class); BrowserContext browser = InboundMcpE2ETest.newBrowserContext();) {
            Page page = browser.newPage();
            page.navigate(app.baseUrl());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Settings")).click();
            page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("Inbound MCP")).click();
            page.locator("#inbound-mcp-settings").waitFor();
            Locator createForm = page.locator("#inbound-mcp-settings > form");
            createForm.locator("input[name='name']").fill("e2e client");
            createForm.locator("input[name='allProjects']").check();
            createForm.locator("button[type='submit']").click();
            page.locator("#inbound-mcp-settings").getByText("e2e client").waitFor();
            page.locator("#inbound-mcp-settings article").filter(new Locator.FilterOptions().setHasText("e2e client"))
                    .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Reveal")).click();
            String revealed = page.locator("#inbound-mcp-settings").textContent();
            Matcher matcher = Pattern.compile("jup_[A-Za-z0-9_-]+").matcher(revealed);
            if (!matcher.find()) {
                throw new AssertionError((Object) "Created inbound MCP token was not revealed");
            }
            String credential = matcher.group();
            McpClient client = new McpClient(app.baseUrl() + "/mcp", credential);
            JsonNode initialized = client.request(1,
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"e2e\",\"version\":\"1\"}}}");
            if (initialized.path("result").path("serverInfo").isMissingNode()) {
                throw new AssertionError(
                        (Object) ("MCP initialize response missing serverInfo: " + String.valueOf(initialized)));
            }
            client.notifyInitialized();
            JsonNode listed = client.request(2,
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
            for (String tool : new String[]{"list_projects", "list_workspaces", "list_sessions", "list_agents",
                    "create_workspace", "post_message", "summarize_session"}) {
                if (!listed.toString().contains(tool)) {
                    throw new AssertionError((Object) ("MCP tool missing: " + tool));
                }
            }
            page.locator("#settings-modal .btn-close").click();
            PlaywrightAssertions.assertThat((Locator) page.locator("#settings-modal")).not().isVisible();
            InboundMcpE2ETest.openProject(page, "MCP E2E Project", repository);
            JsonNode projects = client.call(3, "list_projects", "{}");
            JsonNode projectResults = InboundMcpE2ETest.result(projects, "list_projects").path("result");
            if (!projectResults.isArray() || projectResults.isEmpty()) {
                throw new AssertionError(
                        (Object) ("Expected the initialized git project: " + String.valueOf(projects)));
            }
            long projectId = projectResults.get(0).path("id").asLong();
            JsonNode workspaces = client.call(4, "list_workspaces", "{\"projectId\":" + projectId + "}");
            if (!InboundMcpE2ETest.result(workspaces, "list_workspaces").path("result").isArray()) {
                throw new AssertionError((Object) ("Expected workspace list: " + String.valueOf(workspaces)));
            }
            JsonNode sessions = client.call(5, "list_sessions", "{\"projectId\":" + projectId + "}");
            if (!InboundMcpE2ETest.result(sessions, "list_sessions").path("result").isArray()) {
                throw new AssertionError((Object) ("Expected session list: " + String.valueOf(sessions)));
            }
            JsonNode agents = client.call(6, "list_agents", "{\"projectId\":" + projectId + "}");
            if (!InboundMcpE2ETest.result(agents, "list_agents").path("result").isArray()
                    || InboundMcpE2ETest.result(agents, "list_agents").path("result").isEmpty()) {
                throw new AssertionError((Object) ("Expected primary agents: " + String.valueOf(agents)));
            }
            String branch = "mcp-e2e-workspace";
            JsonNode created = client.call(7, "create_workspace",
                    "{\"projectId\":" + projectId + ",\"branchName\":\"" + branch + "\",\"createBranch\":true}");
            JsonNode workspace = InboundMcpE2ETest.result(created, "create_workspace").path("result");
            long workspaceId = workspace.path("workspaceId").asLong();
            long sessionId = workspace.path("sessionId").asLong();
            if (workspaceId <= 0L || sessionId <= 0L || !branch.equals(workspace.path("name").asText())) {
                throw new AssertionError((Object) ("Workspace did not include its branch and initial session: "
                        + String.valueOf(created)));
            }
            PlaywrightAssertions.assertThat((Locator) page.locator("#settings-modal")).not().isVisible();
            page.locator("#chat-messages-list").waitFor();
            JsonNode posted = client.call(8, "post_message",
                    "{\"sessionId\":" + sessionId + ",\"message\":\"deterministic MCP request\"}");
            if (InboundMcpE2ETest.result(posted, "post_message").path("result").path("sessionId")
                    .asLong() != sessionId) {
                throw new AssertionError(
                        (Object) ("post_message targeted an unexpected session: " + String.valueOf(posted)));
            }
            AppStateService appState = (AppStateService) app.context().getBean(AppStateService.class);
            InboundMcpE2ETest.waitForCompleted(appState, sessionId);
            List beforeSummary = appState.loadSessionDetail(sessionId).chatMessages();
            JsonNode summary = client.call(9, "summarize_session", "{\"sessionId\":" + sessionId + "}");
            JsonNode summaryResult = InboundMcpE2ETest.result(summary, "summarize_session").path("result");
            if (!SUMMARY_MARKER.equals(summaryResult.path("summary").asText())
                    || summaryResult.path("cutoffTurnId").asLong() <= 0L) {
                throw new AssertionError((Object) ("Unexpected summary result: " + String.valueOf(summary)));
            }
            List afterSummary = appState.loadSessionDetail(sessionId).chatMessages();
            if (!beforeSummary.equals(afterSummary)) {
                throw new AssertionError((Object) "Summarization changed persistent conversation messages");
            }
            InboundMcpE2ETest.assertPostMessage(client, appState,
                    "{\"workspaceId\":" + workspaceId + ",\"message\":\"workspace-targeted MCP request\"}", projectId,
                    workspaceId, sessionId, 10);
            String branchTarget = "mcp-e2e-post-project";
            JsonNode projectPost = client.call(11, "post_message", "{\"projectId\":" + projectId + ",\"branchName\":\""
                    + branchTarget + "\",\"createBranch\":true,\"message\":\"project-targeted MCP request\"}");
            JsonNode projectPostResult = InboundMcpE2ETest.result(projectPost, "post_message").path("result");
            long projectSessionId = projectPostResult.path("sessionId").asLong();
            if (projectSessionId <= 0L) {
                throw new AssertionError(
                        (Object) ("Project-targeted post did not return a session: " + String.valueOf(projectPost)));
            }
            InboundMcpE2ETest.waitForCompleted(appState, projectSessionId);
            JsonNode updatedWorkspaces = client.call(12, "list_workspaces", "{\"projectId\":" + projectId + "}");
            if (InboundMcpE2ETest.result(updatedWorkspaces, "list_workspaces").path("result").findValuesAsText("name")
                    .stream().noneMatch(branchTarget::equals)) {
                throw new AssertionError((Object) ("Project-targeted workspace missing from list: "
                        + String.valueOf(updatedWorkspaces)));
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

    private static void assertPostMessage(McpClient client, AppStateService appState, String arguments,
            long expectedProjectId, long expectedWorkspaceId, long previousSessionId, int requestId) throws Exception {
        JsonNode response = client.call(requestId, "post_message", arguments);
        JsonNode result = InboundMcpE2ETest.result(response, "post_message").path("result");
        long sessionId = result.path("sessionId").asLong();
        if (!result.path("accepted").asBoolean() || !"accepted".equals(result.path("status").asText())
                || result.path("projectId").asLong() != expectedProjectId
                || result.path("workspaceId").asLong() != expectedWorkspaceId || sessionId <= 0L
                || sessionId == previousSessionId || appState.loadSessionDetail(sessionId) == null) {
            throw new AssertionError(
                    (Object) ("post_message returned an unexpected accepted session: " + String.valueOf(response)));
        }
        JsonNode sessions = client.call(requestId + 1, "list_sessions",
                "{\"workspaceId\":" + expectedWorkspaceId + "}");
        JsonNode listedSessions = InboundMcpE2ETest.result(sessions, "list_sessions").path("result");
        if (!listedSessions.isArray() || listedSessions.findValuesAsText("id").stream()
                .noneMatch(id -> id.equals(Long.toString(sessionId)))) {
            throw new AssertionError(
                    (Object) ("Expected newly accepted session in target workspace: " + String.valueOf(sessions)));
        }
        InboundMcpE2ETest.waitForCompleted(appState, sessionId);
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
            throw new AssertionError((Object) (tool + " returned an MCP tool error: " + String.valueOf(response)));
        }
        JsonNode content = result.path("content");
        if (!content.isArray() || content.isEmpty()) {
            throw new AssertionError((Object) (tool + " returned no result content: " + String.valueOf(response)));
        }
        try {
            return new ObjectMapper().readTree(content.get(0).path("text").asText());
        } catch (Exception exception) {
            throw new AssertionError(tool + " returned invalid JSON content: " + String.valueOf(response), exception);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestAppConfig {
        TestAppConfig() {
        }

        @Bean
        @Primary
        AgentModelClientFactory agentModelClientFactory() {
            AgentModelClient client = new AgentModelClient() {
                public ModelResponse chat(List<Message> conversation, List<ToolDefinition> tools) {
                    boolean summaryPrompt = conversation.stream().anyMatch(message -> message.getContent() != null
                            && message.getContent().toLowerCase().contains("summar"));
                    return new ModelResponse(
                            summaryPrompt ? InboundMcpE2ETest.SUMMARY_MARKER : InboundMcpE2ETest.MODEL_MARKER, null,
                            null, List.of());
                }

                public ModelResponse chatStreaming(List<Message> conversation, List<ToolDefinition> tools,
                        Consumer<String> onDelta) {
                    onDelta.accept(InboundMcpE2ETest.MODEL_MARKER);
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

    private static final class McpClient {
        private static final ObjectMapper JSON = new ObjectMapper();
        private final HttpClient http = HttpClient.newHttpClient();
        private final String url;
        private final String token;
        private String sessionId;

        private McpClient(String url, String token) {
            this.url = url;
            this.token = token;
        }

        private JsonNode request(int id, String json) throws Exception {
            return this.send(json);
        }

        private JsonNode call(int id, String name, String arguments) throws Exception {
            return this.request(id,
                    "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + name
                            + "\",\"arguments\":" + arguments + "}}");
        }

        private void notifyInitialized() throws Exception {
            this.send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        }

        private JsonNode send(String json) throws Exception {
            HttpResponse<String> response;
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(this.url))
                    .header("Authorization", "Bearer " + this.token).header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .header("MCP-Protocol-Version", "2025-03-26");
            if (this.sessionId != null) {
                builder.header("Mcp-Session-Id", this.sessionId);
            }
            if ((response = this.http.send(builder.POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                    HttpResponse.BodyHandlers.ofString())).statusCode() < 200 || response.statusCode() >= 300) {
                throw new AssertionError(
                        (Object) ("MCP request failed with " + response.statusCode() + ": " + response.body()));
            }
            this.sessionId = response.headers().firstValue("Mcp-Session-Id").orElse(this.sessionId);
            String body = response.body();
            String data = body.lines().filter(line -> line.startsWith("data:")).map(line -> line.substring(5).trim())
                    .findFirst().orElse(body);
            return data.isBlank() ? JSON.createObjectNode() : JSON.readTree(data);
        }
    }
}

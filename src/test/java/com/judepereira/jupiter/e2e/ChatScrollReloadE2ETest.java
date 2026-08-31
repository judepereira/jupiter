package com.judepereira.jupiter.e2e;

import com.judepereira.jupiter.agent.catalog.AgentDefinitionService;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.Persistence.ToolCallTraceInput;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatScrollReloadE2ETest extends E2ETestSupport {

    private static final int CHAT_MESSAGE_COUNT = 36;
    private static final int LINES_PER_MESSAGE = 6;
    private static final String PRIMARY_MARKER = "Primary mixed-content final entry";
    private static final String SECONDARY_MARKER = "Secondary mixed-content final entry";

    @Test
    void mixedPrimaryHistoryStaysAtBottomAcrossSessionWorkspaceSwitchesAndReload(@TempDir Path tempDir) throws Exception {
        Path fakeHome = Files.createDirectories(tempDir.resolve("fake-home"));
        Path projectDir = Files.createDirectories(fakeHome.resolve("child-project"));
        initGitRepoWithInitialCommit(projectDir);
        Path sqliteDbFile = tempDir.resolve("sqlite-db/jupiter.db");
        Files.createDirectories(sqliteDbFile.getParent());

        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());

        try (Playwright playwright = Playwright.create(); Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
             RunningApp app = startApp(fakeHome, sqliteDbFile);
             BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            page.navigate(app.baseUrl());
            page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("New tab")).waitFor();
            openProject(page, "Alpha", projectDir);

            AppStateService state = app.context().getBean(AppStateService.class);
            AgentDefinitionService agents = app.context().getBean(AgentDefinitionService.class);
            JdbcTemplate jdbc = app.context().getBean(JdbcTemplate.class);
            long primarySessionId = state.loadViewData().activeSession().id();
            insertMixedChatHistory(jdbc, state, agents, primarySessionId, PRIMARY_MARKER, "Primary");
            long primaryWorkspaceId = state.loadViewData().activeWorkspace().id();

            page.reload();
            page.waitForLoadState();
            assertMixedHistoryAtBottom(page, PRIMARY_MARKER);

            page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("New session")).click();
            assertThat(page.locator("#session-name-input")).isVisible();
            page.locator("#session-name-input").fill("Secondary");
            page.waitForResponse(response -> response.url().contains("/ui/sessions/add") && response.status() == 200,
                    () -> page.locator("[data-session-create-form]").evaluate("form => form.requestSubmit()"));
            long secondarySessionId = state.loadViewData().activeSession().id();
            insertMixedChatHistory(jdbc, state, agents, secondarySessionId, SECONDARY_MARKER, "Secondary");
            jdbc.queryForList("SELECT id, tool_name, machine_summary_json FROM tool_call_traces WHERE session_id = ?", secondarySessionId);

            page.waitForResponse(response -> response.url().contains("/ui/sessions/" + primarySessionId + "/activate") && response.status() == 200,
                    () -> sessionRow(page, "Session #1").locator(".session-item").click());
            assertMixedHistoryAtBottom(page, PRIMARY_MARKER);

            page.waitForResponse(response -> response.url().contains("/ui/sessions/" + secondarySessionId + "/activate") && response.status() == 200,
                    () -> sessionRow(page, "Secondary").locator(".session-item").click());
            assertMixedHistoryAtBottom(page, SECONDARY_MARKER);

            page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("New Workspace")).click();
            assertThat(page.locator("#workspace-modal")).isVisible();
            page.locator("input[name='branchName']").fill("scroll-workspace");
            page.waitForResponse(response -> response.url().contains("/ui/workspaces/add") && response.status() == 200,
                    () -> page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Create workspace")).click());
            Locator workspace = page.locator(".workspace-group").filter(new Locator.FilterOptions().setHasText("scroll-workspace"));
            workspace.locator(".workspace-item").waitFor();
            assertThat(page.locator(".workspace-group.active .workspace-label")).hasText("scroll-workspace");
            long workspaceSessionId = state.loadViewData().activeSession().id();
            insertMixedChatHistory(jdbc, state, agents, workspaceSessionId, "Workspace mixed-content final entry", "Workspace");

            Locator defaultWorkspace = page.locator(".workspace-group").filter(new Locator.FilterOptions().setHasText("Default Workspace"));
            page.waitForResponse(response -> response.url().contains("/ui/workspaces/" + primaryWorkspaceId + "/activate") && response.status() == 200,
                    () -> defaultWorkspace.locator(".workspace-item").click());
            assertThat(page.locator(".workspace-group.active .workspace-label")).hasText("Default Workspace");
            assertMixedHistoryAtBottom(page, SECONDARY_MARKER);
            page.waitForResponse(response -> response.url().contains("/ui/sessions/" + primarySessionId + "/activate") && response.status() == 200,
                    () -> sessionRow(page, "Session #1").locator(".session-item").click());
            assertMixedHistoryAtBottom(page, PRIMARY_MARKER);

            page.waitForResponse(response -> response.url().contains("/ui/workspaces/") && response.url().contains("/activate") && response.status() == 200,
                    () -> workspace.locator(".workspace-item").click());
            assertThat(page.locator(".workspace-group.active .workspace-label")).hasText("scroll-workspace");
            assertMixedHistoryAtBottom(page, "Workspace mixed-content final entry");
            page.reload();
            page.waitForLoadState();
            assertMixedHistoryAtBottom(page, "Workspace mixed-content final entry");

        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    private static Locator sessionRow(Page page, String name) {
        return page.locator(".session-row").filter(new Locator.FilterOptions().setHasText(name));
    }

    private static void assertMixedHistoryAtBottom(Page page, String marker) {
        assertThat(page.locator("#chat-messages-list")).containsText(marker);
        page.waitForFunction("""
                async () => {
                    const history = document.getElementById('chat-history');
                    const list = document.getElementById('chat-messages-list');
                    if (!history || !list || history.scrollHeight <= history.clientHeight) return false;
                    let previousSignature = '';
                    let stableFrames = 0;
                    for (let frame = 0; frame < 60; frame++) {
                        await new Promise(resolve => requestAnimationFrame(resolve));
                        if (!history.isConnected || !list.isConnected || history.scrollHeight <= history.clientHeight) return false;
                        const signature = [history.scrollHeight, history.clientHeight, list.getBoundingClientRect().height].join(':');
                        stableFrames = signature === previousSignature ? stableFrames + 1 : 0;
                        previousSignature = signature;
                        if (stableFrames >= 3) return true;
                    }
                    return false;
                }
                """);
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) page.evaluate("""
                () => {
                    const history = document.getElementById('chat-history');
                    return {scrollTop: history.scrollTop, max: history.scrollHeight - history.clientHeight};
                }
                """);
        double scrollTop = ((Number) metrics.get("scrollTop")).doubleValue();
        double max = ((Number) metrics.get("max")).doubleValue();
        assertTrue(max > 200, "mixed chat history should overflow materially");
        assertEquals(max, scrollTop, 1.0, "chat history should be scrolled to its bottom after transition");
    }

    private static void insertMixedChatHistory(JdbcTemplate jdbcTemplate, AppStateService state, AgentDefinitionService agents, long sessionId, String marker, String label) {
        Instant createdAt = Instant.now();
        for (int sequence = 1; sequence <= CHAT_MESSAGE_COUNT; sequence++) {
            String content = label + " mixed entry " + sequence + "\n" + ("layout-shifting text ".repeat(18)) + "\n" + marker;
            String role = sequence % 3 == 0 ? "assistant" : sequence % 3 == 1 ? "user" : "assistant";
            boolean taskCall = role.equals("assistant") && sequence % 6 == 0;
            String toolCallId = label.toLowerCase() + "-tool-" + sequence;
            String toolCalls = role.equals("assistant") && sequence % 3 == 0
                    ? "[{\"toolCallId\":\"" + toolCallId + "\",\"toolName\":\"" + (taskCall ? "task" : "search") + "\",\"arguments\":"
                    + (taskCall ? "{\"agentId\":\"explore\",\"requestSummary\":\"" + label + " delegated review\",\"task\":\"Inspect " + label + " history\",\"expectedOutput\":\"delegated result\"}" : "{\"query\":\"" + label + " query\"}") + "}]"
                    : null;
            jdbcTemplate.update(
                    "INSERT INTO conversation_messages (session_id, public_id, role, turn_id, sequence, content, tool_calls_json, show_in_chat, include_in_model, pending, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, 1, 1, 0, ?)",
                    sessionId, UUID.randomUUID().toString(), role, (sequence + 1) / 2, sequence, content, toolCalls, Timestamp.from(createdAt.plusMillis(sequence)));
            if (toolCalls != null) {
                String assistantPublicId = jdbcTemplate.queryForObject("SELECT public_id FROM conversation_messages WHERE session_id = ? AND sequence = ?", String.class, sessionId, sequence);
                String toolName = taskCall ? "task" : "search";
                String argsJson = taskCall
                        ? "{\"agentId\":\"explore\",\"requestSummary\":\"" + label + " delegated review\",\"task\":\"Inspect " + label + " history\",\"expectedOutput\":\"delegated result\"}"
                        : "{\"query\":\"" + label + " query\"}";
                long childSessionId = taskCall ? subagentFixture(jdbcTemplate, state, agents, sessionId, toolCallId, label) : 0;
                String machineJson = taskCall
                        ? "{\"subagentSessionId\":" + childSessionId + ",\"subagentAgentId\":\"explore\",\"subagentAgentName\":\"Explore\"}"
                        : "{}";
                jdbcTemplate.update(
                        "INSERT INTO tool_call_traces (session_id, assistant_message_id, sequence, tool_call_id, tool_name, success, args_json, text_summary, machine_summary_json, completed_at, created_at) SELECT ?, id, ?, ?, ?, 1, ?, ?, ?, ?, ? FROM conversation_messages WHERE public_id = ?",
                        sessionId, sequence, toolCallId, toolName, argsJson,
                        taskCall ? "delegated result " + label : "result " + label + " content", machineJson,
                        Timestamp.from(createdAt.plusMillis(sequence)), Timestamp.from(createdAt.plusMillis(sequence)), assistantPublicId);
            }
        }
    }

    private static long subagentFixture(JdbcTemplate jdbc, AppStateService state, AgentDefinitionService agents,
                                         long parentSessionId, String toolCallId, String label) {
        long childSessionId = state.createHiddenSubagentSession(parentSessionId, toolCallId, agents.getRequired("explore"));
        String assistantId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO conversation_messages (session_id, public_id, role, turn_id, sequence, content, show_in_chat, include_in_model, pending, created_at) VALUES (?, ?, 'assistant', 1, 2, ?, 1, 1, 0, ?)",
                childSessionId, assistantId, "Explore subagent result for " + label, Timestamp.from(Instant.now()));
        return childSessionId;
    }

}

package com.judepereira.jupiter.e2e;

import com.judepereira.jupiter.persistence.AppStateService;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
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

    @Test
    void fullReloadKeepsLongActiveChatHistoryAtBottom(@TempDir Path tempDir) throws Exception {
        Path fakeHome = Files.createDirectories(tempDir.resolve("fake-home"));
        Path projectDir = Files.createDirectories(fakeHome.resolve("child-project"));
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

            long sessionId = app.context().getBean(AppStateService.class).loadViewData().activeSession().id();
            insertLongChatHistory(app.context().getBean(JdbcTemplate.class), sessionId);

            page.reload();
            page.waitForLoadState();
            assertThat(page.locator("#chat-messages-list > li")).hasCount(CHAT_MESSAGE_COUNT + 1);
            page.waitForFunction("() => document.readyState === 'complete' && document.fonts.status === 'loaded'"
                    + " && document.querySelector('#chat-history').scrollHeight > document.querySelector('#chat-history').clientHeight");
            page.waitForFunction("""
                    () => {
                        const history = document.querySelector('#chat-history');
                        const list = document.querySelector('#chat-messages-list');
                        if (!history || !list) return false;
                        const signature = [history.scrollHeight, history.clientHeight, history.scrollTop,
                            list.getBoundingClientRect().height].join(':');
                        const previous = window.__chatScrollLayoutSignature;
                        const stableFrames = previous === signature
                                ? (window.__chatScrollStableFrames || 0) + 1
                                : 0;
                        window.__chatScrollLayoutSignature = signature;
                        window.__chatScrollStableFrames = stableFrames;
                        return stableFrames >= 3;
                    }
                    """);

            @SuppressWarnings("unchecked")
            Map<String, Object> scrollMetrics = (Map<String, Object>) page.evaluate("""
                    () => {
                        const history = document.getElementById('chat-history');
                        return {
                            scrollTop: history.scrollTop,
                            scrollHeight: history.scrollHeight,
                            clientHeight: history.clientHeight
                        };
                    }
                    """);
            double scrollTop = ((Number) scrollMetrics.get("scrollTop")).doubleValue();
            double maxScrollTop = ((Number) scrollMetrics.get("scrollHeight")).doubleValue()
                    - ((Number) scrollMetrics.get("clientHeight")).doubleValue();
            assertTrue(maxScrollTop > 0, "chat history should overflow");
            assertEquals(maxScrollTop, scrollTop, 1.0, "chat history should be scrolled to its bottom");
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    private static void insertLongChatHistory(JdbcTemplate jdbcTemplate, long sessionId) {
        Instant createdAt = Instant.now();
        for (int sequence = 1; sequence <= CHAT_MESSAGE_COUNT; sequence++) {
            StringBuilder content = new StringBuilder("Reload scroll entry ").append(sequence);
            for (int line = 1; line <= LINES_PER_MESSAGE; line++) {
                content.append('\n').append("Deterministic line ").append(line).append(" for entry ").append(sequence);
            }
            jdbcTemplate.update(
                    "INSERT INTO conversation_messages (session_id, public_id, role, turn_id, sequence, content, show_in_chat, include_in_model, pending, created_at) VALUES (?, ?, ?, ?, ?, ?, 1, 1, 0, ?)",
                    sessionId,
                    UUID.randomUUID().toString(),
                    sequence % 2 == 0 ? "assistant" : "user",
                    (sequence + 1) / 2,
                    sequence,
                    content.toString(),
                    Timestamp.from(createdAt.plusMillis(sequence)));
        }
    }

}

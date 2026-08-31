package com.judepereira.jupiter.e2e;

import com.judepereira.jupiter.agent.harness.AgentTurnRequest;
import com.judepereira.jupiter.agent.harness.AgentTurnResult;
import com.judepereira.jupiter.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter.agent.llm.AgentStreamListener;
import com.judepereira.jupiter.persistence.AppStateService;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InactiveSessionUnreadRailE2ETest extends E2ETestSupport {

    private static final String FAILURE_TEXT = "Assistant stream failed because the process ended before this response could be completed. Restart the request to continue.";

    @Test
    void inactiveSessionUnreadDotUpdatesLiveAndClearsOnActivate(@TempDir Path tempDir) throws Exception {
        TestAppConfig.reset();
        TestAppConfig.blockPrimaryTurn();

        Path fakeHome = Files.createDirectories(tempDir.resolve("fake-home"));
        Path projectDir = Files.createDirectories(fakeHome.resolve("child-project"));
        Path sqliteDbFile = tempDir.resolve("sqlite-db/jupiter.db");
        Files.createDirectories(sqliteDbFile.getParent());

        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());

        try (RunningApp app = startApp(fakeHome, sqliteDbFile, TestAppConfig.class);
             BrowserContext context = newBrowserContext()) {

            Page page = context.newPage();
            page.navigate(app.baseUrl());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).waitFor();

            openProject(page, "Alpha", projectDir);

            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New session")).click();
            assertThat(page.locator("#session-name-input")).isVisible();
            page.locator("#session-name-input").fill("Session #2");
            page.waitForResponse(
                    response -> response.url().contains("/ui/sessions/add") && response.status() == 200,
                    () -> page.locator("[data-session-create-form]").evaluate("form => form.requestSubmit()"));

            assertThat(page.locator(".session-row")).hasCount(2);
            Locator sessionOneRow = page.locator(".session-row").filter(new Locator.FilterOptions().setHasText("Session #1"));
            Locator sessionTwoRow = page.locator(".session-row").filter(new Locator.FilterOptions().setHasText("Session #2"));
            assertThat(page.locator(".session-item.active .session-label")).hasText("Session #2");

            page.waitForResponse(
                    response -> response.url().contains("/ui/sessions/") && response.url().contains("/activate") && response.status() == 200,
                    () -> sessionOneRow.locator(".session-item").click());
            assertThat(page.locator(".session-item.active .session-label")).hasText("Session #1");

            page.locator("#chat-input").fill("please block the assistant turn");
            page.locator("#chat-send-btn").click();

            TestAppConfig.awaitPrimaryStarted();
            page.locator("#chat-messages-list > li.pending").waitFor();
            assertThat(sessionOneRow.locator(".pending-dot")).hasCount(1);
            assertThat(sessionOneRow.locator(".failed-dot")).hasCount(0);

            page.waitForResponse(
                    response -> response.url().contains("/ui/sessions/") && response.url().contains("/activate") && response.status() == 200,
                    () -> sessionTwoRow.locator(".session-item").click());
            assertThat(page.locator(".session-item.active .session-label")).hasText("Session #2");
            assertThat(sessionOneRow.locator(".pending-dot")).hasCount(1);
            assertThat(sessionOneRow.locator(".unread-dot")).hasCount(0);

            TestAppConfig.releasePrimaryTurn();
            TestAppConfig.awaitPrimaryCompleted();

            assertThat(sessionOneRow.locator(".pending-dot")).hasCount(0);
            assertThat(sessionOneRow.locator(".unread-dot")).hasCount(1);

            page.waitForResponse(
                    response -> response.url().contains("/ui/sessions/") && response.url().contains("/activate") && response.status() == 200,
                    () -> sessionOneRow.locator(".session-item").click());
            assertThat(page.locator(".session-item.active .session-label")).hasText("Session #1");
            assertThat(sessionOneRow.locator(".unread-dot")).hasCount(0);
        } finally {
            TestAppConfig.reset();
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    @Test
    void restartingWithPendingAssistantShowsFailedRailAndSyntheticFailureMessage(@TempDir Path tempDir) throws Exception {
        TestAppConfig.reset();

        Path fakeHome = Files.createDirectories(tempDir.resolve("fake-home"));
        Path projectDir = Files.createDirectories(fakeHome.resolve("child-project"));
        Path sqliteDbFile = tempDir.resolve("sqlite-db/jupiter.db");
        Files.createDirectories(sqliteDbFile.getParent());

        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());

        try {
            long sessionId;
            try (RunningApp first = startApp(fakeHome, sqliteDbFile, TestAppConfig.class);
                 BrowserContext context = newBrowserContext()) {
                Page page = context.newPage();
                page.navigate(first.baseUrl());
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).waitFor();

                openProject(page, "Alpha", projectDir);
                sessionId = first.context().getBean(AppStateService.class).loadViewData().activeSession().id();
                insertPendingAssistant(first.context().getBean(JdbcTemplate.class), sessionId);
            }

            try (RunningApp second = startApp(fakeHome, sqliteDbFile, TestAppConfig.class);
                 BrowserContext context = newBrowserContext()) {
                Page page = context.newPage();
                page.navigate(second.baseUrl());
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).waitFor();

                assertThat(page.locator(".session-item.active .failed-dot")).hasCount(1);
                assertThat(page.locator(".session-item.active .pending-dot")).hasCount(0);
                assertThat(page.locator("#chat-messages-list > li.pending")).hasCount(0);
                assertThat(page.locator("#chat-messages-list > li[data-stream-url]")).hasCount(0);
                assertThat(page.locator("#chat-messages-list .chat-message-text em")).hasText(FAILURE_TEXT);
                org.assertj.core.api.Assertions.assertThat(page.locator("#chat-container").innerText())
                        .doesNotContain("no_job", "[Error: no_job]");
            }
        } finally {
            TestAppConfig.reset();
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    @Test
    void olderPendingAssistantDoesNotDriveRailWhenLaterVisibleAssistantIsComplete(@TempDir Path tempDir) throws Exception {
        TestAppConfig.reset();

        Path fakeHome = Files.createDirectories(tempDir.resolve("fake-home"));
        Path projectDir = Files.createDirectories(fakeHome.resolve("child-project"));
        Path sqliteDbFile = tempDir.resolve("sqlite-db/jupiter.db");
        Files.createDirectories(sqliteDbFile.getParent());

        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());

        try (RunningApp app = startApp(fakeHome, sqliteDbFile, TestAppConfig.class);
             BrowserContext context = newBrowserContext()) {

            Page page = context.newPage();
            page.navigate(app.baseUrl());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).waitFor();

            openProject(page, "Alpha", projectDir);
            long sessionId = app.context().getBean(AppStateService.class).loadViewData().activeSession().id();
            JdbcTemplate jdbcTemplate = app.context().getBean(JdbcTemplate.class);
            insertAssistantMessage(jdbcTemplate, sessionId, true, "older pending");
            insertAssistantMessage(jdbcTemplate, sessionId, false, "later complete");

            page.reload();
            assertThat(page.locator(".session-item.active .pending-dot")).hasCount(0);
            assertThat(page.locator(".session-item.active .failed-dot")).hasCount(0);
            assertThat(page.locator("#chat-messages-list")).containsText("later complete");
            assertThat(page.locator("#chat-messages-list > li.pending")).hasCount(0);
            org.assertj.core.api.Assertions.assertThat(page.locator("#chat-container").innerText())
                    .doesNotContain("no_job", "[Error: no_job]");
        } finally {
            TestAppConfig.reset();
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    private static void insertPendingAssistant(JdbcTemplate jdbcTemplate, long sessionId) {
        insertAssistantMessage(jdbcTemplate, sessionId, true, "Thinking…");
    }

    private static void insertAssistantMessage(JdbcTemplate jdbcTemplate, long sessionId, boolean pending, String content) {
        long turnId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(turn_id), 0) + 1 FROM conversation_messages WHERE session_id = ?",
                Long.class,
                sessionId);
        long sequence = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(sequence), 0) + 1 FROM conversation_messages WHERE session_id = ?",
                Long.class,
                sessionId);
        jdbcTemplate.update(
                "INSERT INTO conversation_messages (session_id, public_id, role, turn_id, sequence, content, show_in_chat, include_in_model, pending, created_at) VALUES (?, ?, 'assistant', ?, ?, ?, 1, ?, ?, ?)",
                sessionId,
                UUID.randomUUID().toString(),
                turnId,
                sequence,
                content,
                pending ? 0 : 1,
                pending,
                Timestamp.from(Instant.now()));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestAppConfig {

        private static TurnControl primaryTurnControl;

        static void reset() {
            primaryTurnControl = null;
        }

        static void blockPrimaryTurn() {
            primaryTurnControl = new TurnControl();
        }

        static void releasePrimaryTurn() {
            primaryTurnControl.release.countDown();
        }

        static void awaitPrimaryStarted() throws InterruptedException {
            assertTrue(primaryTurnControl.started.await(5, TimeUnit.SECONDS), "primary turn did not start");
        }

        static void awaitPrimaryCompleted() throws InterruptedException {
            assertTrue(primaryTurnControl.completed.await(5, TimeUnit.SECONDS), "primary turn did not complete");
        }

        @Bean
        @Primary
        CodingAgentHarness codingAgentHarness() {
            return new TestCodingAgentHarness();
        }

        private static final class TurnControl {
            private final CountDownLatch started = new CountDownLatch(1);
            private final CountDownLatch release = new CountDownLatch(1);
            private final CountDownLatch completed = new CountDownLatch(1);
        }

        static class TestCodingAgentHarness extends CodingAgentHarness {

            TestCodingAgentHarness() {
                super(null, null, null);
            }

            @Override
            public AgentTurnResult runTurnStreaming(AgentTurnRequest request, AgentStreamListener listener) {
                listener.onTextDelta("Primary task running");
                TurnControl control = primaryTurnControl;
                if (control != null) {
                    control.started.countDown();
                    awaitRelease(control.release);
                }

                AgentTurnResult result = new AgentTurnResult("Deterministic assistant reply", List.of());
                listener.onComplete(result);
                if (control != null) {
                    control.completed.countDown();
                }
                return result;
            }

            private static void awaitRelease(CountDownLatch release) {
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS), "turn release timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        }
    }
}

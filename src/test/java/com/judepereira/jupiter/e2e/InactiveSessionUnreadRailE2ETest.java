package com.judepereira.jupiter.e2e;

import com.judepereira.jupiter.agent.harness.AgentTurnRequest;
import com.judepereira.jupiter.agent.harness.AgentTurnResult;
import com.judepereira.jupiter.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter.agent.llm.AgentStreamListener;
import com.judepereira.jupiter.persistence.AppStateService;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
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

        try (Playwright playwright = Playwright.create(); Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
             RunningApp app = startApp(fakeHome, sqliteDbFile, TestAppConfig.class);
             BrowserContext context = browser.newContext()) {

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
    void inactiveSessionStreamDoesNotMoveScrolledActiveSession(@TempDir Path tempDir) throws Exception {
        TestAppConfig.reset();
        TestAppConfig.blockPrimaryTurnUntilDeltaRelease();

        Path fakeHome = Files.createDirectories(tempDir.resolve("fake-home"));
        Path projectDir = Files.createDirectories(fakeHome.resolve("child-project"));
        Path sqliteDbFile = tempDir.resolve("sqlite-db/jupiter.db");
        Files.createDirectories(sqliteDbFile.getParent());

        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());

        try (Playwright playwright = Playwright.create(); Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
             RunningApp app = startApp(fakeHome, sqliteDbFile, TestAppConfig.class);
             BrowserContext context = browser.newContext()) {

            Page page = context.newPage();
            page.navigate(app.baseUrl());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).waitFor();

            openProject(page, "Alpha", projectDir);
            long sessionOneId = app.context().getBean(AppStateService.class).loadViewData().activeSession().id();

            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New session")).click();
            assertThat(page.locator("#session-name-input")).isVisible();
            page.locator("#session-name-input").fill("Session #2");
            page.waitForResponse(
                    response -> response.url().contains("/ui/sessions/add") && response.status() == 200,
                    () -> page.locator("[data-session-create-form]").evaluate("form => form.requestSubmit()"));

            long sessionTwoId = app.context().getBean(AppStateService.class).loadViewData().activeSession().id();
            JdbcTemplate jdbcTemplate = app.context().getBean(JdbcTemplate.class);
            for (int i = 1; i <= 16; i++) {
                insertAssistantMessage(jdbcTemplate, sessionTwoId, false,
                        "Completed history entry " + i + " - " + "overflow content ".repeat(12));
            }

            page.reload();
            assertThat(page.locator(".session-item.active .session-label")).hasText("Session #2");
            page.waitForFunction("() => { const history = document.getElementById('chat-history'); return history && history.scrollHeight > history.clientHeight + 100; }");

            Locator sessionOneRow = page.locator(".session-row").filter(new Locator.FilterOptions().setHasText("Session #1"));
            Locator sessionTwoRow = page.locator(".session-row").filter(new Locator.FilterOptions().setHasText("Session #2"));
            page.waitForResponse(
                    response -> response.url().contains("/ui/sessions/" + sessionOneId + "/activate") && response.status() == 200,
                    () -> sessionOneRow.locator(".session-item").click());
            assertThat(page.locator(".session-item.active .session-label")).hasText("Session #1");

            page.evaluate("""
                    () => {
                        const NativeEventSource = window.EventSource;
                        window.__inactiveStreamDeltaSettled = false;
                        window.EventSource = class extends NativeEventSource {
                            addEventListener(type, listener, options) {
                                if (type === 'delta') {
                                    return super.addEventListener(type, event => {
                                        listener(event);
                                        requestAnimationFrame(() => requestAnimationFrame(() => {
                                            window.__inactiveStreamDeltaSettled = true;
                                        }));
                                    }, options);
                                }
                                return super.addEventListener(type, listener, options);
                            }
                        };
                    }
                    """);
            page.locator("#chat-input").fill("start the inactive stream");
            page.locator("#chat-send-btn").click();
            TestAppConfig.awaitPrimaryStarted();
            page.locator("#chat-messages-list > li.pending").waitFor();

            page.waitForResponse(
                    response -> response.url().contains("/ui/sessions/" + sessionTwoId + "/activate") && response.status() == 200,
                    () -> sessionTwoRow.locator(".session-item").click());
            assertThat(page.locator(".session-item.active .session-label")).hasText("Session #2");
            page.waitForFunction("() => { const history = document.getElementById('chat-history'); return history && history.scrollHeight - history.clientHeight > 200; }");
            page.locator("#chat-history").evaluate("""
                    async history => {
                        const nextFrame = () => new Promise(resolve => requestAnimationFrame(resolve));
                        await nextFrame();
                        await nextFrame();
                        await nextFrame();

                        const maxScrollTop = history.scrollHeight - history.clientHeight;
                        if (maxScrollTop <= 200) throw new Error('chat history is not scrollable enough');
                        const targetScrollTop = maxScrollTop / 2;
                        history.scrollTop = targetScrollTop;
                        const actualScrollTop = history.scrollTop;
                        if (Math.abs(actualScrollTop - targetScrollTop) > 1) {
                            throw new Error(`chat history did not accept manual scroll: ${actualScrollTop} != ${targetScrollTop}`);
                        }
                    }
                    """);

            double beforeScrollTop = scrollTop(page);
            double beforeBottomOffset = bottomOffset(page);
            assertTrue(beforeBottomOffset > 100, "Session #2 should be manually scrolled away from the bottom");

            TestAppConfig.releasePrimaryTurn();
            TestAppConfig.awaitPrimaryDelta();
            page.waitForFunction("() => window.__inactiveStreamDeltaSettled === true");

            double afterScrollTop = scrollTop(page);
            double afterBottomOffset = bottomOffset(page);
            assertTrue(Math.abs(afterScrollTop - beforeScrollTop) <= 2,
                    "inactive stream moved Session #2 scrollTop from " + beforeScrollTop + " to " + afterScrollTop);
            assertTrue(afterBottomOffset > 100, "Session #2 should remain materially below the bottom");
        } finally {
            TestAppConfig.releasePrimaryTurn();
            TestAppConfig.reset();
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    private static double scrollTop(Page page) {
        return ((Number) page.locator("#chat-history").evaluate("history => history.scrollTop")).doubleValue();
    }

    private static double bottomOffset(Page page) {
        return ((Number) page.locator("#chat-history").evaluate("history => history.scrollHeight - history.clientHeight - history.scrollTop")).doubleValue();
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

        try (Playwright playwright = Playwright.create(); Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            long sessionId;
            try (RunningApp first = startApp(fakeHome, sqliteDbFile, TestAppConfig.class);
                 BrowserContext context = browser.newContext()) {
                Page page = context.newPage();
                page.navigate(first.baseUrl());
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).waitFor();

                openProject(page, "Alpha", projectDir);
                sessionId = first.context().getBean(AppStateService.class).loadViewData().activeSession().id();
                insertPendingAssistant(first.context().getBean(JdbcTemplate.class), sessionId);
            }

            try (RunningApp second = startApp(fakeHome, sqliteDbFile, TestAppConfig.class);
                 BrowserContext context = browser.newContext()) {
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

        try (Playwright playwright = Playwright.create(); Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
             RunningApp app = startApp(fakeHome, sqliteDbFile, TestAppConfig.class);
             BrowserContext context = browser.newContext()) {

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
            primaryTurnControl = new TurnControl(false);
        }

        static void blockPrimaryTurnUntilDeltaRelease() {
            primaryTurnControl = new TurnControl(true);
        }

        static void releasePrimaryTurn() {
            primaryTurnControl.release.countDown();
        }

        static void awaitPrimaryStarted() throws InterruptedException {
            assertTrue(primaryTurnControl.started.await(5, TimeUnit.SECONDS), "primary turn did not start");
        }

        static void awaitPrimaryDelta() throws InterruptedException {
            assertTrue(primaryTurnControl.delta.await(5, TimeUnit.SECONDS), "primary delta was not emitted");
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
            private final boolean awaitDeltaRelease;
            private final CountDownLatch started = new CountDownLatch(1);
            private final CountDownLatch delta = new CountDownLatch(1);
            private final CountDownLatch release = new CountDownLatch(1);
            private final CountDownLatch completed = new CountDownLatch(1);

            private TurnControl(boolean awaitDeltaRelease) {
                this.awaitDeltaRelease = awaitDeltaRelease;
            }
        }

        static class TestCodingAgentHarness extends CodingAgentHarness {

            TestCodingAgentHarness() {
                super(null, null, null);
            }

            @Override
            public AgentTurnResult runTurnStreaming(AgentTurnRequest request, AgentStreamListener listener) {
                TurnControl control = primaryTurnControl;
                if (control != null && control.awaitDeltaRelease) {
                    control.started.countDown();
                    awaitRelease(control.release);
                    listener.onTextDelta("Primary task running");
                    control.delta.countDown();
                } else {
                    listener.onTextDelta("Primary task running");
                    if (control != null) {
                        control.started.countDown();
                        awaitRelease(control.release);
                    }
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

package com.judepereira.jupiter2.e2e;

import com.judepereira.jupiter2.agent.harness.AgentTurnRequest;
import com.judepereira.jupiter2.agent.harness.AgentTurnResult;
import com.judepereira.jupiter2.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter2.agent.llm.AgentStreamListener;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InactiveSessionUnreadRailE2ETest extends E2ETestSupport {

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
            page.waitForResponse(
                    response -> response.url().contains("/ui/sessions/add") && response.status() == 200,
                    () -> {
                        page.locator("#session-name-input").fill("Session #2");
                        page.locator("#session-name-input").press("Enter");
                    });

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

            page.waitForResponse(
                    response -> response.url().contains("/ui/sessions/") && response.url().contains("/activate") && response.status() == 200,
                    () -> sessionTwoRow.locator(".session-item").click());
            assertThat(page.locator(".session-item.active .session-label")).hasText("Session #2");
            assertThat(sessionOneRow.locator(".unread-dot")).hasCount(0);

            TestAppConfig.releasePrimaryTurn();
            TestAppConfig.awaitPrimaryCompleted();

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

package com.judepereira.jupiter.e2e;

import com.judepereira.jupiter.agent.harness.AgentTurnRequest;
import com.judepereira.jupiter.agent.harness.AgentTurnResult;
import com.judepereira.jupiter.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter.agent.llm.AgentStreamListener;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.ViewportSize;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatComposerEnterBehaviorE2ETest extends E2ETestSupport {

    private static final String ASSISTANT_REPLY = "Deterministic assistant reply";

    @Test
    void desktopEnterSubmitsMessage(@TempDir Path tempDir) throws Exception {
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

            page.locator("#chat-input").fill("hello there");
            page.locator("#chat-input").press("Enter");

            assertThat(page.locator("#chat-messages-list li")).hasCount(3);
            assertThat(page.locator("#chat-messages-list li").nth(1).locator(".chat-message-text")).hasText("hello there");
            assertThat(page.locator("#chat-messages-list li").nth(2).locator(".chat-message-text")).hasText(ASSISTANT_REPLY);
            var assistantRow = page.locator("#chat-messages-list li").nth(2);
            var forkButton = assistantRow.locator(".chat-message-fork-button");
            assertThat(assistantRow.locator(".chat-message-subtitle")).containsText("Fork");
            org.assertj.core.api.Assertions.assertThat(forkButton.getAttribute("hx-post")).isEqualTo("/ui/chat/fork/" + assistantRow.getAttribute("data-id"));
            assertThat(forkButton).hasAttribute("hx-target", "#shell");
            assertThat(forkButton).hasAttribute("hx-swap", "none");
            assertThat(page.locator("#chat-input")).hasValue("");
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    @Test
    void desktopAltEnterInsertsNewlineWithoutSubmitting(@TempDir Path tempDir) throws Exception {
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

            int initialMessageCount = page.locator("#chat-messages-list li").count();
            page.locator("#chat-input").fill("hello");
            page.locator("#chat-input").press("Alt+Enter");

            assertThat(page.locator("#chat-input")).hasValue("hello\n");
            assertThat(page.locator("#chat-messages-list li")).hasCount(initialMessageCount);
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    @Test
    void mobileEnterInsertsNewlineWithoutSubmitting(@TempDir Path tempDir) throws Exception {
        Path fakeHome = Files.createDirectories(tempDir.resolve("fake-home"));
        Path projectDir = Files.createDirectories(fakeHome.resolve("child-project"));
        Path sqliteDbFile = tempDir.resolve("sqlite-db/jupiter.db");
        Files.createDirectories(sqliteDbFile.getParent());

        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());

        try (Playwright playwright = Playwright.create(); Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
             RunningApp app = startApp(fakeHome, sqliteDbFile, TestAppConfig.class);
             BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                     .setViewportSize(new ViewportSize(390, 844))
                     .setIsMobile(true)
                     .setHasTouch(true))) {
            Page page = context.newPage();

            page.navigate(app.baseUrl());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).waitFor();
            openProject(page, "Alpha", projectDir);

            int initialMessageCount = page.locator("#chat-messages-list li").count();
            assertTrue((Boolean) page.evaluate("() => window.matchMedia('(max-width: 600px)').matches"));

            page.locator("#chat-input").fill("hello");
            page.locator("#chat-input").press("Enter");

            assertThat(page.locator("#chat-input")).hasValue("hello\n");
            assertThat(page.locator("#chat-messages-list li")).hasCount(initialMessageCount);
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestAppConfig {

        @Bean
        @Primary
        CodingAgentHarness codingAgentHarness() {
            return new TestCodingAgentHarness();
        }

        static class TestCodingAgentHarness extends CodingAgentHarness {

            TestCodingAgentHarness() {
                super(null, null, null);
            }

            @Override
            public AgentTurnResult runTurnStreaming(AgentTurnRequest request, AgentStreamListener listener) {
                listener.onTextDelta(ASSISTANT_REPLY);
                AgentTurnResult result = new AgentTurnResult(ASSISTANT_REPLY, java.util.List.of());
                listener.onComplete(result);
                return result;
            }
        }
    }
}

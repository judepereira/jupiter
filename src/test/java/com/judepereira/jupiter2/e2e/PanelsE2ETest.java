package com.judepereira.jupiter2.e2e;

import com.judepereira.jupiter2.agent.harness.AgentTurnRequest;
import com.judepereira.jupiter2.agent.harness.AgentTurnResult;
import com.judepereira.jupiter2.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter2.agent.llm.AgentStreamListener;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
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

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

class PanelsE2ETest extends E2ETestSupport {

    private static final String ASSISTANT_REPLY = "Deterministic assistant reply";

    @Test
    void toggleReviewAndTerminalPanels(@TempDir Path tempDir) throws Exception {
        Path fakeHome = Files.createDirectories(tempDir.resolve("fake-home"));
        Path projectDir = Files.createDirectories(fakeHome.resolve("child-project"));
        Path dbFile = tempDir.resolve("h2db/jupiter");
        Files.createDirectories(dbFile.getParent());
        Path screenshotsDir = Files.createDirectories(Path.of("target", "playwright-screenshots", "PanelsE2ETest"));

        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());

        try (Playwright playwright = Playwright.create(); Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            try (RunningApp app = startApp(fakeHome, dbFile, TestAppConfig.class);
                 BrowserContext context = browser.newContext()) {
                Page page = context.newPage();

                page.navigate(app.baseUrl());
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).waitFor();

                openProject(page, "Alpha", projectDir);
                captureScreenshot(page, screenshotsDir, "01-project-opened.png");

                assertThat(page.locator("#review")).hasCount(1);
                assertThat(page.locator("#review")).not().isVisible();
                assertThat(page.locator("#bottom-panel")).hasCount(1);
                assertThat(page.locator("#bottom-panel")).not().isVisible();

                page.locator("#toggle-terminal-rail-btn").click();

                assertThat(page.locator("#bottom-panel")).isVisible();
                assertThat(page.locator("#bottom-panel .terminal-header")).isVisible();
                assertThat(page.locator("#review")).not().isVisible();
                runTerminalCommandAndAssertOutput(page);
                captureScreenshot(page, screenshotsDir, "02-terminal-open.png");

                page.locator("#toggle-review-rail-btn").click();

                assertThat(page.locator("#review")).isVisible();
                assertThat(page.locator("#review .review-header")).isVisible();
                assertThat(page.locator("#bottom-panel")).isVisible();
                assertThat(page.locator("#bottom-panel .terminal-header")).isVisible();
                assertThat(page.locator("#review")).hasCount(1);
                assertThat(page.locator("#bottom-panel")).hasCount(1);
                captureScreenshot(page, screenshotsDir, "03-review-open.png");

                page.locator("#toggle-review-rail-btn").click();

                assertThat(page.locator("#review")).not().isVisible();
                assertThat(page.locator("#bottom-panel")).isVisible();
                assertThat(page.locator("#bottom-panel .terminal-header")).isVisible();
                assertThat(page.locator("#review")).hasCount(1);
                assertThat(page.locator("#bottom-panel")).hasCount(1);
                captureScreenshot(page, screenshotsDir, "04-review-closed.png");
            }
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    private void runTerminalCommandAndAssertOutput(Page page) {
        assertThat(page.locator(".terminal-mount .xterm")).isVisible();
        page.locator(".terminal-mount").click();
        page.keyboard().type("echo \"hello world\"");
        page.keyboard().press("Enter");

        assertThat(page.locator(".terminal-mount .xterm-rows")).containsText("hello world");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestAppConfig {

        @Bean
        @Primary
        CodingAgentHarness codingAgentHarness() {
            return new CodingAgentHarness(null, null, null) {
                @Override
                public AgentTurnResult runTurnStreaming(AgentTurnRequest request, AgentStreamListener listener) {
                    listener.onTextDelta(ASSISTANT_REPLY);
                    AgentTurnResult result = new AgentTurnResult(ASSISTANT_REPLY, java.util.List.of());
                    listener.onComplete(result);
                    return result;
                }
            };
        }
    }
}

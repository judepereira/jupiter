package com.judepereira.jupiter.e2e;

import com.judepereira.jupiter.agent.harness.AgentTurnRequest;
import com.judepereira.jupiter.agent.harness.AgentTurnResult;
import com.judepereira.jupiter.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter.agent.llm.AgentStreamListener;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

class ProjectCreationE2ETest extends E2ETestSupport {

    private static final String ASSISTANT_REPLY = "Deterministic assistant reply";

    @Test
    void addProjectChatAndPersistenceSurviveRestart(@TempDir Path tempDir) throws Exception {
        Path fakeHome = Files.createDirectories(tempDir.resolve("fake-home"));
        Path projectDir = Files.createDirectories(fakeHome.resolve("child-project"));
        Path sqliteDbFile = tempDir.resolve("sqlite-db/jupiter.db");
        Files.createDirectories(sqliteDbFile.getParent());
        Path screenshotsDir = Files.createDirectories(Path.of("target", "playwright-screenshots", "ProjectPersistencePlaywrightTest"));

        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());

        try {
            try (RunningApp first = startApp(fakeHome, sqliteDbFile, TestAppConfig.class);
                 BrowserContext context = newBrowserContext()) {
                Page page = context.newPage();

                page.navigate(first.baseUrl());
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).waitFor();
                captureScreenshot(page, screenshotsDir, "01-initial-load.png");

                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).click();
                assertThat(page.locator("#project-modal")).isVisible();
                captureScreenshot(page, screenshotsDir, "02-project-modal-open.png");

                openProjectThroughModal(page, "Alpha", projectDir, () -> captureScreenshot(page, screenshotsDir, "03-directory-selected.png"));
                captureScreenshot(page, screenshotsDir, "04-project-opened.png");

                String userMessage = "hello there";
                page.locator("#chat-input").fill(userMessage);
                page.locator("#chat-send-btn").click();

                assertThat(page.locator("#chat-messages-list li")).hasCount(3);
                assertThat(page.locator("#chat-messages-list li").nth(1).locator(".chat-message-text")).hasText(userMessage);
                assertThat(page.locator("#chat-messages-list li").nth(2).locator(".chat-message-text")).hasText(ASSISTANT_REPLY);
                captureScreenshot(page, screenshotsDir, "05-chat-response.png");
            }

            try (RunningApp second = startApp(fakeHome, sqliteDbFile, TestAppConfig.class);
                 BrowserContext context = newBrowserContext()) {
                Page page = context.newPage();

                page.navigate(second.baseUrl());

                assertThat(page.locator(".project-tab-group.active .project-tab-label")).hasText("Alpha");
                assertThat(page.locator("#chat-messages-list li")).hasCount(3);
                assertThat(page.locator("#chat-messages-list li").nth(1).locator(".chat-message-text")).hasText("hello there");
                assertThat(page.locator("#chat-messages-list li").nth(2).locator(".chat-message-text")).hasText(ASSISTANT_REPLY);
                captureScreenshot(page, screenshotsDir, "06-after-restart.png");
            }
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

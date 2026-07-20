package com.judepereira.jupiter2.e2e;

import com.judepereira.jupiter2.agent.harness.AgentTurnRequest;
import com.judepereira.jupiter2.agent.harness.AgentTurnResult;
import com.judepereira.jupiter2.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter2.agent.harness.ToolCallTrace;
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
import java.util.List;
import java.util.Map;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

class ReviewSourceE2ETest extends E2ETestSupport {

    @Test
    void reviewSourceDropdownSwitchesBetweenSessionAndGitFiles(@TempDir Path tempDir) throws Exception {
        Path fakeHome = Files.createDirectories(tempDir.resolve("fake-home"));
        Path projectDir = fakeHome.resolve("sample-repo");
        Path dbFile = tempDir.resolve("h2db/jupiter");
        Files.createDirectories(dbFile.getParent());
        Path screenshotsDir = Files.createDirectories(Path.of("target", "playwright-screenshots", "ReviewSourceE2ETest"));

        initGitRepoWithInitialCommit(projectDir);
        Files.writeString(projectDir.resolve("outside-git-only.txt"), "outside git change\n");

        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());

        try (Playwright playwright = Playwright.create(); Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            try (RunningApp app = startApp(fakeHome, dbFile, TestAppConfig.class);
                 BrowserContext context = browser.newContext()) {
                Page page = context.newPage();

                page.navigate(app.baseUrl());
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).waitFor();

                openProject(page, "Alpha", projectDir);

                page.waitForResponse(
                        response -> response.url().contains("/ui/review/toggle") && response.status() == 200,
                        () -> page.locator("#toggle-review-rail-btn").click());
                assertThat(page.locator("#review .review-source-select")).isVisible();
                captureScreenshot(page, screenshotsDir, "01-review-open.png");

                page.locator("#chat-input").fill("please edit the session file");
                page.locator("#chat-send-btn").click();
                assertThat(page.locator("#chat-messages-list li")).hasCount(3);

                page.reload();
                assertThat(page.locator("#review .review-source-select")).isVisible();

                assertThat(page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("session-edit.txt"))).hasCount(1);
                assertThat(page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("outside-git-only.txt"))).hasCount(0);

                page.waitForResponse(
                        response -> response.url().contains("/ui/review/source") && response.status() == 200,
                        () -> page.locator("#review .review-source-select").selectOption("GIT"));

                assertThat(page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("session-edit.txt"))).hasCount(1);
                assertThat(page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("outside-git-only.txt"))).hasCount(1);
                captureScreenshot(page, screenshotsDir, "02-git-source.png");

                var outsideFileLink = page.locator("#review-panel a[href*='outside-git-only.txt']");
                assertThat(outsideFileLink).hasCount(1);
                outsideFileLink.click(new com.microsoft.playwright.Locator.ClickOptions().setForce(true));

                assertThat(page.locator("#diff-file-path")).hasText("outside-git-only.txt");
                assertThat(page.locator("#diff-content")).containsText("outside git change");
                captureScreenshot(page, screenshotsDir, "03-git-file-selected.png");
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
            return new CodingAgentHarness(null, null, null) {
                @Override
                public AgentTurnResult runTurnStreaming(AgentTurnRequest request, AgentStreamListener listener) {
                    Path workspaceRoot = Path.of(request.getWorkspaceRoot());
                    Path sessionFile = workspaceRoot.resolve("session-edit.txt");
                    try {
                        Files.writeString(sessionFile, "session edit\n");
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }

                    ToolCallTrace trace = new ToolCallTrace("tool-1-0", "write_file",
                            Map.of("path", "session-edit.txt", "content", "session edit"), true,
                            "wrote session-edit.txt", Map.of("path", "session-edit.txt"));
                    AgentTurnResult result = new AgentTurnResult("done", List.of(trace));
                    listener.onComplete(result);
                    return result;
                }
            };
        }
    }
}

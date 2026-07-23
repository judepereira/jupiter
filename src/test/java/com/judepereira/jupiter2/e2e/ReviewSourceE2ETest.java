package com.judepereira.jupiter2.e2e;

import com.judepereira.jupiter2.agent.harness.AgentTurnRequest;
import com.judepereira.jupiter2.agent.harness.AgentTurnResult;
import com.judepereira.jupiter2.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter2.agent.harness.ToolCallTrace;
import com.judepereira.jupiter2.persistence.AppStateService;
import com.judepereira.jupiter2.persistence.Persistence.AppStateView;
import com.judepereira.jupiter2.persistence.Persistence.ChangedFileDraft;
import com.judepereira.jupiter2.agent.llm.AgentStreamListener;
import com.microsoft.playwright.ConsoleMessage;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewSourceE2ETest extends E2ETestSupport {

    @Test
    void reviewSourceDropdownSwitchesBetweenSessionAndGitFiles(@TempDir Path tempDir) throws Exception {
        Path fakeHome = Files.createDirectories(tempDir.resolve("fake-home"));
        Path projectDir = fakeHome.resolve("sample-repo");
        Path sqliteDbFile = tempDir.resolve("sqlite-db/jupiter.db");
        Files.createDirectories(sqliteDbFile.getParent());
        Path screenshotsDir = Files.createDirectories(Path.of("target", "playwright-screenshots", "ReviewSourceE2ETest"));

        initGitRepoWithInitialCommit(projectDir);
        Files.writeString(projectDir.resolve("outside-git-only.txt"), "outside git change\n");

        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());

        try (Playwright playwright = Playwright.create(); Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            try (RunningApp app = startApp(fakeHome, sqliteDbFile, TestAppConfig.class);
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

                assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("session-edit.txt"))).hasCount(1);
                assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("outside-git-only.txt"))).hasCount(0);

                page.waitForResponse(
                        response -> response.url().contains("/ui/review/source") && response.status() == 200,
                        () -> page.locator("#review .review-source-select").selectOption("GIT"));

                assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("session-edit.txt"))).hasCount(1);
                assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("outside-git-only.txt"))).hasCount(1);
                captureScreenshot(page, screenshotsDir, "02-git-source.png");

                var outsideFileButton = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("outside-git-only.txt"));
                assertThat(outsideFileButton).hasCount(1);
                page.waitForResponse(
                        response -> response.url().contains("/ui/review/file") && response.status() == 200,
                        outsideFileButton::click);

                assertTrue((Boolean) outsideFileButton.evaluate("el => el.classList.contains('active')"));
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

    @Test
    void reviewPanelFileEntriesToggleDiffVisibilityWithoutConsoleErrors(@TempDir Path tempDir) throws Exception {
        Path fakeHome = Files.createDirectories(tempDir.resolve("fake-home"));
        Path projectDir = Files.createDirectories(fakeHome.resolve("sample-repo"));
        Path sqliteDbFile = tempDir.resolve("sqlite-db/jupiter.db");
        Files.createDirectories(sqliteDbFile.getParent());

        initGitRepoWithInitialCommit(projectDir);

        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());

        try (Playwright playwright = Playwright.create(); Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            try (RunningApp app = startApp(fakeHome, sqliteDbFile, TestAppConfig.class);
                 BrowserContext context = browser.newContext()) {
                Page page = context.newPage();
                List<String> consoleErrors = new java.util.concurrent.CopyOnWriteArrayList<>();
                page.onConsoleMessage(message -> {
                    if (message.type().equals("error")) {
                        consoleErrors.add(formatConsoleMessage(message));
                    }
                });

                page.navigate(app.baseUrl());
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).waitFor();

                openProject(page, "Alpha", projectDir);

                AppStateService appStateService = app.context().getBean(AppStateService.class);
                AppStateView view = appStateService.loadViewData();
                long sessionId = view.activeSession().id();
                appStateService.addChangedFilesToSession(sessionId, List.of(
                        new ChangedFileDraft("first-review-file.txt", "first file diff\n"),
                        new ChangedFileDraft("second-review-file.txt", "second file diff\n")
                ));

                page.reload();

                var firstFileButton = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("first-review-file.txt"));
                var secondFileButton = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("second-review-file.txt"));

                firstFileButton.waitFor();
                secondFileButton.waitFor();

                page.waitForResponse(
                        response -> response.url().contains("/ui/review/file") && response.status() == 200,
                        firstFileButton::click);
                assertTrue((Boolean) firstFileButton.evaluate("el => el.classList.contains('active')"));
                assertThat(page.locator("#diff-content")).containsText("first file diff");

                page.waitForResponse(
                        response -> response.url().contains("/ui/review/file") && response.status() == 200,
                        firstFileButton::click);
                assertTrue(!(Boolean) firstFileButton.evaluate("el => el.classList.contains('active')"));
                assertThat(page.locator("#diff-content")).hasCount(0);

                consoleErrors.clear();

                page.waitForResponse(
                        response -> response.url().contains("/ui/review/file") && response.status() == 200,
                        secondFileButton::click);
                assertTrue((Boolean) secondFileButton.evaluate("el => el.classList.contains('active')"));
                assertThat(page.locator("#diff-content")).containsText("second file diff");
                assertTrue(consoleErrors.isEmpty(), () -> "Console errors: " + consoleErrors);

                page.waitForResponse(
                        response -> response.url().contains("/ui/review/file") && response.status() == 200,
                        secondFileButton::click);
                assertTrue(!(Boolean) secondFileButton.evaluate("el => el.classList.contains('active')"));
                assertThat(page.locator("#diff-content")).hasCount(0);
            }
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    private static String formatConsoleMessage(ConsoleMessage message) {
        return message.type() + ": " + message.text();
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
        }
    }
}

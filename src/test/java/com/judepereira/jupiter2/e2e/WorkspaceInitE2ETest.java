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
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceInitE2ETest extends E2ETestSupport {

    @Test
    void workspaceInitCommandsRunWhenCreatingWorkspace(@TempDir Path tempDir) throws Exception {
        Path fakeHome = Files.createDirectories(tempDir.resolve("fake-home"));
        Path projectDir = fakeHome.resolve("sample-repo");
        Path dbFile = tempDir.resolve("h2db/jupiter");
        Files.createDirectories(dbFile.getParent());

        initGitRepoWithInitialCommit(projectDir);

        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());

        String branchName = "feature-init-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String commands = "echo init-one\npwd\ntouch init-ran.txt";
        Path worktreeDir = fakeHome.resolve(".trees").resolve(projectDir.getFileName().toString()).resolve(branchName);

        try (Playwright playwright = Playwright.create(); Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            try (RunningApp app = startApp(fakeHome, dbFile, TestAppConfig.class);
                 BrowserContext context = browser.newContext()) {
                Page page = context.newPage();

                page.navigate(app.baseUrl());
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).waitFor();

                openProject(page, "Alpha", projectDir);

                page.waitForResponse(
                        response -> response.url().contains("/ui/settings") && response.status() == 200,
                        () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Settings")).click());
                assertThat(page.locator("#settings-modal")).isVisible();

                page.locator("textarea[name='workspaceInitCommands']").fill(commands);
                page.waitForResponse(
                        response -> response.url().contains("/ui/settings/apply") && response.status() == 200,
                        () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Save")).click());
                assertThat(page.locator("#settings-modal")).hasCount(0);

                page.waitForResponse(
                        response -> response.url().contains("/ui/workspaces/new") && response.status() == 200,
                        () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New Workspace")).click());
                assertThat(page.locator("#workspace-modal")).isVisible();

                page.locator("input[name='branchName']").fill(branchName);
                page.waitForResponse(
                        response -> response.url().contains("/ui/workspaces/add") && response.status() == 200,
                        () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create workspace")).click());

                assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Workspace Init"))).isVisible();
                assertThat(page.locator("#bottom-panel")).containsText("Workspace Init");

                awaitPathExists(worktreeDir.resolve("init-ran.txt"));
                assertTrue(Files.exists(worktreeDir.resolve("init-ran.txt")));
            }
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    private static void awaitPathExists(Path path) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (Files.exists(path)) {
                return;
            }
            Thread.sleep(100);
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
                    AgentTurnResult result = new AgentTurnResult("done", java.util.List.of());
                    listener.onComplete(result);
                    return result;
                }
            };
        }
    }
}

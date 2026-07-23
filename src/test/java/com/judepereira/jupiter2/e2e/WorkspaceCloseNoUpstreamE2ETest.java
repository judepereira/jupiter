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

class WorkspaceCloseNoUpstreamE2ETest extends E2ETestSupport {

    @Test
    void cleanWorkspaceWithoutUpstreamClosesWithoutConfirmationModal(@TempDir Path tempDir) throws Exception {
        Path fakeHome = Files.createDirectories(tempDir.resolve("fake-home"));
        Path projectDir = fakeHome.resolve("sample-repo");
        Path sqliteDbFile = tempDir.resolve("sqlite-db/jupiter.db");
        Files.createDirectories(sqliteDbFile.getParent());

        initGitRepoWithInitialCommit(projectDir);

        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());

        try (Playwright playwright = Playwright.create(); Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            try (RunningApp app = startApp(fakeHome, sqliteDbFile, TestAppConfig.class);
                 BrowserContext context = browser.newContext()) {
                Page page = context.newPage();

                page.navigate(app.baseUrl());
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).waitFor();

                openProject(page, "Alpha", projectDir);

                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New Workspace")).click();
                assertThat(page.locator("#workspace-modal")).isVisible();
                page.locator("input[name='branchName']").fill("feature-clean-close");
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create workspace")).click();

                assertThat(page.locator(".workspace-group")).hasCount(2);
                assertThat(page.locator(".workspace-group").nth(1).locator(".workspace-label")).hasText("feature-clean-close");

                var branchRow = page.locator(".workspace-group").nth(1).locator(".workspace-row");
                branchRow.hover();
                var closeButton = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Close workspace"));
                assertThat(closeButton).isVisible();
                closeButton.click();

                assertThat(page.locator("#workspace-close-modal")).hasCount(0);
                assertThat(page.locator(".workspace-group")).hasCount(1);
                assertThat(page.locator(".workspace-group .workspace-label")).hasText("Default Workspace");
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
                    AgentTurnResult result = new AgentTurnResult("Deterministic assistant reply", java.util.List.of());
                    listener.onComplete(result);
                    return result;
                }
            };
        }
    }
}

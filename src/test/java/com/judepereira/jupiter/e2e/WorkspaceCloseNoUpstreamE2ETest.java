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

class WorkspaceCloseNoUpstreamE2ETest extends E2ETestSupport {

    @Test
    void unpushedWorkspaceWithoutUpstreamShowsConfirmationModal(@TempDir Path tempDir) throws Exception {
        Path fakeHome = Files.createDirectories(tempDir.resolve("fake-home"));
        Path projectDir = fakeHome.resolve("sample-repo");
        Path sqliteDbFile = tempDir.resolve("sqlite-db/jupiter.db");
        Files.createDirectories(sqliteDbFile.getParent());

        initGitRepoWithInitialCommit(projectDir);

        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());

        try {
            try (RunningApp app = startApp(fakeHome, sqliteDbFile, TestAppConfig.class);
                 BrowserContext context = newBrowserContext()) {
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

                assertThat(page.locator("#workspace-close-modal")).isVisible();
                assertThat(page.locator("#workspace-close-modal")).containsText("Local commits detected, that haven't been pushed");
                assertThat(page.locator(".workspace-group")).hasCount(2);
                assertThat(page.locator(".workspace-group .workspace-label")).containsText(new String[]{"Default Workspace", "feature-clean-close"});
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
            return new CodingAgentHarness(null, null, null, null, null, null, null, null, new com.judepereira.jupiter.agent.harness.SystemPromptComposer(com.judepereira.jupiter.testsupport.SkillTestSupport.defaultComponents().renderer()), com.judepereira.jupiter.testsupport.SkillTestSupport.defaultComponents().discovery(), com.judepereira.jupiter.testsupport.SkillTestSupport.defaultComponents().resolver(), com.judepereira.jupiter.testsupport.SkillTestSupport.defaultComponents().injector()) {
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

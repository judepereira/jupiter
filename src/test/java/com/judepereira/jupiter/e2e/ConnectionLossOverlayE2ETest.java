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
import static org.assertj.core.api.Assertions.assertThat;

class ConnectionLossOverlayE2ETest extends E2ETestSupport {

    @Test
    void showsConnectionLossOverlayAndAutoReloadsAfterRestart(@TempDir Path tempDir) throws Exception {
        Path fakeHome = Files.createDirectories(tempDir.resolve("fake-home"));
        Path projectDir = Files.createDirectories(fakeHome.resolve("child-project"));
        Path sqliteDbFile = tempDir.resolve("sqlite-db/jupiter.db");
        Files.createDirectories(sqliteDbFile.getParent());

        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());

        RunningApp app = null;
        try (BrowserContext context = newBrowserContext()) {
            context.addInitScript("(() => { const key = 'connection-loss-reload-count'; const current = Number(sessionStorage.getItem(key) || '0'); sessionStorage.setItem(key, String(current + 1)); })();");

            app = startApp(fakeHome, sqliteDbFile, TestAppConfig.class);
            Page page = context.newPage();
            page.navigate(app.baseUrl());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).waitFor();

            openProject(page, "Alpha", projectDir);
            assertThat(page.locator("#chat-input")).isVisible();

            int port = app.port();
            app.close();
            app = null;

            assertThat(page.locator("#connection-loss-overlay")).isVisible();
            assertThat((Boolean) page.locator("body").evaluate("body => body.classList.contains('connection-loss-overlay-open')")).isTrue();

            try (RunningApp restarted = startApp(fakeHome, sqliteDbFile, port, TestAppConfig.class)) {
                assertThat(restarted.port()).isEqualTo(port);
                page.waitForFunction("() => Number(sessionStorage.getItem('connection-loss-reload-count') || '0') >= 2");
                page.waitForFunction("() => !document.body.classList.contains('connection-loss-overlay-open')");

                assertThat(page.locator("#connection-loss-overlay")).isHidden();
                assertThat((Boolean) page.locator("body").evaluate("body => body.classList.contains('connection-loss-overlay-open')")).isFalse();

                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).click();
                assertThat(page.locator("#project-modal")).isVisible();
            }
        } finally {
            if (app != null) {
                app.close();
            }
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
            return new CodingAgentHarness(null, null, null, null, null, null, null, null, new com.judepereira.jupiter.agent.harness.SystemPromptComposer(new com.judepereira.jupiter.agent.skill.SkillCatalogRenderer()), new com.judepereira.jupiter.agent.skill.SkillDiscoveryService(new com.judepereira.jupiter.agent.skill.SkillParser(), System.getProperty("user.home")), new com.judepereira.jupiter.agent.skill.SkillInvocationResolver(), new com.judepereira.jupiter.agent.skill.SkillContextInjector(new com.judepereira.jupiter.agent.skill.SkillParser())) {
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

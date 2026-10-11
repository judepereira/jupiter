package com.judepereira.jupiter.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.judepereira.jupiter.agent.catalog.AgentDefinitionService;
import com.judepereira.jupiter.agent.harness.AgentTurnRequest;
import com.judepereira.jupiter.agent.harness.AgentTurnResult;
import com.judepereira.jupiter.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter.agent.harness.SystemPromptComposer;
import com.judepereira.jupiter.agent.llm.AgentStreamListener;
import com.judepereira.jupiter.command.CommandCatalogService.CommandDefinition;
import com.judepereira.jupiter.command.CommandCatalogService.CommandKind;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.testsupport.SkillTestSupport;
import com.judepereira.jupiter.ui.UiController;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

class AutonomousWatchE2ETest extends E2ETestSupport {
    @Test
    void autonomousTurnAppearsInOpenChatAndStreamsWithoutLosingDraft(@TempDir Path tempDir) throws Exception {
        TestAppConfig.reset();
        TestAppConfig.blockTurn();
        Path home = Files.createDirectories(tempDir.resolve("home"));
        Path project = Files.createDirectories(home.resolve("project"));
        Path database = tempDir.resolve("db/jupiter.db");
        Files.createDirectories(database.getParent());
        try (RunningApp app = startAppWithConnectedOpenAi(home, database, TestAppConfig.class);
                BrowserContext context = newBrowserContext()) {
            Page page = context.newPage();
            page.onConsoleMessage(message -> System.err.println("browser console: " + message.text()));
            page.onPageError(error -> System.err.println("browser page error: " + error));
            page.onResponse(response -> {
                if (response.url().contains("/ui/chat/primary"))
                    System.err.println("primary chat response: " + response.status() + " " + response.url());
            });
            page.onRequestFailed(
                    request -> System.err.println("request failed: " + request.url() + " " + request.failure()));
            page.navigate(app.baseUrl());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).waitFor();
            openProject(page, "Alpha", project);
            long sessionId = app.context().getBean(AppStateService.class).loadViewData().activeSession().id();
            page.locator("#chat-input").fill("unsent draft stays here");
            assertTrue((Boolean) page.evaluate("() => !!window.__workspaceRailRefreshSource"),
                    "rail EventSource was not initialized");

            CommandDefinition command = new CommandDefinition("autonomous-watch-test", "Autonomous watch test",
                    "Browser regression action", CommandKind.PROMPT, "Autonomous watch action for browser regression",
                    null, null);
            UiController uiController = app.context().getBean(UiController.class);
            String assistantId = uiController.prepareRegisteredStream(sessionId, project.toString(),
                    app.context().getBean(AgentDefinitionService.class).getRequired("plan"), command);
            uiController.startRegisteredStream(assistantId);

            try {
                assertThat(page.locator("#chat-messages-list")).containsText(command.body());
                assertThat(page.locator("#chat-input")).hasValue("unsent draft stays here");
                assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Stop"))).isVisible();
                TestAppConfig.awaitStarted();
                TestAppConfig.release();
                assertThat(page.locator("#chat-messages-list")).containsText("Deterministic autonomous reply");
            } catch (AssertionError failure) {
                System.err.println("chat DOM: " + page.locator("#chat-messages-list").innerHTML());
                System.err.println("chat dataset: "
                        + page.locator("#chat-messages-list").evaluate("el => JSON.stringify(el.dataset)"));
                System.err.println("EventSource states: " + page.evaluate(
                        "() => Object.entries(window).filter(([k,v]) => k.toLowerCase().includes('source') && v instanceof EventSource).map(([k,v]) => `${k}=${v.readyState}`).join(', ')"));
                throw failure;
            }
        } finally {
            TestAppConfig.release();
            TestAppConfig.reset();
        }
    }

    @TestConfiguration
    static class TestAppConfig {
        private static volatile CountDownLatch started;
        private static volatile CountDownLatch release;
        static void reset() {
            started = null;
            release = null;
        }
        static void blockTurn() {
            started = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }
        static void awaitStarted() throws InterruptedException {
            assertTrue(started.await(20, TimeUnit.SECONDS), "autonomous harness did not start");
        }
        static void release() {
            if (release != null)
                release.countDown();
        }

        @Bean
        @Primary
        CodingAgentHarness codingAgentHarness() {
            return new CodingAgentHarness(null, null, null, null, null, null, null, null, null,
                    new SystemPromptComposer(SkillTestSupport.defaultComponents().renderer()),
                    SkillTestSupport.defaultComponents().discovery(), SkillTestSupport.defaultComponents().resolver(),
                    SkillTestSupport.defaultComponents().injector()) {
                @Override
                public AgentTurnResult runTurnStreaming(AgentTurnRequest request, AgentStreamListener listener) {
                    started.countDown();
                    try {
                        if (!release.await(20, TimeUnit.SECONDS))
                            throw new IllegalStateException("release timed out");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    listener.onTextDelta("Autonomous response complete");
                    var result = new AgentTurnResult("Deterministic autonomous reply", List.of());
                    listener.onComplete(result);
                    return result;
                }
            };
        }
    }
}

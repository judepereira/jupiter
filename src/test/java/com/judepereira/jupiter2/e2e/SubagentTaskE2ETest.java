package com.judepereira.jupiter2.e2e;

import com.judepereira.jupiter2.agent.harness.AgentTurnRequest;
import com.judepereira.jupiter2.agent.harness.AgentTurnResult;
import com.judepereira.jupiter2.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter2.agent.harness.ToolCallTrace;
import com.judepereira.jupiter2.agent.llm.AgentStreamListener;
import com.judepereira.jupiter2.agent.tools.ToolExecutionContext;
import com.judepereira.jupiter2.agent.tools.ToolExecutionResult;
import com.judepereira.jupiter2.agent.tools.impl.TaskTool;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubagentTaskE2ETest extends E2ETestSupport {

    @Test
    void taskTraceShowsSubagentStreamingPanelAndOpensTranscript(@TempDir Path tempDir) throws Exception {
        TestAppConfig.reset();
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

            assertThat(page.locator("#chat-agent-select option")).hasCount(2);
            assertThat(page.locator("#chat-agent-select")).containsText("Plan");
            assertThat(page.locator("#chat-agent-select")).containsText("Engineer");

            page.locator("#chat-input").fill("please use a task");
            page.locator("#chat-send-btn").click();

            var taskToolCall = page.locator("#chat-messages-list > li .tool-calls > .tool-call:has(.tool-call-call[data-tool-call-id='task-1'])").first();
            taskToolCall.waitFor();
            assertThat(taskToolCall).isVisible();
            assertThat(taskToolCall.locator(":scope > summary.tool-call-summary > .tool-call-name")).hasText("task");
            assertThat(taskToolCall.locator(":scope > summary.tool-call-summary > .tool-call-status")).hasText("success");

            taskToolCall.locator(":scope > summary.tool-call-summary").click();
            var taskSubagentButton = taskToolCall.locator(".tool-call-subagent-button");
            taskSubagentButton.waitFor();
            assertThat(taskSubagentButton).containsText("Open subagent");
            org.assertj.core.api.Assertions.assertThat(page.locator("#chat-messages-list").innerText()).contains("Primary complete");
            org.assertj.core.api.Assertions.assertThat(page.locator("#chat-messages-list").innerText()).doesNotContain("Primary task:");

            String subagentHref = taskSubagentButton.getAttribute("hx-get");
            org.assertj.core.api.Assertions.assertThat(subagentHref).contains("/ui/chat/subagent/");
            page.evaluate("url => htmx.ajax('GET', url, { target: '#chat-container', swap: 'outerHTML' })", subagentHref);
            page.locator(".subagent-bar").waitFor();
            assertThat(page.locator(".subagent-bar")).isVisible();
            assertThat(page.locator(".subagent-bar-name")).hasText("Explore");
            org.assertj.core.api.Assertions.assertThat(page.locator("#chat-messages-list").innerText()).contains("Primary task:");
            assertThat(page.locator("#chat-send-form")).hasCount(0);

            page.evaluate("() => htmx.ajax('GET', '/ui/chat/primary', { target: '#chat-container', swap: 'outerHTML' })");
            page.locator("#chat-send-form").waitFor();

            assertThat(page.locator("#chat-send-form")).isVisible();
            assertThat(page.locator("#chat-agent-select option")).hasCount(2);
            org.assertj.core.api.Assertions.assertThat(page.locator("#chat-messages-list").innerText()).contains("Primary complete");
        } finally {
            TestAppConfig.reset();
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    @Test
    void reloadingPendingPrimaryTurnDoesNotShowNoJobError(@TempDir Path tempDir) throws Exception {
        TestAppConfig.reset();
        TestAppConfig.blockPrimaryTurn();

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
            page.locator("#chat-input").fill("please use a task");
            page.locator("#chat-send-btn").click();

            TestAppConfig.awaitPrimaryStarted();
            page.locator("#chat-messages-list > li.pending").waitFor();

            page.reload();
            page.locator("#chat-messages-list > li.pending").waitFor();
            String reloadedText = page.locator("#chat-container").innerText();
            org.assertj.core.api.Assertions.assertThat(reloadedText).doesNotContain("no_job", "[Error: no_job]");

            TestAppConfig.releasePrimaryTurn();
            TestAppConfig.awaitPrimaryCompleted();

            page.reload();
            assertThat(page.locator("#chat-messages-list")).containsText("Primary complete");
            org.assertj.core.api.Assertions.assertThat(page.locator("#chat-container").innerText()).doesNotContain("no_job", "[Error: no_job]");
        } finally {
            TestAppConfig.reset();
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    @Test
    void openingLiveSubagentButtonShowsSubagentTranscriptAndCompletes(@TempDir Path tempDir) throws Exception {
        TestAppConfig.reset();
        TestAppConfig.blockSubagentTurn();

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
            page.locator("#chat-input").fill("please use a task");
            page.locator("#chat-send-btn").click();

            TestAppConfig.awaitSubagentStarted();
            var taskToolCall = page.locator("#chat-messages-list > li .tool-calls > .tool-call:has(.tool-call-call[data-tool-call-id='task-1'])").first();
            taskToolCall.waitFor();
            assertThat(taskToolCall.locator(":scope > summary.tool-call-summary > .tool-call-name")).hasText("task");
            assertThat(taskToolCall.locator(":scope > summary.tool-call-summary > .tool-call-status")).hasText("running");
            taskToolCall.locator(":scope > summary.tool-call-summary").click();
            var taskSubagentButton = taskToolCall.locator(".tool-call-subagent-button");
            taskSubagentButton.waitFor();
            assertThat(taskSubagentButton).containsText("Open subagent: Explore");

            taskSubagentButton.click();
            page.locator(".subagent-bar").waitFor();
            assertThat(page.locator(".subagent-bar-name")).hasText("Explore");
            String subagentText = page.locator("#chat-container").innerText();
            org.assertj.core.api.Assertions.assertThat(subagentText).contains("Primary task:", "Explore subagent");

            TestAppConfig.releaseSubagentTurn();
            TestAppConfig.awaitSubagentCompleted();

            assertThat(page.locator("#chat-messages-list")).containsText("Explore subagent finished");
            org.assertj.core.api.Assertions.assertThat(page.locator("#chat-container").innerText()).doesNotContain("no_job", "[Error: no_job]");
        } finally {
            TestAppConfig.reset();
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    @Test
    void backToPrimaryKeepsSubagentAffordanceAndToolButtonRemainsClickable(@TempDir Path tempDir) throws Exception {
        TestAppConfig.reset();
        TestAppConfig.blockSubagentTurn();

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
            page.locator("#chat-input").fill("please use a task");
            page.locator("#chat-send-btn").click();

            TestAppConfig.awaitSubagentStarted();
            var taskToolCall = page.locator("#chat-messages-list > li .tool-calls > .tool-call:has(.tool-call-call[data-tool-call-id='task-1'])").first();
            taskToolCall.waitFor();
            assertThat(taskToolCall).isVisible();
            assertThat(taskToolCall.locator(":scope > summary.tool-call-summary > .tool-call-status")).hasText("running");

            taskToolCall.locator(":scope > summary.tool-call-summary").click();
            var taskSubagentButton = taskToolCall.locator(".tool-call-subagent-button");
            taskSubagentButton.waitFor();
            taskSubagentButton.click();
            page.locator(".subagent-bar").waitFor();
            assertThat(page.locator(".subagent-bar")).isVisible();
            assertThat(page.locator(".subagent-bar-name")).hasText("Explore");
            org.assertj.core.api.Assertions.assertThat(page.locator("#chat-container").innerText()).contains("Explore subagent");

            page.locator(".subagent-back-button").click();
            page.locator("#chat-send-form").waitFor();
            assertThat(page.locator("#chat-send-form")).isVisible();
            taskToolCall.waitFor();
            assertThat(taskToolCall).isVisible();
            org.assertj.core.api.Assertions.assertThat((Number) taskToolCall.locator(".tool-call-subagent-button")
                    .evaluateAll("buttons => buttons.filter(button => button.offsetParent !== null).length"))
                    .isEqualTo(1);
            assertThat(taskToolCall.locator(".tool-call-subagent-button")).containsText("Open subagent");

            TestAppConfig.releaseSubagentTurn();
            TestAppConfig.awaitSubagentCompleted();

            org.assertj.core.api.Assertions.assertThat((Number) taskToolCall.locator(".tool-call-subagent-button")
                    .evaluateAll("buttons => buttons.filter(button => button.offsetParent !== null).length"))
                    .isEqualTo(1);

            var taskToolCallSummary = taskToolCall.locator(":scope > summary.tool-call-summary");
            taskToolCallSummary.click();
            assertThat(taskSubagentButton).isVisible();
            taskSubagentButton.click();
            page.locator(".subagent-bar").waitFor();
            assertThat(page.locator(".subagent-bar")).isVisible();
            assertThat(page.locator(".subagent-bar-name")).hasText("Explore");
        } finally {
            TestAppConfig.reset();
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestAppConfig {

        private static volatile TurnControl primaryTurnControl;
        private static volatile TurnControl subagentTurnControl;

        static void reset() {
            primaryTurnControl = null;
            subagentTurnControl = null;
        }

        static void blockPrimaryTurn() {
            primaryTurnControl = new TurnControl();
        }

        static void blockSubagentTurn() {
            subagentTurnControl = new TurnControl();
        }

        static void releasePrimaryTurn() {
            primaryTurnControl.release.countDown();
        }

        static void releaseSubagentTurn() {
            subagentTurnControl.release.countDown();
        }

        static void awaitPrimaryStarted() throws InterruptedException {
            assertTrue(primaryTurnControl.started.await(5, TimeUnit.SECONDS), "primary turn did not start");
        }

        static void awaitPrimaryCompleted() throws InterruptedException {
            assertTrue(primaryTurnControl.completed.await(5, TimeUnit.SECONDS), "primary turn did not complete");
        }

        static void awaitSubagentStarted() throws InterruptedException {
            assertTrue(subagentTurnControl.started.await(5, TimeUnit.SECONDS), "subagent turn did not start");
        }

        static void awaitSubagentCompleted() throws InterruptedException {
            assertTrue(subagentTurnControl.completed.await(5, TimeUnit.SECONDS), "subagent turn did not complete");
        }

        @Bean
        @Primary
        CodingAgentHarness codingAgentHarness(TaskTool taskTool) {
            return new TestCodingAgentHarness(taskTool);
        }

        private static final class TurnControl {
            private final CountDownLatch started = new CountDownLatch(1);
            private final CountDownLatch release = new CountDownLatch(1);
            private final CountDownLatch completed = new CountDownLatch(1);
        }

        static class TestCodingAgentHarness extends CodingAgentHarness {

            private final TaskTool taskTool;

            TestCodingAgentHarness(TaskTool taskTool) {
                super(null, null, null);
                this.taskTool = taskTool;
            }

            @Override
            public AgentTurnResult runTurnStreaming(AgentTurnRequest request, AgentStreamListener listener) {
                if ("explore".equals(request.getAgentId())) {
                    listener.onTextDelta("Explore subagent ");
                    TurnControl control = subagentTurnControl;
                    if (control != null) {
                        control.started.countDown();
                        awaitRelease(control.release);
                    }
                    listener.onTextDelta("working");
                    try {
                        Files.writeString(Path.of(request.getWorkspaceRoot(), "child.txt"), "hello from subagent\n");
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    ToolCallTrace trace = new ToolCallTrace("child-tool-1", "write_file", Map.of(
                            "path", "child.txt",
                            "content", "hello from subagent"
                    ), true, "wrote child.txt", Map.of(
                            "path", "child.txt"
                    ));
                    listener.onToolCallTrace(trace);
                    AgentTurnResult result = new AgentTurnResult("Explore subagent finished", List.of(trace));
                    listener.onComplete(result);
                    if (control != null) {
                        control.completed.countDown();
                    }
                    return result;
                }

                listener.onTextDelta("Primary task running");
                TurnControl control = primaryTurnControl;
                if (control != null) {
                    control.started.countDown();
                    awaitRelease(control.release);
                }
                Map<String, Object> taskArgs = Map.of(
                        "agentId", "explore",
                        "task", "Inspect the task flow and report back.",
                        "expectedOutput", "Explore subagent finished"
                );
                listener.onStatus("calling_tool:task");
                listener.onToolCallStarted(new ToolCallTrace("task-1", "task", taskArgs, false, "", Map.of()));
                ToolExecutionResult taskResult = taskTool.execute(Map.of(
                        "agentId", "explore",
                        "task", "Inspect the task flow and report back.",
                        "expectedOutput", "Explore subagent finished"
                ), new ToolExecutionContext(Path.of(request.getWorkspaceRoot()), false, false, 30,
                        request.getSessionId(), "task-1", com.judepereira.jupiter2.agent.catalog.AgentMode.AGENT, "task-1",
                        (eventName, payload) -> listener.onToolCallProgress("task-1", "task", eventName, payload)));

                ToolCallTrace trace = new ToolCallTrace("task-1", "task", Map.of(
                        "agentId", "explore",
                        "task", "Inspect the task flow and report back.",
                        "expectedOutput", "Explore subagent finished"
                ), true, taskResult.getText(), taskResult.getMachine());
                listener.onToolCallTrace(trace);
                listener.onTextDelta("Primary complete");
                AgentTurnResult result = new AgentTurnResult("Primary complete", List.of(trace));
                listener.onComplete(result);
                if (control != null) {
                    control.completed.countDown();
                }
                return result;
            }

            private static void awaitRelease(CountDownLatch release) {
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS), "turn release timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        }
    }
}

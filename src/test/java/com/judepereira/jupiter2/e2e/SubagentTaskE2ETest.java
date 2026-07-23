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

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

class SubagentTaskE2ETest extends E2ETestSupport {

    @Test
    void taskTraceShowsSubagentStreamingPanelAndOpensTranscript(@TempDir Path tempDir) throws Exception {
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

            page.locator(".subagent-activity").waitFor();
            assertThat(page.locator(".subagent-activity")).isVisible();
            assertThat(page.locator(".subagent-activity__name")).hasText("Explore");
            assertThat(page.locator(".subagent-activity__status")).hasText("done");
            assertThat(page.locator(".subagent-activity__text")).containsText("Explore subagent finished");
            assertThat(page.locator(".subagent-activity .subagent-activity__open")).containsText("Open subagent");

            page.locator("#chat-messages-list > li .tool-calls > .tool-call > summary.tool-call-summary").first().click();
            page.locator(".subagent-activity .subagent-activity__open").waitFor();
            assertThat(page.locator(".subagent-activity .subagent-activity__open")).containsText("Open subagent");
            org.assertj.core.api.Assertions.assertThat(page.locator("#chat-messages-list").innerText()).contains("Primary complete");
            org.assertj.core.api.Assertions.assertThat(page.locator("#chat-messages-list").innerText()).doesNotContain("Primary task:");

            String subagentHref = page.locator("#chat-messages-list > li .tool-calls > .tool-call > .tool-call-detail > .tool-call-subagent > .tool-call-subagent-button").getAttribute("hx-get");
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
        CodingAgentHarness codingAgentHarness(TaskTool taskTool) {
            return new TestCodingAgentHarness(taskTool);
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
                    return result;
                }

                listener.onTextDelta("Primary task running");
                ToolExecutionResult taskResult = taskTool.execute(Map.of(
                        "agentId", "explore",
                        "task", "Inspect the task flow and report back.",
                        "expectedOutput", "Explore subagent finished"
                ), new ToolExecutionContext(Path.of(request.getWorkspaceRoot()), false, false, 30,
                        request.getSessionId(), "task-1", com.judepereira.jupiter2.agent.catalog.AgentMode.AGENT, "task-1"));

                ToolCallTrace trace = new ToolCallTrace("task-1", "task", Map.of(
                        "agentId", "explore",
                        "task", "Inspect the task flow and report back.",
                        "expectedOutput", "Explore subagent finished"
                ), true, taskResult.getText(), taskResult.getMachine());
                listener.onToolCallTrace(trace);
                listener.onTextDelta("Primary complete");
                AgentTurnResult result = new AgentTurnResult("Primary complete", List.of(trace));
                listener.onComplete(result);
                return result;
            }
        }
    }
}

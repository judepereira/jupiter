package com.judepereira.jupiter.e2e;

import com.judepereira.jupiter.agent.harness.AgentTurnRequest;
import com.judepereira.jupiter.agent.harness.AgentTurnResult;
import com.judepereira.jupiter.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter.agent.harness.ToolCallTrace;
import com.judepereira.jupiter.agent.llm.AgentStreamListener;
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

class ToolCallBundleLiveDedupeE2ETest extends E2ETestSupport {

    @Test
    void toolCallBundleRendersLiveAndAfterReload(@TempDir Path tempDir) throws Exception {
        Path fakeHome = Files.createDirectories(tempDir.resolve("fake-home"));
        Path projectDir = Files.createDirectories(fakeHome.resolve("child-project"));
        Path sqliteDbFile = tempDir.resolve("sqlite-db/jupiter.db");
        Files.createDirectories(sqliteDbFile.getParent());
        Path screenshot = Path.of("target", "tool-call-bundle-e2e.png");
        Files.createDirectories(screenshot.getParent());

        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());

        try (Playwright playwright = Playwright.create(); Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
             RunningApp app = startApp(fakeHome, sqliteDbFile, TestAppConfig.class);
             BrowserContext context = browser.newContext()) {

            Page page = context.newPage();
            page.navigate(app.baseUrl());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).waitFor();

            openProject(page, "Alpha", projectDir);
            page.locator("#chat-input").fill("stream bundled tool calls");
            page.locator("#chat-send-btn").click();

            var bundles = page.locator("#chat-messages-list .tool-call-bundle");
            bundles.waitFor();
            assertThat(bundles).hasCount(1);
            page.waitForFunction("() => document.querySelector('#chat-messages-list .tool-call-bundle .tool-call-bundle-summary .tool-call-name')?.textContent === '4 tools used: read_file (3), list_files'");
            assertThat(bundles.locator(":scope > summary .tool-call-name")).hasText("4 tools used: read_file (3), list_files");

            bundles.locator(":scope > summary").click();
            var innerGroups = bundles.locator(":scope .tool-call");
            assertThat(innerGroups).hasCount(1);
            assertThat(innerGroups.locator(":scope > summary .tool-call-name")).hasText("read_file (2), list_files, read_file");

            page.reload();
            bundles = page.locator("#chat-messages-list .tool-call-bundle");
            bundles.waitFor();
            assertThat(bundles).hasCount(1);
            page.waitForFunction("() => document.querySelector('#chat-messages-list .tool-call-bundle .tool-call-bundle-summary .tool-call-name')?.textContent === '4 tools used: read_file (3), list_files'");
            assertThat(bundles.locator(":scope > summary .tool-call-name")).hasText("4 tools used: read_file (3), list_files");

            bundles.locator(":scope > summary").click();
            innerGroups = bundles.locator(":scope .tool-call");
            assertThat(innerGroups).hasCount(1);
            assertThat(innerGroups.locator(":scope > summary .tool-call-name")).hasText("read_file (2), list_files, read_file");

            captureScreenshot(page, screenshot.getParent(), screenshot.getFileName().toString());
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
                listener.onTextDelta("Thinking");

                ToolCallTrace read1 = trace("read-1", "read_file", Map.of("path", "src/main/java/App.java"));
                ToolCallTrace read2 = trace("read-2", "read_file", Map.of("path", "src/main/resources/application.properties"));
                ToolCallTrace list = trace("list-1", "list_files", Map.of("path", "src/main/java"));
                ToolCallTrace read3 = trace("read-3", "read_file", Map.of("path", "src/test/java/AppTest.java"));

                listener.onToolCallStarted(started(read1));
                listener.onToolCallTrace(read1);
                listener.onToolCallStarted(started(read2));
                listener.onToolCallTrace(read2);
                listener.onToolCallStarted(started(list));
                listener.onToolCallTrace(list);
                listener.onToolCallStarted(started(read3));
                listener.onToolCallTrace(read3);

                AgentTurnResult result = new AgentTurnResult("done", List.of(read1, read2, list, read3));
                listener.onComplete(result);
                return result;
            }

            private static ToolCallTrace trace(String toolCallId, String toolName, Map<String, Object> args) {
                return new ToolCallTrace(toolCallId, toolName, args, true, toolName + " complete", Map.of());
            }

            private static ToolCallTrace started(ToolCallTrace trace) {
                return new ToolCallTrace(trace.getToolCallId(), trace.getToolName(), trace.getArgs(), false, "", Map.of());
            }
        }
    }
}

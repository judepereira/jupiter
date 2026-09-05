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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallBundleLiveLabelE2ETest extends E2ETestSupport {

    @Test
    void newlyCreatedLiveBundleShowsToolName(@TempDir Path tempDir) throws Exception {
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

            page.locator("#chat-input").fill("read a file");
            page.locator("#chat-send-btn").click();

            TestAppConfig.awaitStarted();
            var bundle = page.locator("#chat-messages-list .tool-call-bundle").first();
            bundle.waitFor();
            assertThat(bundle.locator(":scope > summary .tool-call-name")).hasText("Used: read_file");
            TestAppConfig.release();
        } finally {
            TestAppConfig.release();
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestAppConfig {

        private static CountDownLatch started;
        private static CountDownLatch release;

        static void reset() {
            started = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }

        static void awaitStarted() throws InterruptedException {
            assertTrue(started.await(5, TimeUnit.SECONDS), "tool call did not start");
        }

        static void release() {
            release.countDown();
        }

        @Bean
        @Primary
        CodingAgentHarness codingAgentHarness() {
            return new CodingAgentHarness(null, null, null, null, null, null, null, null, new com.judepereira.jupiter.agent.harness.SystemPromptComposer(com.judepereira.jupiter.testsupport.SkillTestSupport.defaultComponents().renderer()), com.judepereira.jupiter.testsupport.SkillTestSupport.defaultComponents().discovery(), com.judepereira.jupiter.testsupport.SkillTestSupport.defaultComponents().resolver(), com.judepereira.jupiter.testsupport.SkillTestSupport.defaultComponents().injector()) {
                @Override
                public AgentTurnResult runTurnStreaming(AgentTurnRequest request, AgentStreamListener listener) {
                    Map<String, Object> args = Map.of("path", "README.md");
                    listener.onToolCallStarted(new ToolCallTrace("read-1", "read_file", args, false, "", Map.of()));
                    started.countDown();
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS), "tool call release timed out");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    ToolCallTrace trace = new ToolCallTrace("read-1", "read_file", args, true, "hello", Map.of());
                    listener.onToolCallTrace(trace);
                    AgentTurnResult result = new AgentTurnResult("done", List.of(trace));
                    listener.onComplete(result);
                    return result;
                }
            };
        }
    }
}

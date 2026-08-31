package com.judepereira.jupiter.e2e;

import com.judepereira.jupiter.agent.harness.AgentTurnRequest;
import com.judepereira.jupiter.agent.harness.AgentTurnResult;
import com.judepereira.jupiter.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter.agent.harness.ToolCallTrace;
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
import java.util.List;
import java.util.Map;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

class DisplayImageLiveDedupeE2ETest extends E2ETestSupport {

    @Test
    void displayImageRendersOnceLiveAndAfterReload(@TempDir Path tempDir) throws Exception {
        Path fakeHome = Files.createDirectories(tempDir.resolve("fake-home"));
        Path projectDir = Files.createDirectories(fakeHome.resolve("child-project"));
        Path sqliteDbFile = tempDir.resolve("sqlite-db/jupiter.db");
        Files.createDirectories(sqliteDbFile.getParent());
        createImageFile(projectDir, "images/cat.png");

        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());

        try (RunningApp app = startApp(fakeHome, sqliteDbFile, TestAppConfig.class);
             BrowserContext context = newBrowserContext()) {

            Page page = context.newPage();
            page.navigate(app.baseUrl());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).waitFor();

            openProject(page, "Alpha", projectDir);
            page.locator("#chat-input").fill("show the image");
            page.locator("#chat-send-btn").click();

            var call = page.locator("#chat-messages-list .tool-call-call[data-tool-call-id='display-image-1']");
            call.waitFor();
            assertThat(call.locator(".tool-call-image-preview")).hasCount(1);
            assertThat(call.locator(".tool-call-image-preview img")).hasCount(1);
            org.assertj.core.api.Assertions.assertThat((String) call.locator(".tool-call-image-preview figcaption > span").evaluate("el => el.textContent")).isEqualTo("Cat");
            org.assertj.core.api.Assertions.assertThat((String) call.locator(".tool-call-image-preview figcaption > small").evaluate("el => el.textContent")).isEqualTo("images/cat.png");

            page.reload();
            var reloadedCall = page.locator("#chat-messages-list .tool-call-call[data-tool-call-id='display-image-1']");
            reloadedCall.waitFor();
            assertThat(reloadedCall.locator(".tool-call-image-preview")).hasCount(1);
            assertThat(reloadedCall.locator(".tool-call-image-preview img")).hasCount(1);
            org.assertj.core.api.Assertions.assertThat((String) reloadedCall.locator(".tool-call-image-preview figcaption > span").evaluate("el => el.textContent")).isEqualTo("Cat");
            org.assertj.core.api.Assertions.assertThat((String) reloadedCall.locator(".tool-call-image-preview figcaption > small").evaluate("el => el.textContent")).isEqualTo("images/cat.png");
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
                Map<String, Object> args = Map.of(
                        "path", "images/cat.png",
                        "alt", "Cat"
                );
                ToolCallTrace started = new ToolCallTrace("display-image-1", "display_image", args, false, "", Map.of());
                listener.onTextDelta("Displaying image");
                listener.onToolCallStarted(started);

                ToolCallTrace trace = new ToolCallTrace("display-image-1", "display_image", args, true,
                        "Displayed image: images/cat.png",
                        Map.of(
                                "displayType", "image",
                                "path", "images/cat.png",
                                "alt", "Cat",
                                "mediaType", "image/png"
                        ));
                listener.onToolCallTrace(trace);
                AgentTurnResult result = new AgentTurnResult("done", List.of(trace));
                listener.onComplete(result);
                return result;
            }
        }
    }
}

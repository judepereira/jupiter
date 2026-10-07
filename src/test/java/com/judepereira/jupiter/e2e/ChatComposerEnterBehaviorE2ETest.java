package com.judepereira.jupiter.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.judepereira.jupiter.agent.harness.AgentTurnRequest;
import com.judepereira.jupiter.agent.harness.AgentTurnResult;
import com.judepereira.jupiter.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter.agent.harness.SystemPromptComposer;
import com.judepereira.jupiter.agent.llm.AgentStreamListener;
import com.judepereira.jupiter.testsupport.SkillTestSupport;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.ViewportSize;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

class ChatComposerEnterBehaviorE2ETest extends E2ETestSupport {

    private static final String ASSISTANT_REPLY = "Deterministic **assistant** reply\n\n- copied as Markdown";

    @Test
    void desktopEnterSubmitsMessageAndCopiesAssistantResponse(@TempDir Path tempDir) throws Exception {
        Path fakeHome = Files.createDirectories(tempDir.resolve("fake-home"));
        Path projectDir = Files.createDirectories(fakeHome.resolve("child-project"));
        Path sqliteDbFile = tempDir.resolve("sqlite-db/jupiter.db");
        Files.createDirectories(sqliteDbFile.getParent());

        try (RunningApp app = startAppWithConnectedOpenAi(fakeHome, sqliteDbFile, TestAppConfig.class);
                BrowserContext context = newBrowserContext()) {
            context.grantPermissions(List.of("clipboard-read", "clipboard-write"),
                    new BrowserContext.GrantPermissionsOptions().setOrigin(app.baseUrl()));
            Page page = context.newPage();

            page.navigate(app.baseUrl());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).waitFor();
            openProject(page, "Alpha", projectDir);

            page.locator("#chat-input").fill("hello there");
            page.locator("#chat-input").press("Enter");

            var userMessages = page.locator("#chat-messages-list li[data-role='user']");
            var assistantMessages = page.locator("#chat-messages-list li[data-role='assistant']");
            assertThat(userMessages).hasCount(1);
            assertThat(assistantMessages).hasCount(1);
            assertThat(userMessages.first().locator(".chat-message-text")).hasText("hello there");
            assertThat(assistantMessages.first().locator(".chat-message-text"))
                    .containsText("Deterministic assistant reply");
            var assistantRow = assistantMessages.first();
            var forkButton = assistantRow.locator(".chat-message-fork-button");
            assertThat(assistantRow.locator(".chat-message-subtitle")).containsText("Fork");
            Assertions.assertThat(forkButton.getAttribute("hx-post"))
                    .isEqualTo("/ui/chat/fork/" + assistantRow.getAttribute("data-id"));
            assertThat(forkButton).hasAttribute("hx-target", "#shell");
            assertThat(forkButton).hasAttribute("hx-swap", "none");
            assertThat(page.locator("#chat-input")).hasValue("");

            var copyButton = assistantRow.locator(".chat-message-copy-button");
            assertThat(copyButton).hasAttribute("aria-label", "Copy response");
            page.evaluate("() => navigator.clipboard.writeText('clipboard sentinel')");
            int textareaCountBefore = page.locator("textarea").count();
            copyButton.click();
            assertEquals(ASSISTANT_REPLY, page.evaluate("() => navigator.clipboard.readText()"));
            assertThat(copyButton).hasAttribute("aria-label", "Copied");
            assertThat(page.locator("textarea")).hasCount(textareaCountBefore);
            assertThat(copyButton).isFocused();
        }
    }

    @Test
    void desktopAltEnterInsertsNewlineWithoutSubmitting(@TempDir Path tempDir) throws Exception {
        Path fakeHome = Files.createDirectories(tempDir.resolve("fake-home"));
        Path projectDir = Files.createDirectories(fakeHome.resolve("child-project"));
        Path sqliteDbFile = tempDir.resolve("sqlite-db/jupiter.db");
        Files.createDirectories(sqliteDbFile.getParent());

        try (RunningApp app = startAppWithConnectedOpenAi(fakeHome, sqliteDbFile, TestAppConfig.class);
                BrowserContext context = newBrowserContext()) {
            Page page = context.newPage();

            page.navigate(app.baseUrl());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).waitFor();
            openProject(page, "Alpha", projectDir);

            int initialMessageCount = page.locator("#chat-messages-list li").count();
            page.locator("#chat-input").fill("hello");
            page.locator("#chat-input").press("Alt+Enter");

            assertThat(page.locator("#chat-input")).hasValue("hello\n");
            assertThat(page.locator("#chat-messages-list li")).hasCount(initialMessageCount);
        }
    }

    @Test
    void mobileEnterInsertsNewlineWithoutSubmitting(@TempDir Path tempDir) throws Exception {
        Path fakeHome = Files.createDirectories(tempDir.resolve("fake-home"));
        Path projectDir = Files.createDirectories(fakeHome.resolve("child-project"));
        Path sqliteDbFile = tempDir.resolve("sqlite-db/jupiter.db");
        Files.createDirectories(sqliteDbFile.getParent());

        try (RunningApp app = startAppWithConnectedOpenAi(fakeHome, sqliteDbFile, TestAppConfig.class);
                BrowserContext context = newBrowserContext(new Browser.NewContextOptions()
                        .setViewportSize(new ViewportSize(390, 844)).setIsMobile(true).setHasTouch(true))) {
            Page page = context.newPage();

            page.navigate(app.baseUrl());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).waitFor();
            openProject(page, "Alpha", projectDir);

            int initialMessageCount = page.locator("#chat-messages-list li").count();
            assertTrue((Boolean) page.evaluate("() => window.matchMedia('(max-width: 600px)').matches"));

            page.locator("#chat-input").fill("hello");
            page.locator("#chat-input").press("Enter");

            assertThat(page.locator("#chat-input")).hasValue("hello\n");
            assertThat(page.locator("#chat-messages-list li")).hasCount(initialMessageCount);
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
                super(null, null, null, null, null, null, null, null, null,
                        new SystemPromptComposer(SkillTestSupport.defaultComponents().renderer()),
                        SkillTestSupport.defaultComponents().discovery(),
                        SkillTestSupport.defaultComponents().resolver(),
                        SkillTestSupport.defaultComponents().injector());
            }

            @Override
            public AgentTurnResult runTurnStreaming(AgentTurnRequest request, AgentStreamListener listener) {
                listener.onTextDelta(ASSISTANT_REPLY);
                AgentTurnResult result = new AgentTurnResult(ASSISTANT_REPLY, List.of());
                listener.onComplete(result);
                return result;
            }
        }
    }
}

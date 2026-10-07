package com.judepereira.jupiter.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.judepereira.jupiter.agent.harness.AgentTurnRequest;
import com.judepereira.jupiter.agent.harness.AgentTurnResult;
import com.judepereira.jupiter.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter.agent.harness.SystemPromptComposer;
import com.judepereira.jupiter.agent.llm.AgentStreamListener;
import com.judepereira.jupiter.testsupport.SkillTestSupport;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

class ChatAgentSelectionReloadE2ETest extends E2ETestSupport {

    private static final String ASSISTANT_REPLY = "Deterministic assistant reply";

    @Test
    void selectedAgentModelAndThinkingSurvivePageReload(@TempDir Path tempDir) throws Exception {
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
            assertChatControls(page);

            page.locator("#chat-agent-select").selectOption("engineer");
            page.locator("#chat-model-select").selectOption("openai/gpt-5.6-sol");
            page.locator("#chat-thinking-select").selectOption("HIGH");
            assertSelectedControls(page);
            page.locator("#chat-input").fill("hello there");
            page.locator("#chat-send-btn").click();
            Locator latestAssistant = page.locator("#chat-messages-list li[data-role='assistant']").last();
            assertThat(latestAssistant.locator(".chat-message-text")).hasText(ASSISTANT_REPLY);
            assertThat(latestAssistant).hasAttribute("data-agent-id", "engineer");
            assertThat(latestAssistant).hasAttribute("data-model-id", "openai/gpt-5.6-sol");
            assertThat(latestAssistant).hasAttribute("data-thinking-level", "HIGH");
            assertSelectedControls(page);

            page.reload();
            assertChatControls(page);
            assertSelectedControls(page);
            assertThat(page.locator("#chat-controls")).hasAttribute("data-model-explicit", "true");
        }
    }

    private static void assertChatControls(Page page) {
        page.locator("#chat-agent-select").waitFor();
        page.locator("#chat-model-select").waitFor();
        page.locator("#chat-thinking-select").waitFor();
    }

    private static void assertSelectedControls(Page page) {
        assertThat(page.locator("#chat-agent-select")).hasValue("engineer");
        assertThat(page.locator("#chat-model-select")).hasValue("openai/gpt-5.6-sol");
        assertThat(page.locator("#chat-thinking-select")).hasValue("HIGH");
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

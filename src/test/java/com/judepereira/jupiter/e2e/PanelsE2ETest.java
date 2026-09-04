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
import java.util.List;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanelsE2ETest extends E2ETestSupport {

    private static final String ASSISTANT_REPLY = "Deterministic assistant reply";

    @Test
    void toggleReviewAndTerminalPanels(@TempDir Path tempDir) throws Exception {
        Path fakeHome = Files.createDirectories(tempDir.resolve("fake-home"));
        Path projectDir = Files.createDirectories(fakeHome.resolve("child-project"));
        Path sqliteDbFile = tempDir.resolve("sqlite-db/jupiter.db");
        Files.createDirectories(sqliteDbFile.getParent());
        Path screenshotsDir = Files.createDirectories(Path.of("target", "playwright-screenshots", "PanelsE2ETest"));

        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());

        try {
            try (RunningApp app = startApp(fakeHome, sqliteDbFile, TestAppConfig.class);
                 BrowserContext context = newBrowserContext()) {
                Page page = context.newPage();

                page.navigate(app.baseUrl());
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).waitFor();

                openProject(page, "Alpha", projectDir);
                captureScreenshot(page, screenshotsDir, "01-project-opened.png");

                assertThat(page.locator("#review")).hasCount(1);
                assertThat(page.locator("#review")).not().isVisible();
                assertThat(page.locator("#bottom-panel")).hasCount(1);
                assertThat(page.locator("#bottom-panel")).not().isVisible();

                page.locator("#toggle-terminal-rail-btn").click();

                assertTerminalPanelVisible(page);
                assertThat(page.locator("#terminal-panel-divider")).isVisible();
                assertThat(page.locator("#review")).not().isVisible();
                runTerminalCommandAndAssertOutput(page);

                double initialBottomPanelHeight = page.locator("#bottom-panel").boundingBox().height;
                dragTerminalPanelDivider(page, 40);
                double resizedBottomPanelHeight = page.locator("#bottom-panel").boundingBox().height;
                assertNotEquals(initialBottomPanelHeight, resizedBottomPanelHeight);

                captureScreenshot(page, screenshotsDir, "02-terminal-open.png");

                page.locator("#toggle-review-rail-btn").click();

                assertThat(page.locator("#review")).isVisible();
                assertThat(page.locator("#review .review-header")).isVisible();
                assertTerminalPanelVisible(page);
                assertThat(page.locator("#review")).hasCount(1);
                assertThat(page.locator("#bottom-panel")).hasCount(1);
                captureScreenshot(page, screenshotsDir, "03-review-open.png");

                page.locator("#toggle-review-rail-btn").click();

                assertThat(page.locator("#review")).not().isVisible();
                assertTerminalPanelVisible(page);
                assertThat(page.locator("#review")).hasCount(1);
                assertThat(page.locator("#bottom-panel")).hasCount(1);
                captureScreenshot(page, screenshotsDir, "04-review-closed.png");

                page.locator("#toggle-terminal-rail-btn").click();

                assertThat(page.locator("#bottom-panel")).not().isVisible();
            }
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    @Test
    void keyboardShortcutsToggleTerminalAndCycleChatSelectors(@TempDir Path tempDir) throws Exception {
        Path fakeHome = Files.createDirectories(tempDir.resolve("fake-home"));
        Path projectDir = Files.createDirectories(fakeHome.resolve("child-project"));
        Path sqliteDbFile = tempDir.resolve("sqlite-db/jupiter.db");
        Files.createDirectories(sqliteDbFile.getParent());

        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());

        try {
            try (RunningApp app = startApp(fakeHome, sqliteDbFile, TestAppConfig.class);
                 BrowserContext context = newBrowserContext()) {
                Page page = context.newPage();

                page.navigate(app.baseUrl());
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).waitFor();

                openProject(page, "Alpha", projectDir);

                assertThat(page.locator("#bottom-panel")).not().isVisible();
                page.waitForResponse(
                        response -> response.url().contains("/ui/panel/terminal") && response.status() == 200,
                        () -> page.keyboard().press("Control+`"));
                assertTerminalPanelVisible(page);
                page.waitForResponse(
                        response -> response.url().contains("/ui/panel/terminal") && response.status() == 200,
                        () -> page.keyboard().press("Control+`"));
                assertThat(page.locator("#bottom-panel")).not().isVisible();

                assertShortcutCyclesSelect(page, "#chat-agent-select", "Meta+.");
                assertShortcutCyclesSelect(page, "#chat-thinking-select", "Meta+Shift+D");
            }
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    private void runTerminalCommandAndAssertOutput(Page page) {
        assertThat(page.locator(".terminal-mount .xterm")).isVisible();
        page.locator(".terminal-mount").click();
        page.keyboard().type("echo \"hello world\"");
        page.keyboard().press("Enter");

        assertThat(page.locator(".terminal-mount .xterm-rows")).containsText("hello world");
    }

    private void assertTerminalPanelVisible(Page page) {
        assertThat(page.locator("#bottom-panel")).isVisible();
        assertThat(page.locator("#bottom-panel .terminal-shell")).isVisible();
        assertThat(page.locator("#bottom-panel .terminal-tabs")).isVisible();
        assertThat(page.locator("#bottom-panel .terminal-body")).isVisible();
    }

    private void assertShortcutCyclesSelect(Page page, String selectSelector, String shortcut) {
        @SuppressWarnings("unchecked")
        List<String> values = (List<String>) page.locator(selectSelector + " option").evaluateAll("options => options.map(option => option.value)");
        String currentValue = page.locator(selectSelector).inputValue();
        int currentIndex = values.indexOf(currentValue);
        assertTrue(currentIndex >= 0, () -> selectSelector + " current value not found in options: " + currentValue + " / " + values);

        String expectedNextValue = values.get((currentIndex + 1) % values.size());
        page.keyboard().press(shortcut);
        assertThat(page.locator(selectSelector)).hasValue(expectedNextValue);
    }

    private void dragTerminalPanelDivider(Page page, double deltaY) {
        var dividerBox = page.locator("#terminal-panel-divider").boundingBox();
        page.mouse().move(dividerBox.x + dividerBox.width / 2, dividerBox.y + dividerBox.height / 2);
        page.mouse().down();
        page.mouse().move(dividerBox.x + dividerBox.width / 2, dividerBox.y + dividerBox.height / 2 + deltaY);
        page.mouse().up();
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
                super(null, null, null, null, null, null, null, null, new com.judepereira.jupiter.agent.harness.SystemPromptComposer(new com.judepereira.jupiter.agent.skill.SkillCatalogRenderer()), new com.judepereira.jupiter.agent.skill.SkillDiscoveryService(new com.judepereira.jupiter.agent.skill.SkillParser(), System.getProperty("user.home")), new com.judepereira.jupiter.agent.skill.SkillInvocationResolver(), new com.judepereira.jupiter.agent.skill.SkillContextInjector(new com.judepereira.jupiter.agent.skill.SkillParser()));
            }

            @Override
            public AgentTurnResult runTurnStreaming(AgentTurnRequest request, AgentStreamListener listener) {
                listener.onTextDelta(ASSISTANT_REPLY);
                AgentTurnResult result = new AgentTurnResult(ASSISTANT_REPLY, java.util.List.of());
                listener.onComplete(result);
                return result;
            }
        }
    }
}

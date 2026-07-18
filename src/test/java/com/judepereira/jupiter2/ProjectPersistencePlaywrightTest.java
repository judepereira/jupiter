package com.judepereira.jupiter2;

import com.judepereira.jupiter2.agent.harness.AgentTurnRequest;
import com.judepereira.jupiter2.agent.harness.AgentTurnResult;
import com.judepereira.jupiter2.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter2.agent.llm.AgentStreamListener;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

class ProjectPersistencePlaywrightTest {

    private static final String ASSISTANT_REPLY = "Deterministic assistant reply";

    @Test
    void addProjectChatAndPersistenceSurviveRestart(@TempDir Path tempDir) throws Exception {
        Path fakeHome = Files.createDirectories(tempDir.resolve("fake-home"));
        Path projectDir = Files.createDirectories(fakeHome.resolve("child-project"));
        Path dbFile = tempDir.resolve("h2db/jupiter");
        Files.createDirectories(dbFile.getParent());
        Path screenshotsDir = Files.createDirectories(Path.of("target", "playwright-screenshots", "ProjectPersistencePlaywrightTest"));

        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());

        try (Playwright playwright = Playwright.create(); Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            try (RunningApp first = startApp(fakeHome, dbFile);
                 BrowserContext context = browser.newContext()) {
                Page page = context.newPage();

                page.navigate(first.baseUrl());
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).waitFor();
                captureScreenshot(page, screenshotsDir, "01-initial-load.png");

                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).click();
                assertThat(page.locator("#project-modal")).isVisible();
                captureScreenshot(page, screenshotsDir, "02-project-modal-open.png");

                page.locator(".project-form-field input[name='name']").fill("Alpha");
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("child-project")).click();
                page.locator(".project-form-field input[name='name']").fill("Alpha");
                assertThat(page.locator("#project-path-input")).hasValue(projectDir.toAbsolutePath().normalize().toString());
                captureScreenshot(page, screenshotsDir, "03-directory-selected.png");
                page.locator(".project-form-field input[name='name']").fill("Alpha");
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Open")).click();

                assertThat(page.locator(".project-tab-group.active .project-tab-label")).hasText("Alpha");
                captureScreenshot(page, screenshotsDir, "04-project-opened.png");

                String userMessage = "hello there";
                page.locator("#chat-input").fill(userMessage);
                page.locator("#chat-send-btn").click();

                assertThat(page.locator("#chat-messages-list li")).hasCount(3);
                assertThat(page.locator("#chat-messages-list li").nth(1).locator(".chat-message-text")).hasText(userMessage);
                assertThat(page.locator("#chat-messages-list li").nth(2).locator(".chat-message-text")).hasText(ASSISTANT_REPLY);
                captureScreenshot(page, screenshotsDir, "05-chat-response.png");
            }

            try (RunningApp second = startApp(fakeHome, dbFile);
                 BrowserContext context = browser.newContext()) {
                Page page = context.newPage();

                page.navigate(second.baseUrl());

                assertThat(page.locator(".project-tab-group.active .project-tab-label")).hasText("Alpha");
                assertThat(page.locator("#chat-messages-list li")).hasCount(3);
                assertThat(page.locator("#chat-messages-list li").nth(1).locator(".chat-message-text")).hasText("hello there");
                assertThat(page.locator("#chat-messages-list li").nth(2).locator(".chat-message-text")).hasText(ASSISTANT_REPLY);
                captureScreenshot(page, screenshotsDir, "06-after-restart.png");
            }
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    private static RunningApp startApp(Path fakeHome, Path dbFile) {
        String jdbcUrl = "jdbc:h2:file:" + dbFile.toAbsolutePath().normalize() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
        ConfigurableApplicationContext context = new SpringApplicationBuilder(JupiterV2Application.class, TestAppConfig.class)
                .web(WebApplicationType.SERVLET)
                .properties(
                        "server.port=0",
                        "spring.datasource.url=" + jdbcUrl,
                        "spring.datasource.driver-class-name=org.h2.Driver",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "spring.flyway.enabled=true",
                        "spring.docker.compose.enabled=false",
                        "agent.workspace-root=" + fakeHome.toAbsolutePath().normalize(),
                        "openai.api-key=test"
                )
                .run();

        Integer port = context.getEnvironment().getProperty("local.server.port", Integer.class);
        if (port == null) {
            throw new IllegalStateException("Missing local.server.port");
        }
        return new RunningApp(context, "http://localhost:" + port);
    }

    private static void captureScreenshot(Page page, Path screenshotsDir, String fileName) {
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(screenshotsDir.resolve(fileName))
                .setFullPage(true));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestAppConfig {

        @Bean
        @Primary
        CodingAgentHarness codingAgentHarness() {
            return new CodingAgentHarness(null, null, null) {
                @Override
                public AgentTurnResult runTurnStreaming(AgentTurnRequest request, AgentStreamListener listener) {
                    listener.onTextDelta(ASSISTANT_REPLY);
                    AgentTurnResult result = new AgentTurnResult(ASSISTANT_REPLY, java.util.List.of());
                    listener.onComplete(result);
                    return result;
                }
            };
        }
    }

    private record RunningApp(ConfigurableApplicationContext context, String baseUrl) implements AutoCloseable {
        @Override
        public void close() {
            context.close();
        }
    }
}

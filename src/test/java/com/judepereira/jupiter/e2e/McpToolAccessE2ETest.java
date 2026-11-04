package com.judepereira.jupiter.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.judepereira.jupiter.agent.mcp.McpClientFactory;
import com.judepereira.jupiter.agent.mcp.McpProjectMcpServerRuntimeManager;
import com.judepereira.jupiter.agent.tools.ToolExecutionContext;
import com.judepereira.jupiter.persistence.AppStateService;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

class McpToolAccessE2ETest extends E2ETestSupport {

    @Test
    void projectToolAccessIsPersistedAndStaleExecutorsAreBlocked(@TempDir Path tempDir) throws Exception {
        Path fakeHome = Files.createDirectories(tempDir.resolve("fake-home"));
        Path projectDir = Files.createDirectories(fakeHome.resolve("child-project"));
        Path dbFile = tempDir.resolve("sqlite-db/jupiter.db");
        Files.createDirectories(dbFile.getParent());
        Path screenshots = Path.of("target");
        Files.createDirectories(screenshots);

        try (RunningApp app = startApp(fakeHome, dbFile, FakeMcpConfiguration.class);
                BrowserContext context = newBrowserContext()) {
            Page page = context.newPage();
            page.navigate(app.baseUrl());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).waitFor();
            openProject(page, "Alpha", projectDir);

            AppStateService state = app.context().getBean(AppStateService.class);
            long activeProjectId = state.loadViewData().projects().stream()
                    .filter(project -> "Alpha".equals(project.name())).findFirst().orElseThrow().id();
            state.createMcpServer("Example", "http://example.test/mcp", true, List.of(), List.of(activeProjectId));
            McpProjectMcpServerRuntimeManager runtimeManager = app.context()
                    .getBean(McpProjectMcpServerRuntimeManager.class);
            runtimeManager.reloadProject(activeProjectId);

            page.setViewportSize(1440, 1050);
            Locator modal = page.locator("#settings-modal");
            openMcpSettings(page);
            Locator tools = modal.locator(".settings-mcp-tools");
            PlaywrightAssertions.assertThat(tools).isVisible();
            PlaywrightAssertions.assertThat(tools).not().hasAttribute("open", "");
            tools.locator("summary").click();
            Locator read = modal.locator("[data-mcp-tool-name][value='read_items']");
            Locator write = modal.locator("[data-mcp-tool-name][value='write_items']");
            PlaywrightAssertions.assertThat(read).isChecked();
            PlaywrightAssertions.assertThat(write).isChecked();

            var staleExecutor = runtimeManager.snapshot(activeProjectId).executors().get("mcp__example__write_items");
            assertThat(staleExecutor).isNotNull();
            write.uncheck();
            tools.scrollIntoViewIfNeeded();
            page.screenshot(new Page.ScreenshotOptions().setPath(screenshots.resolve("mcp-tool-access-desktop.png"))
                    .setFullPage(true));
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Apply MCP Catalog")).click();
            PlaywrightAssertions.assertThat(modal).not().isVisible();

            McpProjectMcpServerRuntimeManager manager = runtimeManager;
            openMcpSettings(page);
            tools = modal.locator(".settings-mcp-tools");
            tools.locator("summary").click();
            read = modal.locator("[data-mcp-tool-name][value='read_items']");
            write = modal.locator("[data-mcp-tool-name][value='write_items']");
            PlaywrightAssertions.assertThat(read).isChecked();
            PlaywrightAssertions.assertThat(write).not().isChecked();

            assertThat(manager.snapshot(activeProjectId).toolDefinitions()).extracting("name")
                    .containsExactly("mcp__example__read_items");
            assertThrows(IllegalStateException.class,
                    () -> staleExecutor.execute(Map.of(), new ToolExecutionContext(null, false, false, 0, null, null,
                            null, "stale-call", Map.of(), Set.of(), null, null)));

            write.check();
            PlaywrightAssertions.assertThat(read).isChecked();
            PlaywrightAssertions.assertThat(write).isChecked();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Apply MCP Catalog")).click();
            PlaywrightAssertions.assertThat(modal).not().isVisible();
            assertThat(manager.snapshot(activeProjectId).toolDefinitions()).extracting("name")
                    .containsExactly("mcp__example__read_items", "mcp__example__write_items");
            openMcpSettings(page);
            tools = modal.locator(".settings-mcp-tools");
            tools.locator("summary").click();
            read = modal.locator("[data-mcp-tool-name][value='read_items']");
            write = modal.locator("[data-mcp-tool-name][value='write_items']");
            PlaywrightAssertions.assertThat(read).isChecked();
            PlaywrightAssertions.assertThat(write).isChecked();
            page.setViewportSize(390, 844);
            tools.scrollIntoViewIfNeeded();
            page.screenshot(new Page.ScreenshotOptions().setPath(screenshots.resolve("mcp-tool-access-mobile.png"))
                    .setFullPage(true));
        }
    }

    private static void openMcpSettings(Page page) {
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Settings")).click();
        page.locator("#settings-mcp-servers-tab").click();
        PlaywrightAssertions.assertThat(page.locator("#settings-mcp-servers")).isVisible();
    }

    @TestConfiguration
    static class FakeMcpConfiguration {
        @Bean
        @Primary
        McpClientFactory fakeMcpClientFactory() {
            McpClient client = mock(McpClient.class);
            try {
                when(client.listTools()).thenReturn(List.of(tool("read_items"), tool("write_items")));
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
            return (clientKey, url, headers, listener) -> client;
        }

        private static ToolSpecification tool(String name) {
            try {
                return ToolSpecification.builder().name(name).description(name)
                        .parameters(JsonObjectSchema.builder().build()).build();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }
    }
}

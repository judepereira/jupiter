package com.judepereira.jupiter.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.FormData;
import com.microsoft.playwright.options.RequestOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InboundMcpAuthorizationE2ETest extends E2ETestSupport {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void browserManagedProjectAndToolScopesApplyImmediatelyWithIndependentEditAndRevocation(@TempDir Path tempDir)
            throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("home"));
        Path projectA = Files.createDirectories(home.resolve("project-a"));
        Path projectB = Files.createDirectories(home.resolve("project-b"));
        initGitRepoWithInitialCommit(projectA);
        initGitRepoWithInitialCommit(projectB);
        try (RunningApp app = startApp(home, tempDir.resolve("state.db"));
                BrowserContext browser = newBrowserContext()) {
            Page page = browser.newPage();
            page.navigate(app.baseUrl());
            assertInvalidOriginsRejected(page, app.baseUrl(), "jup_test-token");
            openProject(page, "Authorization A", projectA);
            openProject(page, "Authorization B", projectB);
            settings(page);
            PlaywrightAssertions.assertThat(page.locator("#inbound-mcp-settings p.alert-warning code")).hasCount(0);

            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create New MCP Token")).click();
            Locator create = page.locator("#inbound-mcp-create-form form");
            create.locator("input[name='name']").fill("restricted");
            create.locator("input[name='allProjects']").uncheck();
            create.locator("input[name='projectIds']").nth(0).check();
            create.locator("input[name='projectIds']").nth(1).uncheck();
            for (Locator tool : create.locator("input[name='tools']").all()) {
                tool.uncheck();
            }
            create.locator("input[name='tools'][value='list_projects']").check();
            create.locator("input[name='tools'][value='list_agents']").check();
            settingsAction(page, "/create", create.locator("button[type='submit']")::click);
            String restrictedToken = tokenText(page);
            McpHttpTestClient restricted = new McpHttpTestClient(app.baseUrl(), restrictedToken);
            restricted.initialize("authorization-e2e");
            JsonNode listed = restricted.call("list_projects", Map.of());
            JsonNode visible = result(listed).path("result");
            assertThat(visible).hasSize(1);
            assertThat(visible.get(0).path("name").asText()).isEqualTo("Authorization A");
            long projectAId = projectIdFromForm(page, "Authorization A");
            JsonNode allowed = restricted.call("list_agents", Map.of("projectId", projectAId));
            assertThat(result(allowed).path("result")).isNotEmpty();
            long projectBId = projectIdFromForm(page, "Authorization B");
            JsonNode denied = restricted.call("list_agents", Map.of("projectId", projectBId));
            assertThat(denied.path("result").path("isError").asBoolean()).isTrue();

            PlaywrightAssertions.assertThat(page.locator("#inbound-mcp-create-form")).not().isVisible();
            Locator card = card(page, "restricted");
            card.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("restricted").setExact(true))
                    .click();
            Locator editForm = card.locator("form").filter(new Locator.FilterOptions()
                    .setHas(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Save changes"))));

            editForm.locator("input[name='tools'][value='list_agents']").uncheck();
            editForm.locator("input[name='tools'][value='list_projects']").check();
            settingsAction(page, "/edit", () -> editForm
                    .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Save changes")).click());
            PlaywrightAssertions.assertThat(page.locator("#inbound-mcp-settings .alert-warning code")).hasCount(0);
            card = card(page, "restricted");
            JsonNode changed = restricted.call("list_agents", Map.of("projectId", projectAId));
            JsonNode changedError = changed.path("result").path("structuredContent").path("error");
            assertThat(changed.path("result").path("isError").asBoolean()).isTrue();
            assertThat(changedError.path("code").asText()).isEqualTo("RESOURCE_UNAVAILABLE");
            assertThat(changedError.path("message").asText()).isEqualTo("Resource unavailable");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create New MCP Token")).click();
            Locator independent = page.locator("#inbound-mcp-create-form form");
            independent.locator("input[name='name']").fill("independent");
            independent.locator("input[name='allProjects']").check();
            settingsAction(page, "/create", independent.locator("button[type='submit']")::click);
            String independentToken = tokenText(page);
            Locator restrictedCard = card(page, "restricted");
            Locator restrictedHeader = restrictedCard.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("restricted").setExact(true));
            restrictedHeader.click();
            PlaywrightAssertions.assertThat(restrictedHeader).hasAttribute("aria-expanded", "true");
            Locator revoke = card(page, "restricted").getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Revoke"));
            page.onceDialog(dialog -> dialog.accept());
            armRequestCompleted(page, "/revoke");
            page.waitForResponse(response -> response.url().endsWith("/revoke")
                    && response.request().method().equals("POST") && response.status() == 200, revoke::click);
            waitForRequestCompleted(page, "/revoke");
            PlaywrightAssertions.assertThat(page.locator("#inbound-mcp-settings article")
                    .filter(new Locator.FilterOptions().setHasText("restricted"))).hasCount(0);
            assertThat(restricted.httpStatus("tools/list")).isEqualTo(401);
            McpHttpTestClient allProjects = new McpHttpTestClient(app.baseUrl(), independentToken);
            allProjects.initialize("authorization-e2e");
            JsonNode futureProjectList = allProjects.call("list_projects", Map.of());
            assertThat(result(futureProjectList).path("result")).hasSizeGreaterThanOrEqualTo(2);
        }
    }

    private static void assertInvalidOriginsRejected(Page page, String baseUrl, String token) {
        var request = page.request();
        FormData form = FormData.create().set("name", "blocked").set("allProjects", "true");
        String[] origins = {"https://foreign.example", "not-origin", baseUrl + "/path", "https://user@example.com"};
        for (String origin : origins) {
            var settingsResponse = request.post(baseUrl + "/ui/settings/inbound-mcp/create",
                    RequestOptions.create().setHeader("Origin", origin).setForm(form));
            assertThat(settingsResponse.status()).isEqualTo(403);
            var mcpResponse = request.post(baseUrl + "/mcp",
                    RequestOptions.create().setHeader("Origin", origin).setHeader("Authorization", "Bearer " + token)
                            .setHeader("Accept", "application/json, text/event-stream")
                            .setHeader("Content-Type", "application/json")
                            .setData("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}"));
            assertThat(mcpResponse.status()).isEqualTo(403);
        }
    }

    private static void settingsAction(Page page, String path, Runnable action) {
        armRequestCompleted(page, path);
        page.waitForResponse(response -> response.url().endsWith(path) && response.request().method().equals("POST")
                && response.status() == 200, action);
        waitForRequestCompleted(page, path);
    }

    private static void armRequestCompleted(Page page, String path) {
        page.evaluate("""
                path => {
                    window.__settingsRequestCompleted = null;
                    document.addEventListener('htmx:finally:request', event => {
                        const requestAction = event.detail.ctx.request.action;
                        if (requestAction.endsWith(path)) {
                            window.__settingsRequestCompleted = path;
                        }
                    });
                }
                """, path);
    }

    private static void waitForRequestCompleted(Page page, String path) {
        page.waitForFunction("path => window.__settingsRequestCompleted === path", path);
    }

    private static void settings(Page page) {
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Settings")).click();
        page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("Inbound MCP")).click();
        page.locator("#inbound-mcp-settings").waitFor();
    }

    private static Locator card(Page page, String name) {
        return page.locator("#inbound-mcp-settings article").filter(new Locator.FilterOptions()
                .setHas(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(name).setExact(true))));
    }

    private static String tokenText(Page page) {
        Matcher matcher = Pattern.compile("jup_[A-Za-z0-9_-]+")
                .matcher(page.locator("#inbound-mcp-settings").textContent());
        if (!matcher.find()) {
            throw new AssertionError("Credential token was not displayed");
        }
        return matcher.group();
    }

    private static long projectIdFromForm(Page page, String name) {
        Locator checkbox = page.locator("#inbound-mcp-create-form form input[name='projectIds']")
                .filter(new Locator.FilterOptions());
        for (Locator candidate : checkbox.all()) {
            if (candidate.locator("xpath=..").textContent().contains(name)) {
                return Long.parseLong(candidate.getAttribute("value"));
            }
        }
        throw new AssertionError("Project missing from settings: " + name);
    }

    private static JsonNode result(JsonNode response) throws Exception {
        JsonNode content = response.path("result").path("content");
        if (response.path("result").path("isError").asBoolean()) {
            return response.path("result");
        }
        return JSON.readTree(content.get(0).path("text").asText());
    }

}

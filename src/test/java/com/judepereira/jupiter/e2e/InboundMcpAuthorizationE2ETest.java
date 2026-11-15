package com.judepereira.jupiter.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;
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
    void browserManagedProjectAndToolScopesApplyImmediatelyAndRotationRevocationAreIndependent(@TempDir Path tempDir)
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
            openProject(page, "Authorization A", projectA);
            openProject(page, "Authorization B", projectB);
            settings(page);

            Locator create = page.locator("#inbound-mcp-settings > form");
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
            String restrictedToken = reveal(page, "restricted");
            McpHttpTestClient restricted = new McpHttpTestClient(app.baseUrl(), restrictedToken);
            restricted.initialize("authorization-e2e");
            JsonNode listed = restricted.call("list_projects", Map.of());
            JsonNode visible = result(listed).path("result");
            if (!visible.isArray() || visible.size() != 1
                    || !visible.get(0).path("name").asText().equals("Authorization A")) {
                throw new AssertionError("Project-scoped tools/list/call exposed unexpected projects: " + listed);
            }
            long projectAId = projectIdFromForm(page, "Authorization A");
            JsonNode allowed = restricted.call("list_agents", Map.of("projectId", projectAId));
            if (result(allowed).path("result").isEmpty()) {
                throw new AssertionError("Permitted project MCP call returned no agents: " + allowed);
            }
            long projectBId = projectIdFromForm(page, "Authorization B");
            JsonNode denied = restricted.call("list_agents", Map.of("projectId", projectBId));
            if (!denied.path("result").path("isError").asBoolean()) {
                throw new AssertionError("Cross-project MCP call was not denied: " + denied);
            }

            Locator card = card(page, "restricted");
            Locator editForm = card.locator("form").filter(new Locator.FilterOptions()
                    .setHas(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Save changes"))));
            JsonNode editedAllowed = restricted.call("list_agents", Map.of("projectId", projectAId));
            if (result(editedAllowed).path("result").isEmpty()) {
                throw new AssertionError("list_agents was not permitted before editing: " + editedAllowed);
            }
            editForm.locator("input[name='tools'][value='list_agents']").uncheck();
            editForm.locator("input[name='tools'][value='list_projects']").check();
            settingsAction(page, "/edit", () -> editForm
                    .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Save changes")).click());
            card = card(page, "restricted");
            JsonNode changed = restricted.call("list_agents", Map.of("projectId", projectAId));
            JsonNode changedError = changed.path("result").path("structuredContent").path("error");
            if (!changed.path("result").path("isError").asBoolean()
                    || !"RESOURCE_UNAVAILABLE".equals(changedError.path("code").asText())
                    || !"Resource unavailable".equals(changedError.path("message").asText())) {
                throw new AssertionError("Edited tool permission did not deny with RESOURCE_UNAVAILABLE: " + changed);
            }
            Locator rotate = card(page, "restricted").getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Rotate"));
            page.onceDialog(dialog -> dialog.accept());
            settingsAction(page, "/rotate", rotate::click);
            Locator issuedToken = page.locator("#inbound-mcp-settings > p.alert.alert-warning code");
            issuedToken.waitFor();
            String rotatedToken = issuedToken.textContent().trim();
            if (restricted.httpStatus("tools/list") != 401) {
                throw new AssertionError("Rotated token remained valid");
            }
            McpHttpTestClient rotated = new McpHttpTestClient(app.baseUrl(), rotatedToken);
            rotated.initialize("authorization-e2e");
            JsonNode rotatedTools = rotated.tools();
            if (rotatedTools.path("result").path("tools").isEmpty()) {
                throw new AssertionError("Rotated token did not work: " + rotatedTools);
            }
            Locator independent = page.locator("#inbound-mcp-settings > form");
            independent.locator("input[name='name']").fill("independent");
            independent.locator("input[name='allProjects']").check();
            settingsAction(page, "/create", independent.locator("button[type='submit']")::click);
            String independentToken = reveal(page, "independent");
            Locator revoke = card(page, "restricted").getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Revoke"));
            page.onceDialog(dialog -> dialog.accept());
            armSettled(page, "/revoke");
            page.waitForResponse(response -> response.url().endsWith("/revoke")
                    && response.request().method().equals("POST") && response.status() == 200, revoke::click);
            waitForSettled(page, "/revoke");
            Locator revokedCard = page.locator("#inbound-mcp-settings article")
                    .filter(new Locator.FilterOptions().setHasText("restricted"));
            revokedCard.getByText("Revoked", new Locator.GetByTextOptions().setExact(true)).waitFor();
            PlaywrightAssertions
                    .assertThat(revokedCard.getByText("Revoked", new Locator.GetByTextOptions().setExact(true)))
                    .isVisible();
            if (rotated.httpStatus("tools/list") != 401) {
                throw new AssertionError("Revoked credential remained valid");
            }
            McpHttpTestClient allProjects = new McpHttpTestClient(app.baseUrl(), independentToken);
            allProjects.initialize("authorization-e2e");
            JsonNode futureProjectList = allProjects.call("list_projects", Map.of());
            if (result(futureProjectList).path("result").size() < 2) {
                throw new AssertionError("All-project token did not see both projects: " + futureProjectList);
            }
        }
    }

    private static void settingsAction(Page page, String path, Runnable action) {
        armSettled(page, path);
        page.waitForResponse(response -> response.url().endsWith(path) && response.request().method().equals("POST")
                && response.status() == 200, action);
        waitForSettled(page, path);
    }

    private static void armSettled(Page page, String path) {
        page.evaluate("""
                path => {
                    window.__settingsSettled = null;
                    document.addEventListener('htmx:afterSettle', event => {
                        const requestPath = event.detail.requestConfig?.path;
                        const responseUrl = event.detail.xhr?.responseURL;
                        if (requestPath?.endsWith(path) || responseUrl?.endsWith(path)) {
                            window.__settingsSettled = path;
                        }
                    });
                }
                """, path);
    }

    private static void waitForSettled(Page page, String path) {
        page.waitForFunction("path => window.__settingsSettled === path", path);
    }

    private static void settings(Page page) {
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Settings")).click();
        page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("Inbound MCP")).click();
        page.locator("#inbound-mcp-settings").waitFor();
    }

    private static Locator card(Page page, String name) {
        return page.locator("#inbound-mcp-settings article").filter(new Locator.FilterOptions()
                .setHas(page.locator("strong").getByText(name, new Locator.GetByTextOptions().setExact(true))));
    }

    private static String reveal(Page page, String name) {
        settingsAction(page, "/reveal", () -> card(page, name)
                .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Reveal")).click());
        return tokenText(page);
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
        Locator checkbox = page.locator("#inbound-mcp-settings > form input[name='projectIds']")
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

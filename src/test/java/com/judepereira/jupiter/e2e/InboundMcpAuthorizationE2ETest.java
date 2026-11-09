package com.judepereira.jupiter.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
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
            create.locator("button[type='submit']").click();
            String restrictedToken = reveal(page, "restricted");
            Mcp restricted = new Mcp(app.baseUrl(), restrictedToken);
            restricted.initialize();
            JsonNode listed = restricted.call("list_projects", "{}");
            JsonNode visible = result(listed).path("result");
            if (!visible.isArray() || visible.size() != 1
                    || !visible.get(0).path("name").asText().equals("Authorization A")) {
                throw new AssertionError("Project-scoped tools/list/call exposed unexpected projects: " + listed);
            }
            long projectBId = projectIdFromForm(page, "Authorization B");
            JsonNode denied = restricted.call("list_agents", "{\"projectId\":" + projectBId + "}");
            if (!denied.path("result").path("isError").asBoolean()) {
                throw new AssertionError("Cross-project MCP call was not denied: " + denied);
            }

            Locator card = card(page, "restricted");
            Locator editForm = card.locator("form").filter(new Locator.FilterOptions()
                    .setHas(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Save changes"))));
            editForm.locator("input[name='tools'][value='list_agents']").uncheck();
            editForm.locator("input[name='tools'][value='list_projects']").check();
            page.waitForResponse(
                    response -> response.url().endsWith("/edit") && response.request().method().equals("POST")
                            && response.status() == 200,
                    () -> editForm.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Save changes"))
                            .click());
            card = card(page, "restricted");
            JsonNode changed = restricted.call("list_agents", "{}");
            if (!changed.path("result").path("isError").asBoolean()) {
                throw new AssertionError("Edited tool permission did not apply to the existing MCP client: " + changed);
            }
            Locator rotate = card(page, "restricted").getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Rotate"));
            page.onceDialog(dialog -> dialog.accept());
            page.waitForResponse(response -> response.url().contains("/ui/settings/inbound-mcp/")
                    && response.url().endsWith("/rotate") && response.request().method().equals("POST")
                    && response.status() == 200, rotate::click);
            Locator issuedToken = page.locator("#inbound-mcp-settings > p.alert.alert-warning code");
            issuedToken.waitFor();
            String rotatedToken = issuedToken.textContent().trim();
            if (restricted.httpStatus("tools/list") != 401) {
                throw new AssertionError("Rotated token remained valid");
            }
            Mcp rotated = new Mcp(app.baseUrl(), rotatedToken);
            rotated.initialize();
            JsonNode rotatedTools = rotated
                    .send("{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"tools/list\",\"params\":{}}");
            if (rotatedTools.path("result").path("tools").isEmpty()) {
                throw new AssertionError("Rotated token did not work: " + rotatedTools);
            }
            Locator independent = page.locator("#inbound-mcp-settings > form");
            independent.locator("input[name='name']").fill("independent");
            independent.locator("input[name='allProjects']").check();
            independent.locator("button[type='submit']").click();
            String independentToken = reveal(page, "independent");
            Locator revoke = card(page, "restricted").getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Revoke"));
            page.onceDialog(dialog -> dialog.accept());
            page.waitForResponse(response -> response.url().endsWith("/revoke")
                    && response.request().method().equals("POST") && response.status() == 200, revoke::click);
            Locator revokedCard = page.locator("#inbound-mcp-settings article")
                    .filter(new Locator.FilterOptions().setHasText("restricted"));
            revokedCard.getByText("Revoked", new Locator.GetByTextOptions().setExact(true)).waitFor();
            PlaywrightAssertions
                    .assertThat(revokedCard.getByText("Revoked", new Locator.GetByTextOptions().setExact(true)))
                    .isVisible();
            if (rotated.httpStatus("tools/list") != 401) {
                throw new AssertionError("Revoked credential remained valid");
            }
            Mcp allProjects = new Mcp(app.baseUrl(), independentToken);
            allProjects.initialize();
            JsonNode futureProjectList = allProjects.call("list_projects", "{}");
            if (result(futureProjectList).path("result").size() < 2) {
                throw new AssertionError("All-project token did not see both projects: " + futureProjectList);
            }
        }
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
        card(page, name).getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Reveal")).click();
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

    private static final class Mcp {
        private final String url;
        private final String token;
        private final HttpClient http = HttpClient.newHttpClient();
        private String session;
        private int id;

        private Mcp(String baseUrl, String token) {
            url = baseUrl + "/mcp";
            this.token = token;
        }

        private void initialize() throws Exception {
            send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"authorization-e2e\",\"version\":\"1\"}}}");
            send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        }

        private JsonNode call(String name, String args) throws Exception {
            return send("{\"jsonrpc\":\"2.0\",\"id\":" + (++id + 1)
                    + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + name + "\",\"arguments\":" + args + "}}");
        }

        private int httpStatus(String method) throws Exception {
            HttpResponse<String> response = post(
                    "{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"" + method + "\",\"params\":{}}");
            return response.statusCode();
        }

        private JsonNode send(String body) throws Exception {
            HttpResponse<String> response = post(body);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AssertionError("MCP HTTP " + response.statusCode() + ": " + response.body());
            }
            session = response.headers().firstValue("Mcp-Session-Id").orElse(session);
            String data = response.body().lines().filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring(5).trim()).findFirst().orElse(response.body());
            return data.isBlank() ? JSON.createObjectNode() : JSON.readTree(data);
        }

        private HttpResponse<String> post(String body) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .header("MCP-Protocol-Version", "2025-03-26");
            if (session != null) {
                request.header("Mcp-Session-Id", session);
            }
            return http.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }
}

package com.judepereira.jupiter.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.judepereira.jupiter.command.CommandCatalogService;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.Persistence.AppStateView;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator.FilterOptions;
import com.microsoft.playwright.Locator.GetByRoleOptions;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WatchesE2ETest extends E2ETestSupport {

    @Test
    void createEditToggleDeleteWatchKeepsSettingsAndSelection(@TempDir Path tempDir) throws Exception {
        Path fakeHome = Files.createDirectories(tempDir.resolve("fake-home"));
        Path projectDir = Files.createDirectories(fakeHome.resolve("child-project"));
        Path database = tempDir.resolve("db/jupiter.db");
        Files.createDirectories(database.getParent());

        try (RunningApp app = startApp(fakeHome, database); BrowserContext context = newBrowserContext()) {
            Page page = context.newPage();
            page.navigate(app.baseUrl());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).waitFor();
            openProject(page, "Alpha", projectDir);

            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Settings")).click();
            assertThat(page.locator("#settings-modal")).isVisible();
            page.locator("#settings-watches-tab").click();
            assertThat(page.locator("#settings-watches")).isVisible();

            page.locator("#settings-watches input[name=name]").fill("Browser regression watch");
            page.locator("#settings-watches textarea[name=prompt]").fill("Check the project");
            page.locator("#settings-watches input[name=intervalSeconds]").fill("60");
            page.locator("#settings-watches button[type=submit]").click();
            assertThat(page.locator("#settings-watches")).containsText("Browser regression watch");
            assertThat(page.locator("#settings-modal")).isVisible();

            var watch = page.locator(".settings-command-card")
                    .filter(new FilterOptions().setHasText("Browser regression watch"));
            watch.locator("summary").click();
            watch.locator("input[name=name]").fill("Edited browser watch");
            var saveResponse = page.waitForResponse(
                    r -> r.url().contains("/ui/settings/watches") && r.request().method().equals("POST"),
                    () -> watch.getByRole(AriaRole.BUTTON, new GetByRoleOptions().setName("Save")).click());
            if (!saveResponse.ok())
                throw new AssertionError("Watch save failed: " + saveResponse.status());
            assertThat(page.locator("#settings-watches")).containsText("Edited browser watch");
            assertThat(page.locator("#settings-watches")).not().containsText("Browser regression watch");
            assertThat(page.locator("#settings-modal")).isVisible();

            var toggle = page.locator("#settings-watches input[type=checkbox]").first();
            var enableResponse = page.waitForResponse(
                    r -> r.url().contains("/toggle") && r.request().method().equals("POST"), toggle::check);
            if (!enableResponse.ok())
                throw new AssertionError("Watch toggle failed: " + enableResponse.status());
            assertThat(page.locator("#settings-watches input[type=checkbox]").first()).isChecked();
            assertThat(page.locator("#settings-modal")).isVisible();
            var disableResponse = page.waitForResponse(
                    r -> r.url().contains("/toggle") && r.request().method().equals("POST"),
                    () -> page.locator("#settings-watches input[type=checkbox]").first().uncheck());
            if (!disableResponse.ok())
                throw new AssertionError("Watch toggle failed: " + disableResponse.status());
            assertThat(page.locator("#settings-watches input[type=checkbox]").first()).not().isChecked();
            assertThat(page.locator("#settings-modal")).isVisible();

            page.locator("#settings-watches .settings-command-card")
                    .filter(new FilterOptions().setHasText("Edited browser watch"))
                    .getByRole(AriaRole.BUTTON, new GetByRoleOptions().setName("Delete")).click();
            assertThat(page.locator("#settings-watches")).not().containsText("Edited browser watch");
            assertThat(page.locator("#settings-modal")).isVisible();
        }
    }

    @Test
    void watchContextStaysOnSecondWorkspaceAndUsesItsLocalCommand(@TempDir Path tempDir) throws Exception {
        Path fakeHome = Files.createDirectories(tempDir.resolve("fake-home"));
        Path projectDir = Files.createDirectories(fakeHome.resolve("child-project"));
        Path database = tempDir.resolve("db/jupiter.db");
        Files.createDirectories(database.getParent());
        initGitRepoWithInitialCommit(projectDir);

        try (RunningApp app = startApp(fakeHome, database); BrowserContext context = newBrowserContext()) {
            Page page = context.newPage();
            page.navigate(app.baseUrl());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).waitFor();
            openProject(page, "Alpha", projectDir);
            AppStateService state = app.context().getBean(AppStateService.class);
            AppStateView before = state.loadViewData();
            long originalSession = before.activeSession().id();
            var secondWorkspace = state.createWorkspace(before.activeProject().id(), "watch-context", true);
            Path localCommand = Files.createDirectories(Path.of(secondWorkspace.path()).resolve(".claude/commands"))
                    .resolve("local-prompt.md");
            Files.writeString(localCommand, """
                    ---
                    name: Local Prompt
                    description: Workspace-local command
                    ---
                    Do the local check.
                    """);
            var commandCatalog = app.context().getBean(CommandCatalogService.class);
            var localPrompt = commandCatalog.list(Path.of(secondWorkspace.path())).stream()
                    .filter(c -> c.body().contains("Do the local check.")).findFirst()
                    .orElseThrow(() -> new AssertionError("Workspace-local command was not cataloged"));
            if (commandCatalog.list(null).stream().anyMatch(c -> c.id().equals(localPrompt.id())))
                throw new AssertionError("Workspace-local command leaked into the global catalog");
            long secondSession = state.listVisiblePrimarySessions(secondWorkspace.id()).getFirst().id();
            state.activateWorkspace(before.activeWorkspace().id());

            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Settings")).click();
            page.locator("#settings-watches-tab").click();
            assertThat(page.locator("#settings-watches")).isVisible();
            var workspaceSelect = page.locator("#settings-watches select[name=workspaceId]");
            var workspaceResponse = page.waitForResponse(
                    r -> r.url().contains("/ui/settings/watches/select") && r.request().method().equals("GET"),
                    () -> workspaceSelect.selectOption(Long.toString(secondWorkspace.id())));
            if (!workspaceResponse.ok())
                throw new AssertionError("Workspace watch refresh failed: " + workspaceResponse.status());
            assertThat(page.locator("#settings-watches select[name=workspaceId]"))
                    .hasValue(Long.toString(secondWorkspace.id()));
            assertThat(page.locator("#settings-watches select[name=sessionId]")).hasValue(Long.toString(secondSession));
            assertThat(page.locator("#settings-watches select[name=sessionId] option")).containsText("Session #1");
            var createForm = page.locator("#settings-watches form").first();
            assertThat(createForm.locator("input[name=watchSessionId]")).hasValue(Long.toString(secondSession));
            assertThat(createForm.locator("select[name=actionCommandId] option[value='" + localPrompt.id() + "']"))
                    .hasCount(1);
            assertThat(createForm.locator("button[type=submit]")).isEnabled();
            page.locator("#settings-watches input[name=name]").fill("Second workspace watch");
            page.locator("#settings-watches textarea[name=prompt]").fill("Check this workspace");
            page.locator("#settings-watches input[name=intervalSeconds]").fill("60");
            var create = page.locator("#settings-watches form").first();
            create.locator("select[name=actionCommandId]").selectOption(localPrompt.id());
            var response = page.waitForResponse(r -> r.url().contains("/ui/settings/watches/create"),
                    create.locator("button[type=submit]")::click);
            if (!response.ok())
                throw new AssertionError("Watch create failed: " + response.status());
            assertThat(page.locator("#settings-watches select[name=workspaceId]"))
                    .hasValue(Long.toString(secondWorkspace.id()));
            assertThat(page.locator("#settings-watches select[name=sessionId]")).hasValue(Long.toString(secondSession));
            assertThat(page.locator("#settings-watches")).containsText("Second workspace watch");

            var watch = page.locator(".settings-command-card")
                    .filter(new FilterOptions().setHasText("Second workspace watch"));
            watch.locator("summary").click();
            watch.locator("input[name=name]").fill("Edited second workspace watch");
            var save = page.waitForResponse(
                    r -> r.url().matches(".*/ui/settings/watches/\\d+") && r.request().method().equals("POST"),
                    () -> watch.getByRole(AriaRole.BUTTON, new GetByRoleOptions().setName("Save")).click());
            if (!save.ok())
                throw new AssertionError("Watch save failed: " + save.status());
            assertThat(page.locator("#settings-watches")).containsText("Edited second workspace watch");
            assertThat(page.locator("#settings-watches")).not().containsText("Second workspace watch");
            var toggle = page.locator("#settings-watches input[type=checkbox]").first();
            page.waitForResponse(r -> r.url().contains("/toggle") && r.status() == 200, toggle::check);
            assertThat(page.locator("#settings-watches select[name=workspaceId]"))
                    .hasValue(Long.toString(secondWorkspace.id()));
            assertThat(page.locator("#settings-watches select[name=sessionId]")).hasValue(Long.toString(secondSession));
            assertThat(page.locator("#settings-watches input[type=checkbox]").first()).isChecked();
            if (state.loadViewData().activeSession().id() != originalSession)
                throw new AssertionError("Watch context changed the main active session");
            page.waitForResponse(r -> r.url().contains("/delete") && r.request().method().equals("POST"),
                    () -> watch.getByRole(AriaRole.BUTTON, new GetByRoleOptions().setName("Delete")).click());
            assertThat(page.locator("#settings-watches select[name=workspaceId]"))
                    .hasValue(Long.toString(secondWorkspace.id()));
            assertThat(page.locator("#settings-watches select[name=sessionId]")).hasValue(Long.toString(secondSession));
            assertThat(page.locator("#settings-watches")).not().containsText("Edited second workspace watch");
        }
    }

    @Test
    void closingWatchesPanelRemovesPollingAndReopens(@TempDir Path tempDir) throws Exception {
        Path fakeHome = Files.createDirectories(tempDir.resolve("fake-home"));
        Path projectDir = Files.createDirectories(fakeHome.resolve("child-project"));
        Path database = tempDir.resolve("db/jupiter.db");
        Files.createDirectories(database.getParent());
        try (RunningApp app = startApp(fakeHome, database); BrowserContext context = newBrowserContext()) {
            Page page = context.newPage();
            page.navigate(app.baseUrl());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).waitFor();
            openProject(page, "Alpha", projectDir);
            page.locator("#toggle-watches-rail-btn").click();
            assertThat(page.locator("#bottom-panel")).isVisible();
            var watchPanel = page.locator("#bottom-panel");
            assertThat(watchPanel).containsText("WATCHES");
            var state = app.context().getBean(AppStateService.class);
            long originalSessionId = state.loadViewData().activeSession().id();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New session")).click();
            page.locator("#session-name-input").fill("Second watch session");
            page.waitForResponse(response -> response.url().contains("/ui/sessions/add") && response.status() == 200,
                    () -> page.locator("[data-session-create-form]").evaluate("form => form.requestSubmit()"));
            long secondSessionId = state.loadViewData().activeSession().id();
            page.waitForResponse(
                    response -> response.url().contains("/ui/sessions/" + secondSessionId + "/activate")
                            && response.status() == 200,
                    () -> page.locator(".session-row").filter(new FilterOptions().setHasText("Second watch session"))
                            .locator(".session-item").click());
            assertThat(page.locator("#bottom-panel")).hasCount(1);
            assertThat(page.locator("#bottom-panel")).hasClass(Pattern.compile(".*closed.*"));
            assertEquals(secondSessionId, state.loadViewData().activeSession().id());
            assertThat(page.locator("#bottom-panel")).hasClass(Pattern.compile(".*closed.*"));
            assertThat(page.locator("#bottom-panel")).not().isVisible();
            page.waitForResponse(response -> response.url().contains("/ui/panel/watches") && response.status() == 200,
                    () -> page.locator("#toggle-watches-rail-btn").click());
            assertThat(page.locator("#bottom-panel")).isVisible();
            assertThat(page.locator("#bottom-panel")).containsText("WATCHES");
            page.waitForResponse(
                    response -> response.url().contains("/ui/sessions/" + originalSessionId + "/activate")
                            && response.status() == 200,
                    () -> page.locator(".session-item[hx-post='/ui/sessions/" + originalSessionId + "/activate']")
                            .click());
            assertThat(page.locator("#bottom-panel")).isVisible();
            assertThat(page.locator("#bottom-panel")).containsText("WATCHES");
            page.waitForResponse(
                    response -> response.url().contains("/ui/sessions/" + secondSessionId + "/activate")
                            && response.status() == 200,
                    () -> page.locator(".session-item[hx-post='/ui/sessions/" + secondSessionId + "/activate']")
                            .click());
            assertThat(page.locator("#bottom-panel")).isVisible();
            assertThat(page.locator("#bottom-panel")).containsText("WATCHES");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Close watches panel")).click();
            assertThat(page.locator("#bottom-panel")).hasClass(Pattern.compile(".*closed.*"));
            assertThat(page.locator("#bottom-panel")).not().isVisible();
            assertThat(page.locator("#bottom-panel")).not().hasAttribute("hx-trigger", Pattern.compile(".+"));
            page.reload();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).waitFor();
            assertThat(page.locator("#bottom-panel")).hasClass(Pattern.compile(".*closed.*"));
            page.waitForResponse(response -> response.url().contains("/ui/panel/watches") && response.status() == 200,
                    () -> page.locator("#toggle-watches-rail-btn").click());
            assertThat(page.locator("#bottom-panel")).isVisible();
            assertThat(page.locator("#bottom-panel")).hasAttribute("hx-trigger", Pattern.compile("every 20s"));
            app.context().getBean(AppStateService.class);
        }
    }
}

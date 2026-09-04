package com.judepereira.jupiter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.ui.UiController.Project;
import com.judepereira.jupiter.ui.UiController.Session;
import com.judepereira.jupiter.ui.UiController.UsagePoint;
import com.judepereira.jupiter.ui.UiController.Workspace;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.Persistence.LifecycleHookSettings;
import com.judepereira.jupiter.persistence.Persistence.ProjectEnvironmentVariable;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.TemplateSpec;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

public class ProjectsTemplateRenderTest {

    @Test
    public void projectsFragmentRendersWhenNoProjectIsActive() {
        SpringTemplateEngine engine = new SpringTemplateEngine();
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setCacheable(false);
        engine.setTemplateResolver(resolver);

        WebContext context = webContext();
        context.setVariable("shellRefresh", false);
        context.setVariable("projects", List.of());
        context.setVariable("activeProject", null);
        context.setVariable("workspaces", List.of());
        context.setVariable("activeWorkspace", null);
        context.setVariable("sessions", List.of());
        context.setVariable("activeSession", null);
        context.setVariable("selectedName", "");
        context.setVariable("selectedPath", "");
        context.setVariable("currentPath", "");
        context.setVariable("directoryEntries", List.of());
        context.setVariable("includeChatContainer", false);
        context.setVariable("reviewPanelOpen", false);
        context.setVariable("reviewOob", false);
        context.setVariable("changedFiles", List.of());
        context.setVariable("selectedFile", null);
        context.setVariable("workspaceCloseStatus", new AppStateService.WorkspaceCloseInspection(0L, "", "", "", false, false, List.of()));

        String html = engine.process("fragments/projects", context);

        assertThat(html).contains("No projects", "No project selected", "New tab");
    }

    @Test
    public void gitPullControlRendersIdleButton() {
        SpringTemplateEngine engine = engine();
        WebContext context = webContext();
        context.setVariable("workspaceId", 7L);
        context.setVariable("busy", false);
        context.setVariable("hasWorkspace", true);

        String html = engine.process(new TemplateSpec("fragments/projects", Set.of("gitPullControl"), TemplateMode.HTML, null), context);

        assertThat(html).contains("bi-cloud-arrow-down", "hx-post=\"/ui/workspaces/active/git/pull\"",
                "hx-target=\"#git-pull-control\"", "hx-swap=\"outerHTML\"");
        assertThat(html).doesNotContain("spinner-border", "hx-get=");
    }

    @Test
    public void gitPullControlRendersBusyPollingButton() {
        SpringTemplateEngine engine = engine();
        WebContext context = webContext();
        context.setVariable("workspaceId", 7L);
        context.setVariable("busy", true);
        context.setVariable("hasWorkspace", true);

        String html = engine.process(new TemplateSpec("fragments/projects", Set.of("gitPullControl"), TemplateMode.HTML, null), context);

        assertThat(html).contains("disabled", "spinner-border spinner-border-sm", "Git pull in progress",
                "aria-busy=\"true\"", "hx-get=\"/ui/workspaces/7/git/pull/status\"", "hx-trigger=\"every 1s\"");
        assertThat(html).doesNotContain("hx-post=");
    }

    @Test
    public void indexPageIncludesPersistentSystemBalloonRootContainer() {
        SpringTemplateEngine engine = engine();

        WebContext context = webContext();
        context.setVariable("shellRefresh", false);
        context.setVariable("projects", List.of());
        context.setVariable("activeProject", null);
        context.setVariable("workspaces", List.of());
        context.setVariable("activeWorkspace", null);
        context.setVariable("sessions", List.of());
        context.setVariable("activeSession", null);
        context.setVariable("selectedName", "");
        context.setVariable("selectedPath", "");
        context.setVariable("currentPath", "");
        context.setVariable("directoryEntries", List.of());
        context.setVariable("includeChatContainer", false);
        context.setVariable("reviewPanelOpen", false);
        context.setVariable("reviewOob", false);
        context.setVariable("changedFiles", List.of());
        context.setVariable("selectedFile", null);
        context.setVariable("branchName", "");
        context.setVariable("branchMode", "create");
        context.setVariable("createBranch", true);
        context.setVariable("modalOob", false);
        context.setVariable("bottomPanelMode", "none");
        context.setVariable("bottomPanelOpen", false);
        context.setVariable("terminalTabs", List.of());
        context.setVariable("activeTerminal", null);
        context.setVariable("terminalOob", false);

        String html = engine.process("index", context);

        assertThat(html).contains("id=\"system-balloon-root\"");
    }

    @Test
    public void settingsModalRendersExistingProjectEnvironmentVariablesAndMcpCatalog() {
        SpringTemplateEngine engine = engine();

        WebContext context = webContext();
        context.setVariable("shellRefresh", false);
        context.setVariable("projects", List.of(new Project(1L, "Alpha", "/repo", "", List.of(
                new ProjectEnvironmentVariable("API_URL", "https://example.test"),
                new ProjectEnvironmentVariable("FEATURE_FLAG", "true")
        ))));
        context.setVariable("visibleProjects", List.of(new Project(1L, "Alpha", "/repo", "", List.of()), new Project(2L, "Beta", "/repo-b", "", List.of())));
        context.setVariable("lifecycleHookSettings", new LifecycleHookSettings("echo <done>\nline 2", "echo error", "echo subagent", 45));
        context.setVariable("autoGitUpdateEnabled", true);
        context.setVariable("reviewPanelOpen", false);
        context.setVariable("mcpServers", List.of(new com.judepereira.jupiter.persistence.Persistence.McpServerView(9L, "Local MCP", "http://localhost:3000/mcp", true,
                List.of(new com.judepereira.jupiter.persistence.Persistence.McpServerHeader("Authorization", "Bearer token")), List.of(1L))));
        context.setVariable("activeProject", new Project(1L, "Alpha", "/repo", "", List.of(
                new ProjectEnvironmentVariable("API_URL", "https://example.test"),
                new ProjectEnvironmentVariable("FEATURE_FLAG", "true")
        )));
        context.setVariable("workspaces", List.of());
        context.setVariable("activeWorkspace", null);
        context.setVariable("sessions", List.of());
        context.setVariable("activeSession", null);
        context.setVariable("selectedName", "");
        context.setVariable("selectedPath", "");
        context.setVariable("currentPath", "");
        context.setVariable("directoryEntries", List.of());
        context.setVariable("includeChatContainer", false);
        context.setVariable("reviewPanelOpen", false);
        context.setVariable("reviewOob", false);
        context.setVariable("changedFiles", List.of());
        context.setVariable("selectedFile", null);
        context.setVariable("workspaceCloseStatus", new AppStateService.WorkspaceCloseInspection(0L, "", "", "", false, false, List.of()));

        String html = engine.process("fragments/projects", context);

        assertThat(html).contains("id=\"settings-modal\"", "Environment variables", "API_URL", "https://example.test", "FEATURE_FLAG", "true", "Add Variable");
        assertThat(html).contains("MCP servers", "Local MCP", "http://localhost:3000/mcp", "Header name", "Authorization", "Bearer token", "Exposed projects");
        assertThat(html).contains("Hooks", "Assistant completion script", "Assistant error script", "Subagent completion script", "name=\"timeoutSeconds\"", "min=\"1\"", "max=\"3600\"", "Scripts execute with Bash");
        assertThat(html).contains("echo &lt;done&gt;\nline 2").doesNotContain("echo <done>");
        assertThat(html).contains(
                "class=\"nav nav-pills flex-md-column settings-nav\"",
                "id=\"settings-current-project\"",
                "id=\"settings-application\"",
                "id=\"settings-mcp-servers\"",
                "id=\"settings-model-providers\"",
                "id=\"settings-usage\"",
                "id=\"settings-help\"",
                "<h5>Help</h5>");
        assertThat(html.indexOf("id=\"settings-current-project-tab\""))
                .isLessThan(html.indexOf("id=\"settings-mcp-servers-tab\""));
        assertThat(html.indexOf("id=\"settings-mcp-servers-tab\""))
                .isLessThan(html.indexOf("id=\"settings-model-providers-tab\""));
        assertThat(html.indexOf("id=\"settings-model-providers-tab\""))
                .isLessThan(html.indexOf("id=\"settings-usage-tab\""));
        assertThat(html.indexOf("id=\"settings-usage-tab\""))
                .isLessThan(html.indexOf("id=\"settings-help-tab\""));
        assertThat(html).contains("data-bs-toggle=\"pill\"", "aria-selected=\"true\"");
        assertThat(html.split("data-settings-env-row", -1)).hasSize(4);
    }

    @Test
    public void settingsUsageDataUsesEscapedAttributeTransport() throws Exception {
        SpringTemplateEngine engine = engine();
        String usageJson = new ObjectMapper().writeValueAsString(List.of(
                new UsagePoint("2026-08-31T12:00:00Z", "<historical model>", "model\"key", 1, 2L, null, 2L)));

        WebContext context = webContext();
        context.setVariable("usageRange", "24h");
        context.setVariable("usageJson", usageJson);

        String html = engine.process(new TemplateSpec("fragments/projects", Set.of("settingsUsage"), TemplateMode.HTML, null), context);

        String attribute = html.substring(html.indexOf("data-usage-data=\"") + "data-usage-data=\"".length());
        attribute = attribute.substring(0, attribute.indexOf('"'));
        assertThat(html).doesNotContain("<script", "<historical model>");
        assertThat(attribute).contains("&quot;", "&lt;");
        assertThat(new ObjectMapper().readTree(HtmlUtils.htmlUnescape(attribute)).get(0).get("modelKey").asText())
                .isEqualTo("model\"key");
    }

    @Test
    public void settingsModalRendersHooksWithoutAnActiveProject() {
        SpringTemplateEngine engine = engine();
        WebContext context = webContext();
        context.setVariable("activeProject", null);
        context.setVariable("lifecycleHookSettings", new LifecycleHookSettings(null, "echo error", null, 30));
        context.setVariable("projects", List.of());
        context.setVariable("mcpServers", List.of());

        String html = engine.process(new TemplateSpec("fragments/projects", Set.of("settingsModal"), TemplateMode.HTML, null), context);

        assertThat(html).contains("id=\"settings-modal\"", "id=\"settings-hooks\"", "Hooks", "echo error", "value=\"30\"");
        assertThat(html).doesNotContain("id=\"settings-current-project-tab\"").contains("aria-selected=\"true\"");
    }

    @Test
    public void projectModalRendersNormalInputsWithoutOutOfBandSwaps() {
        SpringTemplateEngine engine = engine();

        WebContext context = webContext();
        context.setVariable("shellRefresh", false);
        context.setVariable("projects", List.of());
        context.setVariable("activeProject", null);
        context.setVariable("workspaces", List.of());
        context.setVariable("activeWorkspace", null);
        context.setVariable("sessions", List.of());
        context.setVariable("activeSession", null);
        context.setVariable("selectedName", "Home");
        context.setVariable("selectedPath", "/home/jude");
        context.setVariable("currentPath", "/home/jude");
        context.setVariable("directoryEntries", List.of());
        context.setVariable("includeChatContainer", false);
        context.setVariable("reviewPanelOpen", false);
        context.setVariable("reviewOob", false);
        context.setVariable("changedFiles", List.of());
        context.setVariable("selectedFile", null);
        context.setVariable("workspaceCloseStatus", new AppStateService.WorkspaceCloseInspection(0L, "", "", "", false, false, List.of()));

        String html = engine.process("fragments/projects", context);
        String nameInputHtml = inputTag(html, "project-name-input");
        String pathInputHtml = inputTag(html, "project-path-input");

        assertThat(nameInputHtml)
                .contains("id=\"project-name-input\"", "name=\"name\"", "value=\"Home\"")
                .doesNotContain("hx-swap-oob");
        assertThat(pathInputHtml)
                .contains("id=\"project-path-input\"", "name=\"path\"", "value=\"/home/jude\"")
                .doesNotContain("hx-swap-oob");
    }

    @Test
    public void workspaceRailRendersNewWorkspaceAndNewSessionControls() {
        SpringTemplateEngine engine = engine();

        WebContext context = webContext();
        context.setVariable("shellRefresh", false);
        context.setVariable("projects", List.of(new Project(1L, "Alpha", "/repo", null, List.of())));
        context.setVariable("activeProject", new Project(1L, "Alpha", "/repo", null, List.of()));
        context.setVariable("workspaces", List.of(
                new Workspace(1L, "Default Workspace", "/repo", true, com.judepereira.jupiter.persistence.Persistence.RailStatus.NONE),
                new Workspace(2L, "feature-workspace", "/repo/.trees/repo/feature-workspace", false, com.judepereira.jupiter.persistence.Persistence.RailStatus.NONE)));
        context.setVariable("activeWorkspace", new Workspace(1L, "Default Workspace", "/repo", true, com.judepereira.jupiter.persistence.Persistence.RailStatus.NONE));
        context.setVariable("sessions", List.of(new Session(1L, "Session #1", true, com.judepereira.jupiter.persistence.Persistence.RailStatus.NONE), new Session(2L, "Session #2", false, com.judepereira.jupiter.persistence.Persistence.RailStatus.NONE)));
        context.setVariable("activeSession", new Session(2L, "Session #2", false, com.judepereira.jupiter.persistence.Persistence.RailStatus.NONE));
        context.setVariable("selectedName", "");
        context.setVariable("selectedPath", "");
        context.setVariable("currentPath", "");
        context.setVariable("directoryEntries", List.of());
        context.setVariable("includeChatContainer", false);
        context.setVariable("reviewPanelOpen", false);
        context.setVariable("reviewOob", false);
        context.setVariable("changedFiles", List.of());
        context.setVariable("selectedFile", null);
        context.setVariable("workspaceCloseStatus", new AppStateService.WorkspaceCloseInspection(0L, "", "", "", false, false, List.of()));

        String html = engine.process("fragments/projects", context);

        assertThat(html).contains("New Workspace", "New session");
        assertThat(html).contains("hx-get=\"/ui/workspaces/new\"", "hx-get=\"/ui/sessions/new\"");
        assertThat(html).contains("hx-target=\"this\"", "hx-swap=\"outerHTML\"");
        assertThat(html).contains("hx-post=\"/ui/workspaces/1/collapse\"", "hx-post=\"/ui/workspaces/2/activate\"");
        assertThat(html).contains("hx-post=\"/ui/workspaces/2/close\"", "hx-post=\"/ui/sessions/1/close\"", "hx-post=\"/ui/sessions/2/close\"");
        assertThat(html).doesNotContain("hx-post=\"/ui/workspaces/1/close\"");
        assertThat(html).contains("bi-chevron-down workspace-disclosure", "bi-chevron-right workspace-disclosure");
        assertThat(html).contains("Session #1", "Session #2");
        assertThat(html.split("class=\"unread-dot\" aria-label=\"Unread\"", -1)).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    public void workspaceRailRendersPendingDotsForInProgressItems() {
        SpringTemplateEngine engine = engine();

        WebContext context = webContext();
        context.setVariable("shellRefresh", false);
        context.setVariable("projects", List.of(new Project(1L, "Alpha", "/repo", null, List.of())));
        context.setVariable("activeProject", new Project(1L, "Alpha", "/repo", null, List.of()));
        context.setVariable("workspaces", List.of(
                new Workspace(1L, "Default Workspace", "/repo", false, com.judepereira.jupiter.persistence.Persistence.RailStatus.IN_PROGRESS),
                new Workspace(2L, "feature-workspace", "/repo/.trees/repo/feature-workspace", false, com.judepereira.jupiter.persistence.Persistence.RailStatus.NONE)));
        context.setVariable("activeWorkspace", new Workspace(1L, "Default Workspace", "/repo", false, com.judepereira.jupiter.persistence.Persistence.RailStatus.IN_PROGRESS));
        context.setVariable("sessions", List.of(
                new Session(1L, "Session #1", false, com.judepereira.jupiter.persistence.Persistence.RailStatus.IN_PROGRESS),
                new Session(2L, "Session #2", false, com.judepereira.jupiter.persistence.Persistence.RailStatus.NONE)));
        context.setVariable("activeSession", new Session(2L, "Session #2", false, com.judepereira.jupiter.persistence.Persistence.RailStatus.NONE));
        context.setVariable("selectedName", "");
        context.setVariable("selectedPath", "");
        context.setVariable("currentPath", "");
        context.setVariable("directoryEntries", List.of());
        context.setVariable("includeChatContainer", false);
        context.setVariable("reviewPanelOpen", false);
        context.setVariable("reviewOob", false);
        context.setVariable("changedFiles", List.of());
        context.setVariable("selectedFile", null);
        context.setVariable("workspaceCloseStatus", new AppStateService.WorkspaceCloseInspection(0L, "", "", "", false, false, List.of()));

        String html = engine.process(new TemplateSpec("fragments/projects", Set.of("workspaceRail"), TemplateMode.HTML, null), context);

        assertThat(html).contains("aria-label=\"In progress\"");
        assertThat(html.split("class=\"pending-dot\" aria-label=\"In progress\"", -1)).hasSize(3);
        assertThat(html).doesNotContain("class=\"unread-dot\" aria-label=\"Unread\"");
    }

    @Test
    public void newSessionFormFragmentRendersPostFormAndNameInput() {
        SpringTemplateEngine engine = engine();

        WebContext context = webContext();
        context.setVariable("shellRefresh", false);
        context.setVariable("projects", List.of());
        context.setVariable("activeProject", null);
        context.setVariable("workspaces", List.of());
        context.setVariable("activeWorkspace", null);
        context.setVariable("sessions", List.of());
        context.setVariable("activeSession", null);
        context.setVariable("selectedName", "");
        context.setVariable("selectedPath", "");
        context.setVariable("currentPath", "");
        context.setVariable("directoryEntries", List.of());
        context.setVariable("includeChatContainer", false);
        context.setVariable("reviewPanelOpen", false);
        context.setVariable("reviewOob", false);
        context.setVariable("changedFiles", List.of());
        context.setVariable("selectedFile", null);
        context.setVariable("workspaceCloseStatus", new AppStateService.WorkspaceCloseInspection(0L, "", "", "", false, false, List.of()));
        String html = engine.process("fragments/projects", context);

        assertThat(html).contains("<form", "class=\"session-create-form\"", "data-session-create-form", "hx-post=\"/ui/sessions/add\"");
        assertThat(html).contains("id=\"session-name-input\"", "name=\"name\"", "placeholder=\"Session name\"");
    }

    @Test
    public void workspaceModalRendersExpectedFormActionInputsAndRadioOptions() {
        SpringTemplateEngine engine = engine();

        WebContext context = webContext();
        context.setVariable("shellRefresh", false);
        context.setVariable("projects", List.of());
        context.setVariable("activeProject", null);
        context.setVariable("workspaces", List.of());
        context.setVariable("activeWorkspace", null);
        context.setVariable("sessions", List.of());
        context.setVariable("activeSession", null);
        context.setVariable("selectedName", "");
        context.setVariable("selectedPath", "");
        context.setVariable("currentPath", "");
        context.setVariable("directoryEntries", List.of());
        context.setVariable("includeChatContainer", false);
        context.setVariable("reviewPanelOpen", false);
        context.setVariable("reviewOob", false);
        context.setVariable("changedFiles", List.of());
        context.setVariable("selectedFile", null);
        context.setVariable("workspaceCloseStatus", new AppStateService.WorkspaceCloseInspection(0L, "", "", "", false, false, List.of()));
        context.setVariable("branchName", "feature-workspace");
        context.setVariable("branchMode", "create");
        context.setVariable("createBranch", true);

        String html = engine.process("fragments/projects", context);

        assertThat(html).contains("id=\"workspace-modal\"", "hx-post=\"/ui/workspaces/add\"", "name=\"branchName\"", "data-workspace-branch-name");
        assertThat(html).contains("name=\"branchMode\" value=\"create\"", "name=\"branchMode\" value=\"checkout\"", "data-workspace-branch-mode");
        assertThat(html).contains("Create a new branch", "Checkout an existing branch");
    }

    @Test
    public void terminalFragmentRendersDedicatedBottomPanelId() {
        SpringTemplateEngine engine = new SpringTemplateEngine();
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setCacheable(false);
        engine.setTemplateResolver(resolver);

        WebContext context = webContext();
        context.setVariable("bottomPanelMode", "terminal");
        context.setVariable("bottomPanelOpen", true);
        context.setVariable("terminalTabs", List.of());
        context.setVariable("activeTerminal", null);
        context.setVariable("terminalOob", false);

        String html = engine.process("fragments/terminal", context);

        assertThat(html).contains("<aside id=\"bottom-panel\"");
        assertThat(html).contains("id=\"terminal-panel-divider\"");
        assertThat(html).doesNotContain("<aside id=\"review\"");
    }

    @Test
    public void shellUpdatesRenderReviewAndBottomPanelsIndependently() {
        SpringTemplateEngine engine = engine();

        WebContext context = webContext();
        context.setVariable("shellRefresh", false);
        context.setVariable("projects", List.of());
        context.setVariable("activeProject", null);
        context.setVariable("workspaces", List.of());
        context.setVariable("activeWorkspace", null);
        context.setVariable("sessions", List.of());
        context.setVariable("activeSession", null);
        context.setVariable("includeChatContainer", false);
        context.setVariable("reviewPanelOpen", true);
        context.setVariable("reviewOob", false);
        context.setVariable("changedFiles", List.of());
        context.setVariable("selectedFile", null);
        context.setVariable("workspaceCloseStatus", new AppStateService.WorkspaceCloseInspection(0L, "", "", "", false, false, List.of()));
        context.setVariable("bottomPanelMode", "terminal");
        context.setVariable("bottomPanelOpen", true);
        context.setVariable("terminalTabs", List.of());
        context.setVariable("activeTerminal", null);
        context.setVariable("terminalOob", false);

        String html = engine.process("fragments/projects", context);

        assertThat(html).contains("<aside id=\"review\"");
        assertThat(html).contains("<aside id=\"bottom-panel\"");
        assertThat(html).contains("id=\"toggle-review-rail-btn\"", "hx-post=\"/ui/review/toggle\"", "hx-target=\"#review\"");
        assertThat(html).doesNotContain("/ui/panel/review");
    }

    private static String inputTag(String html, String inputId) {
        int idIndex = html.indexOf("id=\"" + inputId + "\"");
        int start = html.lastIndexOf("<input", idIndex);
        int end = html.indexOf('>', idIndex);

        return html.substring(start, end + 1);
    }

    private static SpringTemplateEngine engine() {
        SpringTemplateEngine engine = new SpringTemplateEngine();
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setCacheable(false);
        engine.setTemplateResolver(resolver);
        return engine;
    }

    private static WebContext webContext() {
        MockServletContext servletContext = new MockServletContext();
        JakartaServletWebApplication application = JakartaServletWebApplication.buildApplication(servletContext);
        MockHttpServletRequest request = new MockHttpServletRequest(servletContext);
        request.setContextPath("");
        request.setServletPath("");
        request.setRequestURI("/");
        WebContext context = new WebContext(application.buildExchange(request, new MockHttpServletResponse()), Locale.US);
        context.setVariable("workspaceCloseStatus", new AppStateService.WorkspaceCloseInspection(0L, "", "", "", false, false, List.of()));
        return context;
    }
}

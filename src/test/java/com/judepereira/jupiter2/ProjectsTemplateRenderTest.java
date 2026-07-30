package com.judepereira.jupiter2;

import com.judepereira.jupiter2.ui.UiController.Project;
import com.judepereira.jupiter2.ui.UiController.Session;
import com.judepereira.jupiter2.ui.UiController.Workspace;
import com.judepereira.jupiter2.persistence.AppStateService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.List;

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
        context.setVariable("projects", List.of(new Project(1L, "Alpha", "/repo", null)));
        context.setVariable("activeProject", new Project(1L, "Alpha", "/repo", null));
        context.setVariable("workspaces", List.of(
                new Workspace(1L, "Default Workspace", "/repo", true),
                new Workspace(2L, "feature-workspace", "/repo/.trees/repo/feature-workspace")));
        context.setVariable("activeWorkspace", new Workspace(1L, "Default Workspace", "/repo", true));
        context.setVariable("sessions", List.of(new Session(1L, "Session #1", true), new Session(2L, "Session #2")));
        context.setVariable("activeSession", new Session(2L, "Session #2"));
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

        assertThat(html).contains("id=\"workspace-modal\"", "hx-post=\"/ui/workspaces/add\"", "name=\"branchName\"");
        assertThat(html).contains("name=\"branchMode\" value=\"create\"", "name=\"branchMode\" value=\"checkout\"");
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

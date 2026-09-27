package com.judepereira.jupiter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.catalog.ModelDefinition;
import com.judepereira.jupiter.command.CommandCatalogService;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.Persistence;
import com.judepereira.jupiter.persistence.Persistence.LifecycleHookSettings;
import com.judepereira.jupiter.persistence.Persistence.McpServerHeader;
import com.judepereira.jupiter.persistence.Persistence.McpServerView;
import com.judepereira.jupiter.persistence.Persistence.ProjectEnvironmentVariable;
import com.judepereira.jupiter.ui.UiController.Project;
import com.judepereira.jupiter.ui.UiController.Session;
import com.judepereira.jupiter.ui.UiController.UsagePoint;
import com.judepereira.jupiter.ui.UiController.Workspace;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.util.HtmlUtils;
import org.thymeleaf.TemplateSpec;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

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
        context.setVariable("workspaceCloseStatus",
                new AppStateService.WorkspaceCloseInspection(0L, "", "", "", false, false, List.of()));

        String html = engine.process("fragments/projects", context);

        assertThat(html).contains("No projects", "No project selected", "New tab");
        assertThat(html).contains("id=\"topbar-logo\"", "src=\"/favicon-32x32.png\"");
    }

    @Test
    public void gitPullControlRendersIdleButton() {
        SpringTemplateEngine engine = engine();
        WebContext context = webContext();
        context.setVariable("workspaceId", 7L);
        context.setVariable("busy", false);
        context.setVariable("hasWorkspace", true);

        String html = engine.process(
                new TemplateSpec("fragments/projects", Set.of("gitPullControl"), TemplateMode.HTML, null), context);

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

        String html = engine.process(
                new TemplateSpec("fragments/projects", Set.of("gitPullControl"), TemplateMode.HTML, null), context);

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
        context.setVariable("projects",
                List.of(new Project(1L, "Alpha", "/repo", "",
                        List.of(new ProjectEnvironmentVariable("API_URL", "https://example.test"),
                                new ProjectEnvironmentVariable("FEATURE_FLAG", "true")),
                        "HOME, PATH")));
        context.setVariable("visibleProjects", List.of(new Project(1L, "Alpha", "/repo", "", List.of(), "HOME, PATH"),
                new Project(2L, "Beta", "/repo-b", "", List.of(), null)));
        context.setVariable("lifecycleHookSettings",
                new LifecycleHookSettings("echo <done>\nline 2", "echo error", "echo subagent", 45));
        context.setVariable("autoGitUpdateEnabled", true);
        context.setVariable("reviewPanelOpen", false);
        context.setVariable("mcpServers", List.of(new McpServerView(9L, "Local MCP", "http://localhost:3000/mcp", true,
                List.of(new McpServerHeader("Authorization", "Bearer token")), List.of(1L))));
        context.setVariable("activeProject",
                new Project(1L, "Alpha", "/repo", "",
                        List.of(new ProjectEnvironmentVariable("API_URL", "https://example.test"),
                                new ProjectEnvironmentVariable("FEATURE_FLAG", "true")),
                        "HOME, PATH"));
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
        context.setVariable("workspaceCloseStatus",
                new AppStateService.WorkspaceCloseInspection(0L, "", "", "", false, false, List.of()));

        String html = engine.process("fragments/projects", context);

        assertThat(normalizeWhitespace(html)).contains("id=\"settings-modal\"", "Environment variables", "API_URL",
                "https://example.test", "FEATURE_FLAG", "true", "Add Variable", "name=\"commandEnvironmentAllowlist\"",
                "HOME, PATH", "run command tool", "Terminal sessions retain the normal system environment");
        assertThat(html).contains("MCP Servers", "Local MCP", "http://localhost:3000/mcp", "Header name",
                "Authorization", "Bearer token", "Projects with access");
        assertThat(html).contains("Hooks", "Agent completion script", "Agent error script",
                "Subagent completion script", "name=\"assistantCompletedScript\"", "name=\"assistantErroredScript\"",
                "name=\"subagentCompletedScript\"", "Runs after the agent response completes.",
                "Runs when the agent execution fails.",
                "Runs when a subagent completes, using the parent session's context.", "name=\"timeoutSeconds\"",
                "min=\"1\"", "max=\"3600\"", "Hooks run asynchronously", "/bin/bash", "/tmp", "JUPITER_PROJECT_NAME",
                "JUPITER_WORKSPACE_NAME", "JUPITER_SESSION_NAME", "Project-configured environment variables",
                "/tmp/jupiter-hooks.log");
        assertThat(html).contains("echo &lt;done&gt;\nline 2").doesNotContain("echo <done>");
        assertThat(html).contains("class=\"nav nav-pills flex-md-column settings-nav\"",
                "id=\"settings-current-project\"", "id=\"settings-application\"", "id=\"settings-mcp-servers\"",
                "id=\"settings-model-providers\"", "id=\"settings-usage\"", "id=\"settings-help\"");
        assertThat(html.indexOf("id=\"settings-current-project-tab\""))
                .isLessThan(html.indexOf("id=\"settings-mcp-servers-tab\""));
        assertThat(html.indexOf("id=\"settings-mcp-servers-tab\""))
                .isLessThan(html.indexOf("id=\"settings-model-providers-tab\""));
        assertThat(html.indexOf("id=\"settings-model-providers-tab\""))
                .isLessThan(html.indexOf("id=\"settings-usage-tab\""));
        assertThat(html.indexOf("id=\"settings-usage-tab\"")).isLessThan(html.indexOf("id=\"settings-help-tab\""));
        assertThat(html).contains("data-bs-toggle=\"pill\"", "aria-selected=\"true\"");
        assertThat(html.split("data-settings-env-row", -1)).hasSize(4);
    }

    @Test
    public void environmentRowsPreserveEmptyPopulatedAndClientTemplateContracts() {
        SpringTemplateEngine engine = engine();

        WebContext empty = webContext();
        Project emptyProject = new Project(1L, "Alpha", "/repo", "", List.of(), null);
        empty.setVariable("activeProject", emptyProject);
        empty.setVariable("projects", List.of(emptyProject));
        empty.setVariable("mcpServers", List.of());
        String emptyHtml = engine.process(
                new TemplateSpec("fragments/projects", Set.of("settingsModal"), TemplateMode.HTML, null), empty);
        assertThat(emptyHtml.split("data-settings-env-row", -1)).hasSize(3);
        assertThat(emptyHtml)
                .contains("data-settings-env-template", "data-settings-env-add", "name=\"environmentVariableNames\"")
                .contains("name=\"environmentVariableValues\"");

        Project project = new Project(1L, "Alpha", "/repo", "",
                List.of(new ProjectEnvironmentVariable("API_URL", "https://example.test"),
                        new ProjectEnvironmentVariable("FEATURE_FLAG", "true")),
                null);
        WebContext populated = webContext();
        populated.setVariable("activeProject", project);
        populated.setVariable("projects", List.of(project));
        populated.setVariable("mcpServers", List.of());
        String populatedHtml = engine.process(
                new TemplateSpec("fragments/projects", Set.of("settingsModal"), TemplateMode.HTML, null), populated);
        assertThat(populatedHtml.split("data-settings-env-row", -1)).hasSize(4);
        assertThat(populatedHtml).contains("value=\"API_URL\"", "value=\"https://example.test\"",
                "value=\"FEATURE_FLAG\"", "value=\"true\"");
        String template = populatedHtml.substring(populatedHtml.indexOf("data-settings-env-template"));
        assertThat(template).contains("data-settings-env-row", "data-settings-env-remove")
                .contains("btn-outline-secondary");
    }

    @Test
    public void mcpCardsAndHeaderRowsPreserveBlankPersistedAndTemplateHooks() {
        SpringTemplateEngine engine = engine();
        Project project = new Project(1L, "Alpha", "/repo", "", List.of(), null);
        WebContext blank = webContext();
        blank.setVariable("activeProject", project);
        blank.setVariable("projects", List.of(project));
        blank.setVariable("visibleProjects", List.of(project));
        blank.setVariable("mcpServers", List.of());
        String blankHtml = engine.process(
                new TemplateSpec("fragments/projects", Set.of("settingsModal"), TemplateMode.HTML, null), blank);
        assertThat(blankHtml.split("data-settings-mcp-server", -1)).hasSizeGreaterThanOrEqualTo(4);
        assertThat(blankHtml).contains("data-settings-mcp-server-template", "data-settings-mcp-header-template",
                "data-mcp-server-enabled checked", "data-settings-mcp-add-server", "data-settings-mcp-add-header");

        McpServerView server = new McpServerView(9L, "Local MCP", "http://localhost:3000/mcp", false,
                List.of(new McpServerHeader("Authorization", "Bearer token")), List.of(1L));
        WebContext persisted = webContext();
        persisted.setVariable("activeProject", project);
        persisted.setVariable("projects", List.of(project));
        persisted.setVariable("visibleProjects", List.of(project));
        persisted.setVariable("mcpServers", List.of(server));
        String persistedHtml = engine.process(
                new TemplateSpec("fragments/projects", Set.of("settingsModal"), TemplateMode.HTML, null), persisted);
        assertThat(persistedHtml).contains("data-mcp-server-id=\"9\"", "value=\"Local MCP\"",
                "value=\"http://localhost:3000/mcp\"", "value=\"1\"", "Authorization", "Bearer token");
        int persistedCardStart = persistedHtml.indexOf("data-mcp-server-id=\"9\"");
        int persistedCardEnd = persistedHtml.indexOf("</div>", persistedCardStart);
        String persistedCard = persistedHtml.substring(persistedCardStart, persistedCardEnd);
        assertThat(persistedCard).contains("data-mcp-server-enabled").doesNotContain("checked");
        assertThat(persistedHtml).contains("data-mcp-server-header-row", "data-mcp-server-header-name",
                "data-mcp-server-header-value", "data-settings-mcp-remove-header");
    }

    @Test
    public void customCommandAddAndEditFormsShareFieldsButKeepIntentionalDifferences() {
        SpringTemplateEngine engine = engine();
        CommandCatalogService.CommandDefinition command = new CommandCatalogService.CommandDefinition("demo", "Demo",
                "Description", CommandCatalogService.CommandKind.SCRIPT, "echo demo", "/tmp", 20);
        WebContext context = webContext();
        context.setVariable("customCommands", List.of(command));
        String html = engine.process(
                new TemplateSpec("fragments/projects", Set.of("settingsCommands"), TemplateMode.HTML, null), context);

        assertThat(html).contains("hx-post=\"/ui/settings/commands/create\"",
                "hx-post=\"/ui/settings/commands/demo/update\"", "name=\"id\"", "name=\"name\"", "name=\"type\"",
                "name=\"description\"", "name=\"workingDir\"", "name=\"timeoutSeconds\"", "name=\"body\"");
        assertThat(html.split("name=\"id\"", -1)).hasSize(3);
        assertThat(html).contains("pattern=\"[a-z0-9][a-z0-9_-]*\"", "name=\"originalId\"",
                "hx-confirm=\"Delete this custom command?\"");
        assertThat(html.split("required", -1)).hasSizeGreaterThan(3);
    }

    @Test
    public void modalChromeKeepsIdsAriaLinkageAndCloseRequests() {
        SpringTemplateEngine engine = engine();
        WebContext context = webContext();
        context.setVariable("workspaceId", 7L);
        context.setVariable("workspaceName", "Feature");
        context.setVariable("workspaceCloseReasons", List.of("Uncommitted changes"));
        context.setVariable("branchName", "feature");
        context.setVariable("branchMode", "create");
        for (String fragment : List.of("modal", "settingsModal", "workspaceModal", "workspaceCloseModal")) {
            String html = engine.process(
                    new TemplateSpec("fragments/projects", Set.of(fragment), TemplateMode.HTML, null), context);
            String id = fragment.equals("modal")
                    ? "project-modal"
                    : fragment.equals("settingsModal")
                            ? "settings-modal"
                            : fragment.equals("workspaceModal") ? "workspace-modal" : "workspace-close-modal";
            String title = id + "-title";
            assertThat(html).contains("id=\"" + id + "\"", "role=\"dialog\"", "aria-labelledby=\"" + title + "\"",
                    "id=\"" + title + "\"", "hx-get=\"/ui/projects/modal/close\"", "hx-target=\"#modal-root\"",
                    "hx-swap=\"innerHTML\"");
        }
    }

    @Test
    public void modelSelectionsUseProviderEndpointsAndPersistedSelection() {
        SpringTemplateEngine engine = engine();
        ModelDefinition openAiModel = new ModelDefinition("openai/gpt-test", "GPT Test", "openai", "gpt-test", false,
                true, 1000, 100, null, null, null, null, null, List.of("text"), List.of("text"));
        ModelDefinition anthropicModel = new ModelDefinition("anthropic/claude-test", "Claude Test", "anthropic",
                "claude-test", false, true, 1000, 100, null, null, null, null, null, List.of("text"), List.of("text"));
        WebContext context = webContext();
        context.setVariable("modelGroups",
                Map.of("openai", List.of(openAiModel), "anthropic", List.of(anthropicModel)));
        context.setVariable("providerAvailability", Map.of("openai", true, "anthropic", true));
        context.setVariable("selectedModelIds", Map.of("openai", List.of("openai/gpt-test"), "anthropic", List.of()));
        String html = engine.process(
                new TemplateSpec("fragments/projects", Set.of("settingsModels"), TemplateMode.HTML, null), context);
        assertThat(html).contains("action=\"/ui/settings/models\"", "hx-post=\"/ui/settings/models\"",
                "name=\"provider\" value=\"openai\"", "name=\"provider\" value=\"anthropic\"",
                "value=\"openai/gpt-test\"", "selected", "value=\"anthropic/claude-test\"");
        assertThat(html.split("class=\"settings-model-selections\"", -1)).hasSize(3);
    }

    @Test
    public void anthropicAndModelsFragmentsRenderWithNullAndPopulatedContexts() {
        SpringTemplateEngine engine = engine();
        WebContext empty = webContext();
        String emptyHtml = engine.process(new TemplateSpec("fragments/projects",
                Set.of("anthropicOAuthSection", "settingsModels"), TemplateMode.HTML, null), empty);
        assertThat(emptyHtml).contains("Anthropic is not connected").doesNotContain("${view.message}");
        assertThat(emptyHtml).contains("id=\"settings-models\"", "class=\"settings-section\"");

        String oobHtml = engine.process(
                new TemplateSpec("fragments/projects", Set.of("settingsModelsOob"), TemplateMode.HTML, null), empty);
        assertThat(oobHtml).contains("id=\"settings-models\"").contains("hx-swap-oob=\"outerHTML:#settings-models\"");

        WebContext populated = webContext();
        populated.setVariable("modelGroups",
                Map.of("anthropic",
                        List.of(new ModelDefinition("anthropic/claude-test", "Claude Test", "anthropic", "claude-test",
                                false, true, 1000, 100, null, null, null, null, null, List.of("text"),
                                List.of("text")))));
        populated.setVariable("providerAvailability", Map.of("anthropic", true));
        populated.setVariable("selectedModelIds", Map.of("anthropic", List.of()));
        String populatedHtml = engine.process(
                new TemplateSpec("fragments/projects", Set.of("settingsModels"), TemplateMode.HTML, null), populated);
        assertThat(populatedHtml).contains("anthropic", "Claude Test");
    }

    @Test
    public void settingsUsageDataUsesEscapedAttributeTransport() throws Exception {
        SpringTemplateEngine engine = engine();
        String usageJson = new ObjectMapper().writeValueAsString(
                List.of(new UsagePoint("2026-08-31T12:00:00Z", "<historical model>", "model\"key", 1, 2L, null, 2L)));

        WebContext context = webContext();
        context.setVariable("usageRange", "24h");
        context.setVariable("usageJson", usageJson);

        String html = engine.process(
                new TemplateSpec("fragments/projects", Set.of("settingsUsage"), TemplateMode.HTML, null), context);

        String attribute = html.substring(html.indexOf("data-usage-data=\"") + "data-usage-data=\"".length());
        attribute = attribute.substring(0, attribute.indexOf('"'));
        assertThat(html).doesNotContain("<script", "<historical model>");
        assertThat(html).contains("aria-label=\"Stacked input and output token usage by hour and model\"");
        assertThat(attribute).contains("&quot;", "&lt;");
        assertThat(new ObjectMapper().readTree(HtmlUtils.htmlUnescape(attribute)).get(0).get("modelKey").asText())
                .isEqualTo("model\"key");
    }

    @Test
    public void settingsCommandsRenderEndpointsFieldsAndEscapedCommandContent() {
        SpringTemplateEngine engine = engine();
        WebContext context = webContext();
        CommandCatalogService.CommandDefinition command = new CommandCatalogService.CommandDefinition("unsafe-command",
                "<Unsafe name>", "<unsafe description>", CommandCatalogService.CommandKind.PROMPT,
                "Body <script>alert(1)</script> & text", "/tmp", 15);
        CommandCatalogService.CommandDefinition secondCommand = new CommandCatalogService.CommandDefinition(
                "second-command", "Second command", "Second description", CommandCatalogService.CommandKind.SCRIPT,
                "echo second", "/var/tmp", 30);
        context.setVariable("customCommands", List.of(command, secondCommand));
        CommandCatalogService.CommandDefinition external = new CommandCatalogService.CommandDefinition(
                "external-claude-cmV2aWV3", "Review", "External prompt", CommandCatalogService.CommandKind.PROMPT,
                "review body", null, null, "claude", "project", "/workspace/.claude/commands/review.md", false);
        context.setVariable("externalCommands", List.of(external));
        String html = engine.process(
                new TemplateSpec("fragments/projects", Set.of("settingsCommands"), TemplateMode.HTML, null), context);

        assertThat(html).contains("Commands discovered in your custom command folder", "unsafe-command",
                "second-command", "Review", "external-claude-cmV2aWV3", "claude / project", "External prompt",
                "name=\"id\"", "name=\"description\"", "name=\"body\"", "hx-post=\"/ui/settings/commands/create\"",
                "/ui/settings/commands/unsafe-command/update", "/ui/settings/commands/unsafe-command/delete",
                "/ui/settings/commands/second-command/update", "/ui/settings/commands/second-command/delete",
                "class=\"form-control\"", "class=\"form-select\"", "class=\"row g-3\"", "class=\"collapse\"",
                "data-bs-toggle=\"collapse\"", "aria-expanded=\"false\"", "aria-controls=\"settings-command-edit-0\"",
                "data-bs-target=\"#settings-command-edit-0\"", "id=\"settings-command-edit-0\"",
                "aria-controls=\"settings-command-edit-1\"", "data-bs-target=\"#settings-command-edit-1\"",
                "id=\"settings-command-edit-1\"");
        assertThat(html).contains("&lt;Unsafe name&gt;", "&lt;unsafe description&gt;",
                "Body &lt;script&gt;alert(1)&lt;/script&gt; &amp; text");
        assertThat(html).doesNotContain("<Unsafe name>", "<unsafe description>", "<script>alert(1)</script>");
        assertThat(html.split("class=\"collapse\"", -1)).hasSize(3);
        assertThat(html.split("data-bs-target=\"#settings-command-edit-", -1)).hasSize(3);
        assertThat(html).doesNotContain("external-claude-cmV2aWV3/update", "external-claude-cmV2aWV3/delete");
    }

    @Test
    public void settingsModalRendersHooksWithoutAnActiveProject() {
        SpringTemplateEngine engine = engine();
        WebContext context = webContext();
        context.setVariable("activeProject", null);
        context.setVariable("lifecycleHookSettings", new LifecycleHookSettings(null, "echo error", null, 30));
        context.setVariable("projects", List.of());
        context.setVariable("mcpServers", List.of());

        String html = engine.process(
                new TemplateSpec("fragments/projects", Set.of("settingsModal"), TemplateMode.HTML, null), context);

        assertThat(html).contains("id=\"settings-modal\"", "id=\"settings-hooks\"", "Hooks", "echo error",
                "value=\"30\"");
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
        context.setVariable("workspaceCloseStatus",
                new AppStateService.WorkspaceCloseInspection(0L, "", "", "", false, false, List.of()));

        String html = engine.process("fragments/projects", context);
        String nameInputHtml = inputTag(html, "project-name-input");
        String pathInputHtml = inputTag(html, "project-path-input");

        assertThat(nameInputHtml).contains("id=\"project-name-input\"", "name=\"name\"", "value=\"Home\"")
                .doesNotContain("hx-swap-oob");
        assertThat(pathInputHtml).contains("id=\"project-path-input\"", "name=\"path\"", "value=\"/home/jude\"")
                .doesNotContain("hx-swap-oob");
    }

    @Test
    public void workspaceRailRendersNewWorkspaceAndNewSessionControls() {
        SpringTemplateEngine engine = engine();

        WebContext context = webContext();
        context.setVariable("shellRefresh", false);
        context.setVariable("projects", List.of(new Project(1L, "Alpha", "/repo", null, List.of(), null)));
        context.setVariable("activeProject", new Project(1L, "Alpha", "/repo", null, List.of(), null));
        context.setVariable("workspaces",
                List.of(new Workspace(1L, "Default Workspace", "/repo", true, Persistence.RailStatus.NONE),
                        new Workspace(2L, "feature-workspace", "/repo/.trees/repo/feature-workspace", false,
                                Persistence.RailStatus.NONE)));
        context.setVariable("activeWorkspace",
                new Workspace(1L, "Default Workspace", "/repo", true, Persistence.RailStatus.NONE));
        context.setVariable("sessions", List.of(new Session(1L, "Session #1", true, Persistence.RailStatus.NONE),
                new Session(2L, "Session #2", false, Persistence.RailStatus.NONE)));
        context.setVariable("activeSession", new Session(2L, "Session #2", false, Persistence.RailStatus.NONE));
        context.setVariable("selectedName", "");
        context.setVariable("selectedPath", "");
        context.setVariable("currentPath", "");
        context.setVariable("directoryEntries", List.of());
        context.setVariable("includeChatContainer", false);
        context.setVariable("reviewPanelOpen", false);
        context.setVariable("reviewOob", false);
        context.setVariable("changedFiles", List.of());
        context.setVariable("selectedFile", null);
        context.setVariable("workspaceCloseStatus",
                new AppStateService.WorkspaceCloseInspection(0L, "", "", "", false, false, List.of()));

        String html = engine.process("fragments/projects", context);

        assertThat(html).contains("New Workspace", "New session");
        assertThat(html).contains("hx-get=\"/ui/workspaces/new\"", "hx-get=\"/ui/sessions/new\"");
        assertThat(html).contains("hx-target=\"this\"", "hx-swap=\"outerHTML\"");
        assertThat(html).contains("hx-post=\"/ui/workspaces/1/collapse\"", "hx-post=\"/ui/workspaces/2/activate\"");
        assertThat(html).contains("hx-post=\"/ui/workspaces/2/close\"", "hx-post=\"/ui/sessions/1/close\"",
                "hx-post=\"/ui/sessions/2/close\"");
        assertThat(html).doesNotContain("hx-post=\"/ui/workspaces/1/close\"");
        assertThat(html).contains("bi-chevron-down workspace-disclosure", "bi-chevron-right workspace-disclosure");
        assertThat(html).contains("Session #1", "Session #2");
        assertThat(html.split("class=\"unread-dot\" aria-label=\"Unread\"", -1)).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    public void railStatusIndicatorPreservesFailureUnreadAndProgressPrecedence() {
        SpringTemplateEngine engine = engine();
        WebContext context = webContext();

        context.setVariable("failed", true);
        context.setVariable("unread", true);
        context.setVariable("inProgress", true);
        String failedHtml = engine.process(new TemplateSpec("fragments/project-components",
                Set.of("railStatusIndicator"), TemplateMode.HTML, null), context);
        assertThat(failedHtml).contains("class=\"failed-dot\" aria-label=\"Failed\"").doesNotContain("unread-dot",
                "pending-dot");

        context.setVariable("failed", false);
        String unreadHtml = engine.process(new TemplateSpec("fragments/project-components",
                Set.of("railStatusIndicator"), TemplateMode.HTML, null), context);
        assertThat(unreadHtml).contains("class=\"unread-dot\" aria-label=\"Unread\"").doesNotContain("failed-dot",
                "pending-dot");
    }

    @Test
    public void oauthResponsesRenderProviderSectionBeforeSharedOobTail() {
        SpringTemplateEngine engine = engine();
        WebContext context = webContext();
        String html = engine.process(
                new TemplateSpec("fragments/projects", Set.of("openaiOAuthResponse"), TemplateMode.HTML, null),
                context);

        assertThat(html.indexOf("id=\"openai-oauth-section\"")).isLessThan(html.indexOf("id=\"settings-models\""));
        assertThat(html).doesNotContain("id=\"chat-controls\"");
    }

    @Test
    public void workspaceRailRendersPendingDotsForInProgressItems() {
        SpringTemplateEngine engine = engine();

        WebContext context = webContext();
        context.setVariable("shellRefresh", false);
        context.setVariable("projects", List.of(new Project(1L, "Alpha", "/repo", null, List.of(), null)));
        context.setVariable("activeProject", new Project(1L, "Alpha", "/repo", null, List.of(), null));
        context.setVariable("workspaces",
                List.of(new Workspace(1L, "Default Workspace", "/repo", false, Persistence.RailStatus.IN_PROGRESS),
                        new Workspace(2L, "feature-workspace", "/repo/.trees/repo/feature-workspace", false,
                                Persistence.RailStatus.NONE)));
        context.setVariable("activeWorkspace",
                new Workspace(1L, "Default Workspace", "/repo", false, Persistence.RailStatus.IN_PROGRESS));
        context.setVariable("sessions",
                List.of(new Session(1L, "Session #1", false, Persistence.RailStatus.IN_PROGRESS),
                        new Session(2L, "Session #2", false, Persistence.RailStatus.NONE)));
        context.setVariable("activeSession", new Session(2L, "Session #2", false, Persistence.RailStatus.NONE));
        context.setVariable("selectedName", "");
        context.setVariable("selectedPath", "");
        context.setVariable("currentPath", "");
        context.setVariable("directoryEntries", List.of());
        context.setVariable("includeChatContainer", false);
        context.setVariable("reviewPanelOpen", false);
        context.setVariable("reviewOob", false);
        context.setVariable("changedFiles", List.of());
        context.setVariable("selectedFile", null);
        context.setVariable("workspaceCloseStatus",
                new AppStateService.WorkspaceCloseInspection(0L, "", "", "", false, false, List.of()));

        String html = engine.process(
                new TemplateSpec("fragments/projects", Set.of("workspaceRail"), TemplateMode.HTML, null), context);

        assertThat(html).contains("aria-label=\"In progress\"");
        assertThat(html.split("class=\"pending-dot\"", -1)).hasSize(3);
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
        context.setVariable("workspaceCloseStatus",
                new AppStateService.WorkspaceCloseInspection(0L, "", "", "", false, false, List.of()));
        String html = engine.process("fragments/projects", context);

        assertThat(html).contains("<form", "class=\"session-create-form\"", "data-session-create-form",
                "hx-post=\"/ui/sessions/add\"");
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
        context.setVariable("workspaceCloseStatus",
                new AppStateService.WorkspaceCloseInspection(0L, "", "", "", false, false, List.of()));
        context.setVariable("branchName", "feature-workspace");
        context.setVariable("branchMode", "create");
        context.setVariable("createBranch", true);

        String html = engine.process("fragments/projects", context);

        assertThat(html).contains("id=\"workspace-modal\"", "hx-post=\"/ui/workspaces/add\"", "name=\"branchName\"",
                "data-workspace-branch-name");
        assertThat(html).containsPattern("<input\\s+[^>]*name=\"branchMode\"[^>]*value=\"create\"");
        assertThat(html).containsPattern("<input\\s+[^>]*name=\"branchMode\"[^>]*value=\"checkout\"");
        assertThat(html).contains("data-workspace-branch-mode");
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

        assertThat(html).containsPattern("<aside\\s+id=\"bottom-panel\"");
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
        context.setVariable("workspaceCloseStatus",
                new AppStateService.WorkspaceCloseInspection(0L, "", "", "", false, false, List.of()));
        context.setVariable("bottomPanelMode", "terminal");
        context.setVariable("bottomPanelOpen", true);
        context.setVariable("terminalTabs", List.of());
        context.setVariable("activeTerminal", null);
        context.setVariable("terminalOob", false);

        String html = engine.process("fragments/projects", context);

        assertThat(html).containsPattern("<aside\\s+id=\"review\"");
        assertThat(html).containsPattern("<aside\\s+id=\"bottom-panel\"");
        assertThat(html).contains("id=\"toggle-review-rail-btn\"", "hx-post=\"/ui/review/toggle\"",
                "hx-target=\"#review\"");
        assertThat(html).doesNotContain("/ui/panel/review");
    }

    private static String normalizeWhitespace(String html) {
        return html.replaceAll("\\s+", " ");
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
        WebContext context = new WebContext(application.buildExchange(request, new MockHttpServletResponse()),
                Locale.US);
        context.setVariable("workspaceCloseStatus",
                new AppStateService.WorkspaceCloseInspection(0L, "", "", "", false, false, List.of()));
        return context;
    }
}

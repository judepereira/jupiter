package com.judepereira.jupiter.ui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.catalog.*;
import com.judepereira.jupiter.agent.config.AgentProperties;
import com.judepereira.jupiter.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter.agent.mcp.McpProjectMcpServerRuntimeManager;
import com.judepereira.jupiter.agent.mcp.McpRuntimeEvents;
import com.judepereira.jupiter.agent.tools.impl.FileUtils;
import com.judepereira.jupiter.command.CommandCatalogService;
import com.judepereira.jupiter.command.CommandCatalogService.CommandDefinition;
import com.judepereira.jupiter.command.CommandCatalogService.CommandKind;
import com.judepereira.jupiter.command.CommandCatalogService.CommandMutationException;
import com.judepereira.jupiter.command.CommandStreamService;
import com.judepereira.jupiter.config.HttpAuthProperties;
import com.judepereira.jupiter.config.PublicRequestScheme;
import com.judepereira.jupiter.git.GitAutoUpdateService;
import com.judepereira.jupiter.git.ManualGitPullCoordinator;
import com.judepereira.jupiter.lifecycle.LifecycleHookService;
import com.judepereira.jupiter.openai.oauth.OpenAiOAuthService;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.ContextCompactionService;
import com.judepereira.jupiter.persistence.GitWorktreeException;
import com.judepereira.jupiter.persistence.InvalidGitBranchNameException;
import com.judepereira.jupiter.persistence.Persistence.*;
import com.judepereira.jupiter.persistence.TokenUsageService;
import com.judepereira.jupiter.terminal.TerminalHandle;
import com.judepereira.jupiter.terminal.TerminalManager;
import com.judepereira.jupiter.terminal.TerminalPanelState;
import com.judepereira.jupiter.terminal.TerminalStateService;
import com.judepereira.jupiter.ui.ChatPresentationService.ChatMessage;
import com.judepereira.jupiter.ui.ChatStreamRuntime.ActiveStream;
import com.judepereira.jupiter.ui.balloon.SystemBalloonService;
import com.judepereira.jupiter.ui.rail.WorkspaceRailRefreshService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.Locale;
import java.util.stream.Collectors;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Log4j2
@Controller
public class UiController {

    private static final ObjectMapper SseJson = new ObjectMapper();
    private static final String DEFAULT_APP_VERSION = "0.0.1-SNAPSHOT";

    private final CodingAgentHarness harness;
    private final AgentProperties agentProperties;
    private final AppStateService appStateService;
    private final AgentDefinitionService agentDefinitionService;
    private final ModelCatalogService modelCatalogService;
    private final AgentModelResolutionService agentModelResolutionService;
    private final AgentPreferenceResolver agentPreferenceResolver;
    private final AgentPreferenceService agentPreferenceService;
    private final ModelPickerService modelPickerService;
    private final ModelPreferencesService modelPreferencesService;
    private final ProviderAvailabilityService providerAvailabilityService;
    private final ContextCompactionService contextCompactionService;
    private final TokenUsageService tokenUsageService;
    private final CommandStreamService commandStreamService;
    private final CommandCatalogService commandCatalogService;
    private final McpProjectMcpServerRuntimeManager mcpRuntimeManager;
    private final TerminalManager terminalManager;
    private final TerminalStateService terminalStateService;
    private final SystemBalloonService systemBalloonService;
    private final WorkspaceRailRefreshService workspaceRailRefreshService;
    private final ActiveStreamRegistryService activeStreamRegistryService;
    private final OpenAiOAuthService openAiOAuthService;
    private final ChatPresentationService chatPresentationService;
    private final ChatToolCallHtmlService chatToolCallHtmlService;
    private final LifecycleHookService lifecycleHookService;
    private final HttpAuthProperties httpAuthProperties;
    private final GitAutoUpdateService gitAutoUpdateService;
    private final ManualGitPullCoordinator manualGitPullCoordinator;
    private final String appVersion;
    private final String userHome;

    private final ChatStreamRuntime chatStreamRuntime;

    private ManualGitPullCoordinator manualGitPullCoordinator() {
        return manualGitPullCoordinator;
    }

    @GetMapping("/ui/chat/stream/{assistantId}")
    public SseEmitter streamChat(@PathVariable("assistantId") String assistantId) {
        return chatStreamRuntime.streamChat(assistantId);
    }

    @Autowired
    public UiController(CodingAgentHarness harness, AgentProperties agentProperties, AppStateService appStateService,
            AgentDefinitionService agentDefinitionService, ModelCatalogService modelCatalogService,
            AgentModelResolutionService agentModelResolutionService, AgentPreferenceResolver agentPreferenceResolver,
            AgentPreferenceService agentPreferenceService, ModelPickerService modelPickerService,
            ModelPreferencesService modelPreferencesService, ProviderAvailabilityService providerAvailabilityService,
            SystemBalloonService systemBalloonService, WorkspaceRailRefreshService workspaceRailRefreshService,
            ActiveStreamRegistryService activeStreamRegistryService, TerminalManager terminalManager,
            TerminalStateService terminalStateService, OpenAiOAuthService openAiOAuthService,
            ContextCompactionService contextCompactionService, TokenUsageService tokenUsageService,
            CommandStreamService commandStreamService, CommandCatalogService commandCatalogService,
            McpProjectMcpServerRuntimeManager mcpRuntimeManager, ChatPresentationService chatPresentationService,
            ChatToolCallHtmlService chatToolCallHtmlService, LifecycleHookService lifecycleHookService,
            HttpAuthProperties httpAuthProperties, GitAutoUpdateService gitAutoUpdateService,
            ManualGitPullCoordinator manualGitPullCoordinator, ChatStreamRuntime chatStreamRuntime,
            @Value("${app.version:" + DEFAULT_APP_VERSION + "}") String appVersion,
            @Value("${user.home}") String userHome) {
        this.chatStreamRuntime = chatStreamRuntime;
        this.harness = harness;
        this.agentProperties = agentProperties;
        this.appStateService = appStateService;
        this.agentDefinitionService = agentDefinitionService;
        this.modelCatalogService = modelCatalogService;
        this.agentModelResolutionService = agentModelResolutionService;
        this.agentPreferenceResolver = agentPreferenceResolver;
        this.agentPreferenceService = agentPreferenceService;
        this.modelPickerService = modelPickerService;
        this.modelPreferencesService = modelPreferencesService;
        this.providerAvailabilityService = providerAvailabilityService;
        this.contextCompactionService = contextCompactionService;
        this.tokenUsageService = tokenUsageService;
        this.commandStreamService = commandStreamService;
        this.commandCatalogService = commandCatalogService;
        this.mcpRuntimeManager = mcpRuntimeManager;
        this.systemBalloonService = systemBalloonService;
        this.activeStreamRegistryService = activeStreamRegistryService;
        this.terminalManager = terminalManager;
        this.terminalStateService = terminalStateService;
        this.workspaceRailRefreshService = workspaceRailRefreshService;
        this.openAiOAuthService = openAiOAuthService;
        this.chatPresentationService = chatPresentationService;
        this.chatToolCallHtmlService = chatToolCallHtmlService;
        this.lifecycleHookService = lifecycleHookService;
        this.httpAuthProperties = httpAuthProperties;
        this.gitAutoUpdateService = gitAutoUpdateService;
        this.manualGitPullCoordinator = manualGitPullCoordinator;
        this.appVersion = appVersion;
        this.userHome = userHome;
    }

    @GetMapping("/")
    public String index(Model model) {
        AppStateView view = appStateService.loadViewData();
        populateChatControlsModel(model, activeChatSelection(view));
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        return "index";
    }

    public String sendMessage(@RequestParam("message") String message, Model model, HttpServletRequest request) {
        return sendMessage(message, null, null, null, model, request);
    }

    @PostMapping("/ui/chat/send")
    public String sendMessage(@RequestParam("message") String message,
            @RequestParam(value = "agentId", required = false) String agentId,
            @RequestParam(value = "modelId", required = false) String modelId,
            @RequestParam(value = "thinkingLevel", required = false) String thinkingLevel, Model model,
            HttpServletRequest request) {
        List<ChatMessage> newChatMessages = new ArrayList<>();
        AppStateView view = appStateService.loadViewData();
        SessionView session = null;
        boolean shellRefresh = false;
        ChatSelection selected = resolveChatSelection(agentId, modelId, thinkingLevel);

        if (message != null && !message.isBlank()) {
            shellRefresh = view.activeSession() == null;
            session = shellRefresh
                    ? appStateService.ensureChatSession(agentProperties.getWorkspaceRoot())
                    : view.activeSession();
            var submission = chatStreamRuntime.submit(session.id(), message, agentId, modelId, thinkingLevel);
            if (submission.summary() != null) {
                newChatMessages.add(toChatMessage(submission.summary()));
            }
            newChatMessages.add(toChatMessage(submission.queuedTurn().userMessage()));
            newChatMessages.add(toChatMessage(submission.queuedTurn().assistantMessage()));
            view = appStateService.loadViewData();
        }

        populateChatControlsModel(model, selected);
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        model.addAttribute("newChatMessages", List.copyOf(newChatMessages));
        model.addAttribute("pendingStreamBaseUrl", "/ui/chat/stream");
        model.addAttribute("subagentView", false);
        boolean hasPending = view.activeSessionDetail() != null
                && view.activeSessionDetail().chatMessages().stream().anyMatch(ChatMessageView::pending);
        model.addAttribute("hasPending", hasPending);
        model.addAttribute("shellRefresh", shellRefresh);
        model.addAttribute("includeChatContainer", false);
        model.addAttribute("reviewOob", shellRefresh
                || (view.activeSessionDetail() != null && !hasPending && view.activeSessionDetail().reviewPanelOpen()));
        return "fragments/chat-response :: response";
    }

    @GetMapping("/ui/review/file/{id}")
    public String loadFile(@PathVariable("id") int id, Model model) {
        AppStateView view = appStateService.loadViewData();
        if (view.activeSession() != null && view.activeSessionDetail() != null) {
            ChangedFileView found = view.activeSessionDetail().changedFiles().stream()
                    .filter(f -> f.id() != null && f.id() == id).findFirst().orElse(null);
            if (found != null) {
                appStateService.selectSessionChangedFile(view.activeSession().id(), id);
                view = appStateService.loadViewData();
            }
        }
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        model.addAttribute("reviewOob", false);
        return "fragments/review :: panel";
    }

    public String loadFile(ReviewSource source, String key, Model model) {
        return loadReviewFile(source, key, false, model);
    }

    @GetMapping("/ui/review/file")
    public String loadFile(@RequestParam("source") ReviewSource source, @RequestParam("key") String key,
            @RequestParam(value = "close", defaultValue = "false") boolean close, Model model) {
        return loadReviewFile(source, key, close, model);
    }

    private String loadReviewFile(ReviewSource source, String key, boolean close, Model model) {
        AppStateView view = appStateService.loadViewData();
        ChangedFileView selectedFile = null;
        if (view.activeSession() != null) {
            long sessionId = view.activeSession().id();
            if (close) {
                if (source == ReviewSource.SESSION) {
                    appStateService.clearSessionChangedFileSelection(sessionId);
                }
            } else if (source == ReviewSource.GIT) {
                selectedFile = appStateService.selectGitChangedFile(sessionId,
                        key.startsWith("git:") ? key.substring(4) : key);
                appStateService.switchReviewSource(sessionId, source);
            } else {
                appStateService.selectSessionChangedFile(sessionId,
                        Integer.parseInt(key.startsWith("session:") ? key.substring(8) : key));
                view = appStateService.loadViewData();
                selectedFile = view.activeSessionDetail().selectedFile();
            }
            view = appStateService.loadViewData();
        }
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        if (selectedFile != null) {
            model.addAttribute("selectedFile", toChangedFile(selectedFile));
        }
        model.addAttribute("reviewOob", false);
        return "fragments/review :: panel";
    }

    @PostMapping("/ui/chat/stop")
    public String stopChat(@RequestParam(value = "assistantId", required = false) String assistantId, Model model) {
        AppStateView view = appStateService.loadViewData();
        SessionView session = view.activeSession();
        if (session == null) {
            populateChatControlsModel(model, activeChatSelection(view));
            populateProjectModel(model, view);
            populateSessionModel(model, view);
            return "fragments/chat :: chat";
        }

        String targetAssistantId = assistantId;
        ActiveStream active = targetAssistantId == null || targetAssistantId.isBlank()
                ? null
                : chatStreamRuntime.activeStreams.get(targetAssistantId);
        if (active != null && active.pendingStream().sessionId() != session.id()) {
            throw new IllegalStateException("Assistant stream does not belong to the active session");
        }
        if (active == null) {
            active = chatStreamRuntime.activeStreams.values().stream()
                    .filter(s -> s.pendingStream().sessionId() == session.id()).findFirst().orElse(null);
        }
        if (active == null) {
            if (targetAssistantId != null && !targetAssistantId.isBlank()) {
                Long streamSessionId = activeStreamRegistryService.sessionIdForAssistantId(targetAssistantId)
                        .orElse(null);
                if (streamSessionId != null && streamSessionId != session.id()) {
                    throw new IllegalStateException("Assistant stream does not belong to the active session");
                }
                commandStreamService.stop(targetAssistantId);
            } else {
                activeStreamRegistryService.assistantIdForSession(session.id()).ifPresent(commandStreamService::stop);
            }
            view = appStateService.loadViewData();
            populateChatControlsModel(model, activeChatSelection(view));
            populateProjectModel(model, view);
            populateSessionModel(model, view);
            return "fragments/chat :: chat";
        }

        if (targetAssistantId == null || targetAssistantId.isBlank()) {
            ActiveStream targetActive = active;
            targetAssistantId = chatStreamRuntime.activeStreams.entrySet().stream()
                    .filter(entry -> entry.getValue() == targetActive).map(Map.Entry::getKey).findFirst().orElseThrow();
        }
        chatStreamRuntime.stopActiveStream(targetAssistantId, active);
        view = appStateService.loadViewData();
        populateChatControlsModel(model, activeChatSelection(view));
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        return "fragments/chat :: chat";
    }

    @GetMapping("/ui/chat/tool-call/{assistantPublicId}/{toolCallId}")
    @ResponseBody
    public String loadToolCallGroup(@PathVariable String assistantPublicId, @PathVariable String toolCallId) {
        return chatToolCallHtmlService.lazyGroup(assistantPublicId, toolCallId);
    }

    @GetMapping("/ui/chat/image/{sessionId}/{toolCallId}")
    ResponseEntity<byte[]> streamDisplayImage(@PathVariable long sessionId, @PathVariable String toolCallId)
            throws Exception {
        AppStateService.DisplayImageView view = appStateService.loadDisplayImageView(sessionId, toolCallId);
        Path workspace = Path.of(view.workspaceRoot());
        Path resolved = FileUtils.resolveWorkspacePath(workspace, view.path());
        if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) {
            throw new IllegalStateException("Image file not found: " + view.path());
        }
        String mediaType = FileUtils.resolveAllowedImageMediaType(Files.probeContentType(resolved), view.path());
        if (mediaType == null) {
            throw new IllegalStateException("Unsupported image type: " + view.path());
        }
        byte[] bytes = Files.readAllBytes(resolved);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, mediaType)
                .header("X-Content-Type-Options", "nosniff").header(HttpHeaders.CACHE_CONTROL, "no-store").body(bytes);
    }

    @GetMapping("/ui/chat/primary")
    public String loadPrimaryChat(Model model) {
        AppStateView view = appStateService.loadViewData();
        if (view.activeSession() == null || view.activeSessionDetail() == null) {
            throw new IllegalStateException("No active primary session");
        }

        populateChatControlsModel(model, activeChatSelection(view));
        model.addAttribute("activeSession", toSession(view.activeSession()));
        model.addAttribute("chatDraft", view.activeSessionDetail().chatDraft());
        populateChatModel(model, view.activeSessionDetail().chatMessages(), false, null, null, null);
        model.addAttribute("forkSessionId", view.activeSession().id());
        return "fragments/chat :: chat";
    }

    @GetMapping("/ui/chat/subagent/{sessionId}")
    public String loadSubagentChat(@PathVariable long sessionId, Model model) {
        AppStateView view = appStateService.loadViewData();
        if (view.activeSession() == null) {
            throw new IllegalStateException("No active primary session");
        }

        SubagentSessionDetailView subagent = appStateService.loadSubagentSessionDetail(sessionId);
        if (subagent.parentSessionId() == null || subagent.parentSessionId() != view.activeSession().id()) {
            throw new IllegalStateException(
                    "Subagent session does not belong to the active primary session: " + sessionId);
        }

        populateChatControlsModel(model, defaultChatSelection());
        populateChatModel(model, subagent.sessionDetail().chatMessages(), true, subagent.subagentAgentName(),
                subagent.subagentAgentId(), sessionId);
        return "fragments/chat :: chat";
    }

    @PostMapping("/ui/chat/fork/{assistantPublicId}")
    public String forkPrimaryChat(@PathVariable String assistantPublicId, Model model) {
        AppStateView before = appStateService.loadViewData();
        if (before.activeSession() == null) {
            throw new IllegalStateException("No active primary session");
        }
        appStateService.forkPrimarySessionAtAssistantMessage(before.activeSession().id(), assistantPublicId);
        AppStateView view = appStateService.loadViewData();
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        populateShellUpdates(model, view);
        return "fragments/projects :: shellUpdates";
    }

    public SseEmitter systemBalloonStream(@RequestParam(value = "shellId", required = false) String shellId) {
        return systemBalloonStream(shellId, null);
    }

    @GetMapping("/ui/system-balloons/stream")
    public SseEmitter systemBalloonStream(@RequestParam(value = "shellId", required = false) String shellId,
            HttpServletRequest request) {
        SseEmitter emitter = systemBalloonService.connect();
        if (systemBalloonService.markShellInitialized(shellId)) {
            sendInitialMcpFailureBalloons(emitter);
            if (httpAuthProperties.enabled() && request != null && !PublicRequestScheme.isHttps(request)) {
                systemBalloonService.publishWarning(emitter, "Use HTTPS",
                        "Jupiter HTTP Basic authentication is enabled, but this connection is not using HTTPS. Configure HTTPS at the public proxy.");
            }
        }
        return emitter;
    }

    @GetMapping("/ui/workspaces/rail/stream")
    public SseEmitter workspaceRailStream() {
        return workspaceRailRefreshService.connect();
    }

    @PostMapping("/ui/review/toggle")
    public String toggleReview(Model model) {
        AppStateView view = appStateService.loadViewData();
        if (view.activeSession() != null) {
            appStateService.toggleReviewPanel(view.activeSession().id());
            view = appStateService.loadViewData();
        }
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        model.addAttribute("reviewOob", false);
        return "fragments/review :: panel";
    }

    @PostMapping("/ui/review/source")
    public String switchReviewSource(@RequestParam("source") ReviewSource source, Model model) {
        AppStateView view = appStateService.loadViewData();
        if (view.activeSession() != null) {
            appStateService.switchReviewSource(view.activeSession().id(), source);
            view = appStateService.loadViewData();
        }
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        model.addAttribute("reviewOob", false);
        return "fragments/review :: panel";
    }

    @PostMapping("/ui/panel/review")
    public String openReviewPanel(Model model) {
        AppStateView view = currentViewWithSessionIfNeeded(false);
        if (view.activeSession() != null
                && (view.activeSessionDetail() == null || !view.activeSessionDetail().reviewPanelOpen())) {
            appStateService.toggleReviewPanel(view.activeSession().id());
            view = appStateService.loadViewData();
        }
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        model.addAttribute("reviewOob", false);
        return "fragments/review :: panel";
    }

    @PostMapping("/ui/panel/terminal")
    public String openTerminalPanel(Model model) {
        AppStateView view = appStateService.loadViewData();
        if (view.activeWorkspace() != null) {
            TerminalPanelState state = terminalStateService.snapshot(view.activeWorkspace().id());
            if (state.bottomPanelOpen()) {
                terminalStateService.closeTerminalPane(view.activeWorkspace().id());
            } else {
                if (state.terminalTabs().isEmpty()) {
                    TerminalHandle terminal = terminalManager.createTerminal(view.activeWorkspace().path(),
                            activeProjectEnvironmentVariables(view));
                    terminalStateService.registerTerminal(view.activeWorkspace().id(), terminal);
                }
                terminalStateService.openTerminalPane(view.activeWorkspace().id());
            }
            view = appStateService.loadViewData();
        }
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        return "fragments/terminal :: panel";
    }

    @PostMapping("/ui/terminal/new")
    public String newTerminal(Model model) {
        AppStateView view = appStateService.loadViewData();
        if (view.activeWorkspace() != null) {
            TerminalHandle terminal = terminalManager.createTerminal(view.activeWorkspace().path(),
                    activeProjectEnvironmentVariables(view));
            terminalStateService.registerTerminal(view.activeWorkspace().id(), terminal);
            terminalStateService.openTerminalPane(view.activeWorkspace().id());
            view = appStateService.loadViewData();
        }
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        return "fragments/terminal :: panel";
    }

    @PostMapping("/ui/terminal/{id}/activate")
    public String activateTerminal(@PathVariable String id, Model model) {
        AppStateView view = appStateService.loadViewData();
        if (view.activeWorkspace() != null) {
            terminalStateService.activateTerminal(view.activeWorkspace().id(), id);
            terminalStateService.openTerminalPane(view.activeWorkspace().id());
            view = appStateService.loadViewData();
        }
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        return "fragments/terminal :: panel";
    }

    @PostMapping("/ui/terminal/{id}/close")
    public String closeTerminal(@PathVariable String id, Model model) {
        AppStateView view = appStateService.loadViewData();
        if (view.activeWorkspace() != null) {
            terminalStateService.closeTerminal(view.activeWorkspace().id(), id);
            terminalManager.closeTerminal(id);
            view = appStateService.loadViewData();
        }
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        return "fragments/terminal :: panel";
    }

    @GetMapping("/ui/panel/{name}")
    public String panelPlaceholder(@PathVariable String name, Model model) {
        model.addAttribute("panelName", name);
        return "fragments/panel :: panel";
    }

    @GetMapping("/ui/projects/new")
    public String newProjectModal(Model model) {
        Path home = Path.of(userHome).toAbsolutePath().normalize();
        model.addAttribute("currentPath", home.toString());
        model.addAttribute("selectedPath", home.toString());
        model.addAttribute("startPath", home.toString());
        model.addAttribute("selectedName", directoryDisplayName(home));
        model.addAttribute("directoryEntries", listDirectoryEntries(home));
        AppStateView view = appStateService.loadViewData();
        populateProjectModel(model, view);
        return "fragments/projects :: modal";
    }

    @GetMapping("/ui/workspaces/new")
    public String newWorkspaceModal(Model model) {
        AppStateView view = appStateService.loadViewData();
        populateProjectModel(model, view);
        model.addAttribute("branchName", "");
        model.addAttribute("branchMode", "create");
        model.addAttribute("createBranch", true);
        return view.activeProject() == null
                ? "fragments/projects :: modalClose"
                : "fragments/projects :: workspaceModal";
    }

    @GetMapping("/ui/projects/directory")
    public String listDirectory(@RequestParam("path") String path, Model model) {
        Path current = Path.of(path).toAbsolutePath().normalize();
        model.addAttribute("directoryEntries", listDirectoryEntries(current));
        model.addAttribute("name", directoryDisplayName(current));
        model.addAttribute("path", current.toString());
        model.addAttribute("selectedPath", current.toString());
        model.addAttribute("selectedName", directoryDisplayName(current));
        model.addAttribute("expanded", true);
        return "fragments/directory-list :: nodeResponse";
    }

    @GetMapping("/ui/projects/directory/collapse")
    public String collapseDirectory(@RequestParam("path") String path, Model model) {
        Path current = Path.of(path).toAbsolutePath().normalize();
        model.addAttribute("name", directoryDisplayName(current));
        model.addAttribute("path", current.toString());
        model.addAttribute("selectedPath", current.toString());
        model.addAttribute("selectedName", directoryDisplayName(current));
        model.addAttribute("expanded", false);
        return "fragments/directory-list :: nodeResponse";
    }

    @GetMapping("/ui/projects/modal/close")
    public String closeProjectModal() {
        return "fragments/projects :: modalClose";
    }

    @GetMapping("/ui/settings")
    public String settingsModal(Model model) {
        AppStateView view = appStateService.loadViewData();
        populateProjectModel(model, view);
        populateSettingsModel(model);
        return "fragments/projects :: settingsModal";
    }

    private void populateSettingsModel(Model model) {
        model.addAttribute("lifecycleHookSettings", appStateService.loadLifecycleHookSettings());
        model.addAttribute("autoGitUpdateEnabled", appStateService.loadAutoGitUpdateEnabled());
        model.addAttribute("openAiOAuthView", openAiOAuthService.currentView());
        model.addAttribute("customCommands", commandCatalogService.listCustom());
        AppStateView view = appStateService.loadViewData();
        Path workspace = view.activeSessionDetail() == null
                ? null
                : Path.of(view.activeSessionDetail().workspaceRoot());
        model.addAttribute("externalCommands",
                commandCatalogService.list(workspace).stream().filter(command -> !command.editable()).toList());
        populateSettingsModels(model);
        model.addAttribute("agentPreferences", agentPreferenceService.list());
        model.addAttribute("mcpServers", appStateService.listMcpServers());
        Map<Long, McpProjectMcpServerRuntimeManager.DiscoveredServer> discoveredMcpServers = new LinkedHashMap<>();
        if (mcpRuntimeManager != null) {
            for (var discoveredServer : mcpRuntimeManager.discoveredServers()) {
                discoveredMcpServers.put(discoveredServer.serverId(), discoveredServer);
            }
        }
        model.addAttribute("discoveredMcpServers", discoveredMcpServers);
    }

    @PostMapping("/ui/settings/agents/save")
    public String saveAgentPreference(@RequestParam String agentId,
            @RequestParam(value = "modelId", required = false) String modelId,
            @RequestParam(value = "thinkingLevel", required = false) String thinkingLevel, Model model) {
        try {
            agentPreferenceService.save(agentId, modelId, thinkingLevel);
        } catch (IllegalArgumentException e) {
            populateSettingsModel(model);
            model.addAttribute("agentPreferenceError", e.getMessage());
            model.addAttribute("agentPreferenceSubmittedAgentId", agentId);
            model.addAttribute("agentPreferenceSubmittedModelId", modelId == null ? "" : modelId);
            model.addAttribute("agentPreferenceSubmittedThinkingLevel", thinkingLevel == null ? "" : thinkingLevel);
            return "fragments/projects :: settingsAgents";
        }
        populateSettingsModel(model);
        model.addAttribute("agentPreferenceSuccess", "Agent defaults saved.");
        populateChatControlsOob(model);
        return "fragments/projects :: settingsAgentsOobResponse";
    }

    @PostMapping("/ui/settings/agents/reset")
    public String resetAgentPreference(@RequestParam String agentId, Model model) {
        agentPreferenceService.reset(agentId);
        populateSettingsModel(model);
        model.addAttribute("agentPreferenceSuccess", "Agent defaults reset to built-in values.");
        populateChatControlsOob(model);
        return "fragments/projects :: settingsAgentsOobResponse";
    }

    private void populateChatControlsOob(Model model) {
        AppStateView view = appStateService.loadViewData();
        populateChatControlsModel(model, activeChatSelection(view));
        model.addAttribute("chatControlsOob", true);
    }

    private void populateSettingsModels(Model model) {
        var modelGroups = modelCatalogService.list().stream()
                .collect(Collectors.groupingBy(ModelDefinition::provider, LinkedHashMap::new, Collectors.toList()));
        var availability = modelGroups.keySet().stream().collect(Collectors.toMap(provider -> provider,
                providerAvailabilityService::isAvailable, (left, right) -> right, LinkedHashMap::new));
        model.addAttribute("modelGroups", modelGroups);
        model.addAttribute("providerAvailability", availability);
        model.addAttribute("selectedModelIds",
                availability.entrySet().stream().filter(Map.Entry::getValue)
                        .collect(Collectors.toMap(Map.Entry::getKey,
                                entry -> modelPreferencesService.selectedModelIds(entry.getKey()),
                                (left, right) -> right, LinkedHashMap::new)));
    }

    @PostMapping("/ui/settings/models")
    public String replaceSelectedModels(@RequestParam String provider,
            @RequestParam(value = "modelId", required = false) List<String> modelIds, Model model) {
        if (!modelCatalogService.hasProviderModel(provider)) {
            throw new IllegalArgumentException("Unsupported model provider: " + provider);
        }
        if (!providerAvailabilityService.isAvailable(provider)) {
            throw new IllegalArgumentException("Model provider is not connected: " + provider);
        }
        if (modelIds == null || modelIds.isEmpty()) {
            populateSettingsModel(model);
            model.addAttribute("modelSelectionError", "Select at least one model before saving.");
            return "fragments/projects :: settingsModelsOobResponse";
        }
        modelPreferencesService.replaceSelectedModelIds(provider, modelIds);
        populateSettingsModel(model);
        populateChatControlsOob(model);
        return "fragments/projects :: settingsModelsOobResponse";
    }

    @PostMapping("/ui/settings/commands/create")
    public String createCommand(@RequestParam(value = "id", required = false) String id,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "body", required = false) String body,
            @RequestParam(value = "workingDir", required = false) String workingDir,
            @RequestParam(value = "timeoutSeconds", required = false) String timeout, Model model) {
        return mutateCommand(model, null, id, name, description, type, body, workingDir, timeout, false);
    }

    @PostMapping("/ui/settings/commands/{originalId}/update")
    public String updateCommand(@PathVariable String originalId,
            @RequestParam(value = "id", required = false) String id,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "body", required = false) String body,
            @RequestParam(value = "workingDir", required = false) String workingDir,
            @RequestParam(value = "timeoutSeconds", required = false) String timeout, Model model) {
        return mutateCommand(model, originalId, id, name, description, type, body, workingDir, timeout, true);
    }

    @PostMapping("/ui/settings/commands/{id}/delete")
    public String deleteCommand(@PathVariable String id, Model model) {
        String message;
        try {
            commandCatalogService.delete(id);
            message = "Command deleted.";
        } catch (CommandMutationException | IllegalArgumentException e) {
            message = safeCommandError(e);
        }
        return renderCommands(model, message, null, null, null, null);
    }

    private String mutateCommand(Model model, String originalId, String id, String name, String description,
            String type, String body, String workingDir, String timeout, boolean update) {
        CommandDefinition submitted = null;
        try {
            CommandKind kind = parseKind(type);
            Integer timeoutSeconds = parseTimeout(timeout);
            submitted = new CommandDefinition(id, name, description, kind, body, workingDir, timeoutSeconds);
            if (update) {
                commandCatalogService.update(originalId, submitted);
            } else {
                commandCatalogService.create(submitted);
            }
            return renderCommands(model, update ? "Command updated." : "Command added.", null, null, null, null);
        } catch (CommandMutationException | IllegalArgumentException e) {
            // A null type is intentional when parsing failed: Thymeleaf can still render
            // the submitted form safely.
            if (submitted == null) {
                submitted = new CommandDefinition(id, name, description, null, body, workingDir, null);
            }
            return renderCommands(model, null, safeCommandError(e), submitted, originalId,
                    update ? "update" : "create");
        }
    }

    private String renderCommands(Model model, String success, String error, CommandDefinition submitted,
            String submittedOriginalId, String submittedOperation) {
        model.addAttribute("customCommands", commandCatalogService.listCustom());
        AppStateView view = appStateService.loadViewData();
        Path workspace = view.activeSessionDetail() == null
                ? null
                : Path.of(view.activeSessionDetail().workspaceRoot());
        model.addAttribute("externalCommands",
                commandCatalogService.list(workspace).stream().filter(command -> !command.editable()).toList());
        model.addAttribute("commandSuccess", success);
        model.addAttribute("commandError", error);
        model.addAttribute("submittedCommand", submitted);
        model.addAttribute("submittedCommandOriginalId", submittedOriginalId);
        model.addAttribute("submittedCommandOperation", submittedOperation);
        return "fragments/projects :: settingsCommands";
    }

    private static CommandKind parseKind(String type) {
        if (type == null) {
            return null;
        }
        try {
            return CommandKind.fromValue(type);
        } catch (RuntimeException e) {
            throw new CommandMutationException("Type must be prompt or script");
        }
    }

    private static Integer parseTimeout(String timeout) {
        if (timeout == null || timeout.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(timeout.trim());
        } catch (NumberFormatException e) {
            throw new CommandMutationException("Timeout must be a whole number of seconds");
        }
    }

    private static String safeCommandError(Exception e) {
        return e.getMessage() == null ? "Command change could not be saved." : e.getMessage();
    }

    @GetMapping("/ui/settings/usage")
    public String settingsUsage(@RequestParam(name = "range", defaultValue = "24h") String range, Model model) {
        AppStateView view = appStateService.loadViewData();
        if (view.activeProject() == null) {
            return "fragments/projects :: usageEmpty";
        }
        long hours = switch (range) {
            case "7d" -> 24L * 7;
            case "30d" -> 24L * 30;
            case "60d" -> 24L * 60;
            default -> 24;
        };
        Instant to = Instant.now().truncatedTo(ChronoUnit.HOURS).plus(1, ChronoUnit.HOURS);
        Instant from = to.minus(hours, ChronoUnit.HOURS);
        List<UsagePoint> points = tokenUsageService.findProjectHourlyUsage(view.activeProject().id(), from, to).stream()
                .map(row -> new UsagePoint(row.hourStartUtc().toString(), resolveModelLabel(row.modelKey()),
                        row.modelKey(), row.requestCount(), row.inputTokenCount(), row.outputTokenCount(),
                        row.totalTokenCount()))
                .toList();
        model.addAttribute("usageRange",
                "7d".equals(range) || "30d".equals(range) || "60d".equals(range) ? range : "24h");
        try {
            model.addAttribute("usageJson", SseJson.writeValueAsString(points));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize usage data", e);
        }
        return "fragments/projects :: settingsUsage";
    }

    @PostMapping("/ui/settings/hooks/apply")
    public String applyLifecycleHookSettings(
            @RequestParam(name = "assistantCompletedScript", required = false) String assistantCompletedScript,
            @RequestParam(name = "assistantErroredScript", required = false) String assistantErroredScript,
            @RequestParam(name = "subagentCompletedScript", required = false) String subagentCompletedScript,
            @RequestParam("timeoutSeconds") int timeoutSeconds, Model model) {
        appStateService.updateLifecycleHookSettings(new LifecycleHookSettings(assistantCompletedScript,
                assistantErroredScript, subagentCompletedScript, timeoutSeconds));
        return "fragments/projects :: modalClose";
    }

    @PostMapping("/ui/settings/auto-git-update/apply")
    public String applyAutoGitUpdateSettings(@RequestParam(name = "enabled", defaultValue = "false") boolean enabled) {
        appStateService.updateAutoGitUpdateEnabled(enabled);
        return "fragments/projects :: modalClose";
    }

    @PostMapping("/ui/settings/apply")
    public String applySettings(@RequestParam("workspaceInitCommands") String workspaceInitCommands,
            @RequestParam(name = "commandEnvironmentAllowlist", required = false) String commandEnvironmentAllowlist,
            @RequestParam(name = "environmentVariableNames", required = false) List<String> environmentVariableNames,
            @RequestParam(name = "environmentVariableValues", required = false) List<String> environmentVariableValues,
            Model model) {
        return applySettingsInternal(workspaceInitCommands, commandEnvironmentAllowlist, environmentVariableNames,
                environmentVariableValues, model);
    }

    @PostMapping("/ui/settings/mcp/apply")
    public String applyMcpSettings(@RequestParam("mcpCatalogJson") String mcpCatalogJson, Model model) {
        return applyMcpSettingsInternal(mcpCatalogJson, model);
    }

    String applySettingsInternal(String workspaceInitCommands, String commandEnvironmentAllowlist,
            List<String> environmentVariableNames, List<String> environmentVariableValues, Model model) {
        AppStateView view = appStateService.loadViewData();
        if (view.activeProject() == null) {
            return "fragments/projects :: modalClose";
        }

        List<ProjectEnvironmentVariable> environmentVariables = new ArrayList<>();
        int count = Math.max(environmentVariableNames == null ? 0 : environmentVariableNames.size(),
                environmentVariableValues == null ? 0 : environmentVariableValues.size());
        for (int i = 0; i < count; i++) {
            String name = environmentVariableNames != null && i < environmentVariableNames.size()
                    ? environmentVariableNames.get(i)
                    : null;
            String value = environmentVariableValues != null && i < environmentVariableValues.size()
                    ? environmentVariableValues.get(i)
                    : null;
            environmentVariables.add(new ProjectEnvironmentVariable(name, value));
        }

        appStateService.updateProjectSettings(view.activeProject().id(), workspaceInitCommands, environmentVariables,
                commandEnvironmentAllowlist);
        reloadMcpRuntimeForProject(view.activeProject().id());
        return "fragments/projects :: modalClose";
    }

    String applyMcpSettingsInternal(String mcpCatalogJson, Model model) {
        AppStateView view = appStateService.loadViewData();
        if (view.activeProject() == null) {
            return "fragments/projects :: modalClose";
        }

        McpCatalogPayload payload;
        try {
            payload = SseJson.readValue(mcpCatalogJson, McpCatalogPayload.class);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid MCP catalog payload", e);
        }

        List<McpServerView> currentServers = appStateService.listMcpServers();
        Map<Long, McpServerView> currentById = new LinkedHashMap<>();
        for (McpServerView server : currentServers) {
            currentById.put(server.id(), server);
        }

        Set<Long> affectedProjects = new LinkedHashSet<>();
        Set<Long> payloadServerIds = payload.serverIds();
        List<McpServerPayload> servers = payload.servers() == null ? List.of() : payload.servers();
        for (McpServerPayload server : servers) {
            List<McpServerHeader> headers = server.headers() == null
                    ? List.of()
                    : server.headers().stream().map(header -> new McpServerHeader(header.name(), header.value()))
                            .toList();
            List<Long> exposedProjectIds = server.exposedProjectIds() == null
                    ? List.of()
                    : List.copyOf(server.exposedProjectIds());
            McpServerView saved;
            if (server.id() != null && currentById.containsKey(server.id())) {
                McpServerView current = currentById.get(server.id());
                affectedProjects.addAll(current.exposedProjectIds());
                saved = appStateService.updateMcpServer(server.id(), server.name(), server.url(), server.enabled(),
                        headers, exposedProjectIds);
            } else {
                saved = appStateService.createMcpServer(server.name(), server.url(), server.enabled(), headers,
                        exposedProjectIds);
            }
            if (server.disabledToolNames() != null) {
                appStateService.updateMcpServerDisabledTools(saved.id(), server.disabledToolNames());
            }
            affectedProjects.addAll(saved.exposedProjectIds());
        }

        for (McpServerView server : currentServers) {
            if (payloadServerIds.contains(server.id())) {
                continue;
            }
            affectedProjects.addAll(server.exposedProjectIds());
            appStateService.deleteMcpServer(server.id());
        }

        affectedProjects.forEach(this::reloadMcpRuntimeForProject);
        return "fragments/projects :: modalClose";
    }

    private void reloadMcpRuntimeForProject(long projectId) {
        if (mcpRuntimeManager != null) {
            mcpRuntimeManager.reloadProject(projectId);
        }
    }

    @PostMapping("/ui/settings/openai/start")
    public String startOpenAiOAuth(Model model) {
        var view = openAiOAuthService.startDeviceAuthorization();
        populateSettingsModel(model);
        model.addAttribute("openAiOAuthView", view);
        return "fragments/projects :: openaiOAuthResponse";
    }

    @PostMapping("/ui/settings/openai/logout")
    public String logoutOpenAiOAuth(Model model) {
        var disconnected = openAiOAuthService.resetConnectionState();
        populateSettingsModel(model);
        model.addAttribute("openAiOAuthView", disconnected);
        populateChatControlsOob(model);
        return "fragments/projects :: openaiOAuthResponse";
    }

    @GetMapping("/ui/settings/openai/status")
    public String openAiOAuthStatus(Model model) {
        model.addAttribute("openAiOAuthView", openAiOAuthService.pollCurrentDeviceAuthorization());
        populateSettingsModel(model);
        populateChatControlsOob(model);
        return "fragments/projects :: openaiOAuthResponse";
    }

    @PostMapping("/ui/workspaces/active/git/pull")
    public String pullActiveWorkspace(Model model) {
        AppStateView view = appStateService.loadViewData();
        if (view.activeWorkspace() == null) {
            systemBalloonService.publishWarning("Git Pull", "No active workspace is selected.");
            addGitPullModel(model, null);
        } else {
            ManualGitPullCoordinator.DispatchResult result = manualGitPullCoordinator()
                    .dispatch(view.activeWorkspace().id());
            addGitPullModel(model, view.activeWorkspace(), result != ManualGitPullCoordinator.DispatchResult.FAILED);
        }
        return "fragments/projects :: gitPullControl";
    }

    @GetMapping("/ui/workspaces/{workspaceId}/git/pull/status")
    public String gitPullStatus(@PathVariable long workspaceId, Model model) {
        AppStateView view = appStateService.loadViewData();
        WorkspaceView workspace = view.activeWorkspace() != null && view.activeWorkspace().id() == workspaceId
                ? view.activeWorkspace()
                : null;
        addGitPullModel(model, workspace, workspace != null && manualGitPullCoordinator().isPulling(workspace.id()));
        return "fragments/projects :: gitPullControl";
    }

    private void addGitPullModel(Model model, WorkspaceView workspace) {
        addGitPullModel(model, workspace, workspace != null && manualGitPullCoordinator().isPulling(workspace.id()));
    }

    private void addGitPullModel(Model model, WorkspaceView workspace, boolean busy) {
        model.addAttribute("workspaceId", workspace == null ? null : workspace.id());
        model.addAttribute("gitPullBusy", workspace != null && busy);
        model.addAttribute("hasWorkspace", workspace != null);
    }

    @PostMapping("/ui/projects/add")
    public String addProject(@RequestParam("name") String name, @RequestParam("path") String path, Model model) {
        appStateService.addOrReopenProject(name, Path.of(path).toAbsolutePath().normalize().toString());
        AppStateView view = appStateService.loadViewData();
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        populateShellUpdates(model, view);
        return "fragments/projects :: shellUpdates";
    }

    @PostMapping("/ui/workspaces/add")
    public String addWorkspace(@RequestParam("branchName") String branchName,
            @RequestParam(name = "branchMode", defaultValue = "create") String branchMode, Model model) {
        AppStateView view = appStateService.loadViewData();
        String trimmedBranchName = branchName.trim();
        BranchMode parsedBranchMode = BranchMode.fromValue(branchMode);
        boolean createBranch = parsedBranchMode == BranchMode.CREATE;

        try {
            appStateService.createWorkspace(view.activeProject().id(), trimmedBranchName, createBranch);
        } catch (InvalidGitBranchNameException e) {
            if (createBranch) {
                String gitOutput = e.gitOutput();
                String body = e.getMessage();
                if (gitOutput != null && !gitOutput.isBlank()) {
                    body += "\n\n" + gitOutput;
                }
                systemBalloonService.publishError("Invalid Branch Name", body);
                populateProjectModel(model, view);
                populateSessionModel(model, view);
                model.addAttribute("branchName", trimmedBranchName);
                model.addAttribute("branchMode", parsedBranchMode.value());
                model.addAttribute("createBranch", true);
                model.addAttribute("modalOob", true);
                return "fragments/projects :: workspaceModal";
            }
            throw e;
        } catch (GitWorktreeException e) {
            if (!createBranch) {
                String gitOutput = e.lastGitOutputLines();
                if (gitOutput == null || gitOutput.isBlank()) {
                    gitOutput = e.getMessage();
                }
                String body = "Could not check out existing Git branch \"" + trimmedBranchName + "\".\n\n" + gitOutput;
                systemBalloonService.publishError("Checkout Failed", body);
                populateProjectModel(model, view);
                populateSessionModel(model, view);
                model.addAttribute("branchName", trimmedBranchName);
                model.addAttribute("branchMode", parsedBranchMode.value());
                model.addAttribute("createBranch", false);
                model.addAttribute("modalOob", true);
                return "fragments/projects :: workspaceModal";
            }
            throw e;
        }

        view = appStateService.loadViewData();
        String workspaceInitCommands = view.activeProject().workspaceInitCommands();
        if (workspaceInitCommands != null && !workspaceInitCommands.isBlank()) {
            TerminalHandle terminal = terminalManager.createTerminal(view.activeWorkspace().path(), "Workspace Init",
                    activeProjectEnvironmentVariables(view));
            terminalStateService.registerTerminal(view.activeWorkspace().id(), terminal);
            terminalStateService.openTerminalPane(view.activeWorkspace().id());
            terminalManager.write(terminal.id(),
                    workspaceInitCommands.endsWith("\n") ? workspaceInitCommands : workspaceInitCommands + "\n");
        }
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        populateShellUpdates(model, view);
        return "fragments/projects :: shellUpdates";
    }

    @PostMapping("/ui/projects/{projectId}/activate")
    public String activateProject(@PathVariable long projectId, Model model) {
        appStateService.activateProject(projectId);
        AppStateView view = appStateService.loadViewData();
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        populateShellUpdates(model, view);
        return "fragments/projects :: shellUpdates";
    }

    @PostMapping("/ui/projects/{projectId}/close")
    public String closeProject(@PathVariable long projectId, Model model) {
        appStateService.closeProject(projectId);
        AppStateView view = appStateService.loadViewData();
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        populateShellUpdates(model, view);
        return "fragments/projects :: shellUpdates";
    }

    @PostMapping("/ui/workspaces/{workspaceId}/activate")
    public String activateWorkspace(@PathVariable long workspaceId, Model model) {
        appStateService.activateWorkspace(workspaceId);
        AppStateView view = appStateService.loadViewData();
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        populateShellUpdates(model, view);
        return "fragments/projects :: shellUpdates";
    }

    @PostMapping("/ui/workspaces/{workspaceId}/collapse")
    public String collapseWorkspace(@PathVariable long workspaceId, Model model) {
        appStateService.collapseWorkspace(workspaceId);
        AppStateView view = appStateService.loadViewData();
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        populateShellUpdates(model, view);
        return "fragments/projects :: shellUpdates";
    }

    @PostMapping("/ui/workspaces/{workspaceId}/close")
    public String closeWorkspace(@PathVariable long workspaceId, Model model) {
        AppStateView view = appStateService.loadViewData();
        AppStateService.WorkspaceCloseInspection inspection = appStateService.inspectWorkspaceClose(workspaceId);
        if (inspection.uncommittedChanges() || inspection.unpushedCommits()) {
            populateProjectModel(model, view);
            populateSessionModel(model, view);
            populateWorkspaceCloseModel(model, inspection);
            return "fragments/projects :: workspaceCloseModal";
        }

        appStateService.removeWorkspaceWorktree(workspaceId, false);
        appStateService.closeWorkspace(workspaceId);
        view = appStateService.loadViewData();
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        populateShellUpdates(model, view);
        return "fragments/projects :: shellUpdates";
    }

    @PostMapping("/ui/workspaces/{workspaceId}/close/confirm")
    public String confirmWorkspaceClose(@PathVariable long workspaceId, Model model) {
        appStateService.removeWorkspaceWorktree(workspaceId, true);
        appStateService.closeWorkspace(workspaceId);
        AppStateView view = appStateService.loadViewData();
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        populateShellUpdates(model, view);
        return "fragments/projects :: shellUpdates";
    }

    @PostMapping("/ui/sessions/{sessionId}/activate")
    public String activateSession(@PathVariable long sessionId, Model model) {
        appStateService.activateSession(sessionId);
        AppStateView view = appStateService.loadViewData();
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        populateShellUpdates(model, view);
        return "fragments/projects :: shellUpdates";
    }

    @PostMapping("/ui/sessions/{sessionId}/close")
    public String closeSession(@PathVariable long sessionId, Model model) {
        appStateService.closeSession(sessionId);
        AppStateView view = appStateService.loadViewData();
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        populateShellUpdates(model, view);
        return "fragments/projects :: shellUpdates";
    }

    @PostMapping("/ui/sessions/{sessionId}/draft")
    public ResponseEntity<Void> updateSessionDraft(@PathVariable long sessionId, @RequestParam("draft") String draft) {
        appStateService.updateSessionDraft(sessionId, draft);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/ui/sessions/add")
    public String addSession(@RequestParam("name") String name, Model model) {
        AppStateView view = appStateService.loadViewData();
        appStateService.createSession(view.activeWorkspace().id(), name);
        view = appStateService.loadViewData();
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        populateShellUpdates(model, view);
        return "fragments/projects :: shellUpdates";
    }

    @GetMapping("/ui/sessions/new")
    public String newSessionForm() {
        return "fragments/projects :: newSessionForm";
    }

    @GetMapping("/ui/workspaces/rail")
    public String workspaceRail(Model model) {
        AppStateView view = appStateService.loadViewData();
        populateProjectModel(model, view);
        return "fragments/projects :: workspaceRail";
    }

    @GetMapping("/ui/sessions/new/button")
    public String newSessionButton() {
        return "fragments/projects :: newSessionButton";
    }

    private void populateSessionModel(Model model, AppStateView view) {
        SessionView session = view.activeSession();
        SessionDetailView detail = view.activeSessionDetail();
        TerminalPanelState terminalState = view.activeWorkspace() == null
                ? new TerminalPanelState("none", List.of(), null, false)
                : terminalStateService.snapshot(view.activeWorkspace().id());
        if (session == null) {
            model.addAttribute("chatMessages", List.of());
            model.addAttribute("subagentView", false);
            model.addAttribute("changedFiles", List.of());
            model.addAttribute("reviewPanelOpen", false);
            model.addAttribute("reviewSource", null);
            model.addAttribute("selectedFile", null);
            model.addAttribute("hasPending", false);
            model.addAttribute("reviewOob", false);
            model.addAttribute("workspaceRoot", view.activeWorkspace() == null ? null : view.activeWorkspace().path());
            model.addAttribute("chatDraft", "");
            model.addAttribute("forkSessionId", null);
            model.addAttribute("terminalTabs", terminalState.terminalTabs());
            model.addAttribute("activeTerminal", terminalState.activeTerminal());
            model.addAttribute("bottomPanelMode", terminalState.bottomPanelMode());
            model.addAttribute("bottomPanelOpen", terminalState.bottomPanelOpen());
            model.addAttribute("terminalPanelOpen", terminalState.bottomPanelOpen());
            model.addAttribute("panelMode", terminalState.bottomPanelMode());
            return;
        }

        boolean hasPending = detail.chatMessages().stream().anyMatch(ChatMessageView::pending);
        model.addAttribute("chatMessages", detail.chatMessages().stream().map(this::toChatMessage).toList());
        model.addAttribute("subagentView", false);
        model.addAttribute("activeSession", toSession(session));
        model.addAttribute("changedFiles", detail.changedFiles().stream().map(this::toChangedFile).toList());
        model.addAttribute("reviewPanelOpen", detail.reviewPanelOpen());
        model.addAttribute("reviewSource", detail.reviewSource());
        model.addAttribute("selectedFile", toChangedFile(detail.selectedFile()));
        model.addAttribute("hasPending", hasPending);
        model.addAttribute("reviewOob", !hasPending && detail.reviewPanelOpen());
        model.addAttribute("workspaceRoot", detail.workspaceRoot());
        model.addAttribute("chatDraft", detail.chatDraft());
        model.addAttribute("forkSessionId", session.id());
        model.addAttribute("terminalTabs", terminalState.terminalTabs());
        model.addAttribute("activeTerminal", terminalState.activeTerminal());
        model.addAttribute("bottomPanelMode", terminalState.bottomPanelMode());
        model.addAttribute("bottomPanelOpen", terminalState.bottomPanelOpen());
        model.addAttribute("terminalPanelOpen", terminalState.bottomPanelOpen());
        model.addAttribute("panelMode", terminalState.bottomPanelMode());
    }

    private void populateChatModel(Model model, List<ChatMessageView> chatMessages, boolean subagentView,
            String subagentAgentName, String subagentAgentId, Long subagentSessionId) {
        model.addAttribute("chatMessages", chatMessages.stream().map(this::toChatMessage).toList());
        model.addAttribute("hasPending", chatMessages.stream().anyMatch(ChatMessageView::pending));
        model.addAttribute("reviewOob", false);
        model.addAttribute("shellRefresh", false);
        model.addAttribute("subagentView", subagentView);
        model.addAttribute("subagentAgentName", subagentAgentName);
        model.addAttribute("subagentAgentId", subagentAgentId);
        model.addAttribute("subagentSessionId", subagentSessionId);
        model.addAttribute("forkSessionId", null);
    }

    private boolean isTerminalPanelOpen(AppStateView view) {
        return view.activeWorkspace() != null
                && terminalStateService.snapshot(view.activeWorkspace().id()).bottomPanelOpen();
    }

    private void populateProjectModel(Model model, AppStateView view) {
        List<Project> projects = view.projects().stream().map(this::toProject).toList();
        model.addAttribute("projects", projects);
        model.addAttribute("visibleProjects", projects);
        model.addAttribute("mcpServers", appStateService.listMcpServers());
        model.addAttribute("activeProject", toProject(view.activeProject()));
        model.addAttribute("workspaces", view.workspaces().stream().map(this::toWorkspace).toList());
        model.addAttribute("workspaceActions", view.workspaces().stream()
                .map(workspace -> toWorkspaceAction(view.activeProject(), workspace)).toList());
        model.addAttribute("activeWorkspace", toWorkspace(view.activeWorkspace()));
        model.addAttribute("sessions", view.sessions().stream().map(this::toSession).toList());
        model.addAttribute("activeSession", toSession(view.activeSession()));
        model.addAttribute("reviewPanelOpen",
                view.activeSessionDetail() != null && view.activeSessionDetail().reviewPanelOpen());
        model.addAttribute("shellRefresh", false);
        model.addAttribute("includeChatContainer", false);
        model.addAttribute("appVersion", appVersion);
    }

    private Map<String, String> projectEnvironmentVariables(AppStateView view) {
        return appStateService.loadProjectEnvironmentVariables(view.activeProject().id());
    }

    private void populateShellUpdates(Model model, AppStateView view) {
        model.addAttribute("terminalOob", true);
        model.addAttribute("shellRefresh", true);
        model.addAttribute("includeChatContainer", true);
        model.addAttribute("reviewOob", true);
        model.addAttribute("activeSession", toSession(view.activeSession()));
        populateChatControlsModel(model, activeChatSelection(view));
    }

    private void sendInitialMcpFailureBalloons(SseEmitter emitter) {
        if (mcpRuntimeManager == null) {
            return;
        }

        AppStateView view = appStateService.loadViewData();
        ProjectView project = view.activeProject();
        if (project == null) {
            return;
        }

        Map<Long, McpRuntimeEvents.ConnectionStatus> statuses = mcpRuntimeManager.connectionStatuses(project.id());
        if (statuses.isEmpty()) {
            return;
        }

        Map<Long, McpServerView> serversById = new LinkedHashMap<>();
        for (McpServerView server : appStateService.loadEnabledMcpServersForProject(project.id())) {
            serversById.put(server.id(), server);
        }

        for (Map.Entry<Long, McpRuntimeEvents.ConnectionStatus> entry : statuses.entrySet()) {
            if (entry.getValue() != McpRuntimeEvents.ConnectionStatus.FAILED) {
                continue;
            }

            McpServerView server = serversById.get(entry.getKey());
            if (server == null) {
                continue;
            }

            systemBalloonService.publishWarning(emitter, "MCP server failed: " + project.name() + " / " + server.name(),
                    "An MCP server is unavailable for the active project.");
        }
    }

    private ChatSelection activeChatSelection(AppStateView view) {
        if (view != null && view.activeSessionDetail() != null) {
            ChatSelection selection = latestAssistantChatSelection(view.activeSessionDetail());
            if (selection != null) {
                return selection;
            }
        }
        return defaultChatSelection();
    }

    private ChatSelection latestAssistantChatSelection(SessionDetailView detail) {
        if (detail == null) {
            return null;
        }
        ChatMessageView newestAssistant = detail.chatMessages().stream()
                .filter(message -> "assistant".equals(message.role())).reduce((first, second) -> second).orElse(null);
        if (newestAssistant == null || newestAssistant.metadata() == null) {
            return null;
        }
        ChatMessageMetadata metadata = newestAssistant.metadata();
        AgentDefinition selectedAgent = agentDefinitionService.resolveOrDefault(metadata.agentId());
        AgentPreferenceResolver.Resolution selectedDefaults = resolveAgentPreferenceForDisplay(selectedAgent);
        ModelDefinition selectedModel = selectedDefaults.model();
        boolean explicitModel = false;
        if (metadata.modelId() != null && !metadata.modelId().isBlank()) {
            try {
                selectedModel = modelCatalogService.getRequired(metadata.modelId());
                explicitModel = true;
            } catch (IllegalArgumentException e) {
                log.warn("Ignoring unknown persisted model '{}' while restoring session", metadata.modelId());
            }
        }
        ThinkingLevel selectedThinking = selectedDefaults.thinkingLevel();
        if (metadata.thinkingLevel() != null && !metadata.thinkingLevel().isBlank()) {
            try {
                selectedThinking = ThinkingLevel.fromValue(metadata.thinkingLevel());
            } catch (IllegalArgumentException e) {
                log.warn("Ignoring invalid persisted thinking level '{}' while restoring session",
                        metadata.thinkingLevel());
            }
        }
        AgentDefinition defaultAgent = agentDefinitionService.defaultAgent();
        AgentPreferenceResolver.Resolution defaultResolved = resolveAgentPreferenceForDisplay(defaultAgent);
        return new ChatSelection(selectedAgent, selectedModel, selectedThinking, explicitModel, defaultAgent,
                defaultResolved.model(), defaultResolved.thinkingLevel());
    }

    private void populateChatControlsModel(Model model, ChatSelection selection) {
        List<AgentDefinition> agents = agentDefinitionService.listPrimaryAgents();
        model.addAttribute("agents", agents);
        List<ModelDefinition> pickerModels = modelPickerService == null
                ? modelCatalogService.list()
                : modelPickerService.listPickerModels();
        Map<String, String> agentDefaultModels = new LinkedHashMap<>();
        Map<String, String> agentDefaultThinking = new LinkedHashMap<>();
        Map<String, Boolean> unavailableModels = new LinkedHashMap<>();
        Map<String, ModelDefinition> renderedModels = new LinkedHashMap<>();
        agents.forEach(agent -> {
            AgentPreferenceResolver.Resolution resolved = resolveAgentPreferenceForDisplay(agent);
            if (resolved.model() != null) {
                agentDefaultModels.put(agent.id(), resolved.model().id());
                renderedModels.put(resolved.model().id(), resolved.model());
                unavailableModels.put(resolved.model().id(),
                        !providerAvailabilityService.isAvailable(resolved.model().provider()));
            }
            if (resolved.thinkingLevel() != null) {
                agentDefaultThinking.put(agent.id(), resolved.thinkingLevel().name());
            }
        });
        Map<String, ModelDefinition> uniqueRenderedModels = new LinkedHashMap<>();
        pickerModels.forEach(modelDef -> uniqueRenderedModels.put(modelDef.id(), modelDef));
        renderedModels.forEach(uniqueRenderedModels::putIfAbsent);
        ModelDefinition renderedModel = selection.selectedModel();
        boolean explicitModel = selection.explicitModel();
        if (renderedModel != null) {
            // A catalog-known session model may be disconnected or no longer configured in
            // the picker, but it is still the user's remembered selection.
            uniqueRenderedModels.putIfAbsent(renderedModel.id(), renderedModel);
            unavailableModels.putIfAbsent(renderedModel.id(),
                    !providerAvailabilityService.isAvailable(renderedModel.provider()));
        }
        List<ModelDefinition> renderedPickerModels = new ArrayList<>(uniqueRenderedModels.values());
        model.addAttribute("agentDefaultModels", agentDefaultModels);
        model.addAttribute("agentDefaultThinking", agentDefaultThinking);
        model.addAttribute("models", renderedPickerModels);
        model.addAttribute("unavailableModels", unavailableModels);
        model.addAttribute("pickerEmpty", renderedPickerModels.isEmpty());
        model.addAttribute("thinkingLevels", List.of(ThinkingLevel.values()));
        model.addAttribute("defaultAgent", selection.defaultAgent());
        model.addAttribute("defaultModel", selection.defaultModel());
        model.addAttribute("defaultThinking", selection.defaultThinking());
        model.addAttribute("selectedAgent", selection.selectedAgent());
        model.addAttribute("selectedModel", renderedModel);
        model.addAttribute("selectedThinking", selection.selectedThinking());
        model.addAttribute("selectedModelExplicit", explicitModel);
    }

    private ChatSelection defaultChatSelection() {
        AgentDefinition defaultAgent = agentDefinitionService.defaultAgent();
        AgentPreferenceResolver.Resolution resolved = resolveAgentPreferenceForDisplay(defaultAgent);
        return new ChatSelection(defaultAgent, resolved.model(), resolved.thinkingLevel(), false, defaultAgent,
                resolved.model(), resolved.thinkingLevel());
    }

    private AgentPreferenceResolver.Resolution resolveAgentPreference(AgentDefinition agent) {
        return agentPreferenceResolver.resolve(agent, null, null, false);
    }

    private AgentPreferenceResolver.Resolution resolveAgentPreferenceForDisplay(AgentDefinition agent) {
        return agentPreferenceResolver.resolveForDisplay(agent);
    }

    private ChatSelection resolveChatSelection(String agentId, String modelId, String thinkingLevel) {
        AgentDefinition defaultAgent = agentDefinitionService.defaultAgent();
        AgentDefinition selectedAgent = agentId == null || agentId.isBlank()
                ? defaultAgent
                : agentDefinitionService.getRequired(agentId);
        AgentPreferenceResolver.Resolution resolved = resolveAgentPreferenceForDisplay(selectedAgent);
        ModelDefinition selectedModel = modelId == null || modelId.isBlank()
                ? resolved.model()
                : modelCatalogService.getRequired(modelId);
        ThinkingLevel selectedThinking = thinkingLevel == null || thinkingLevel.isBlank()
                ? resolved.thinkingLevel()
                : ThinkingLevel.fromValue(thinkingLevel);
        ModelDefinition defaultModel = resolveAgentPreferenceForDisplay(defaultAgent).model();
        return new ChatSelection(selectedAgent, selectedModel, selectedThinking, modelId != null && !modelId.isBlank(),
                defaultAgent, defaultModel, resolveAgentPreferenceForDisplay(defaultAgent).thinkingLevel());
    }

    private void populateWorkspaceCloseModel(Model model, AppStateService.WorkspaceCloseInspection inspection) {
        model.addAttribute("workspace", new Workspace(inspection.workspaceId(), inspection.workspaceName(),
                inspection.workspacePath(), false, RailStatus.NONE));
        model.addAttribute("workspaceId", inspection.workspaceId());
        model.addAttribute("workspaceName", inspection.workspaceName());
        model.addAttribute("workspacePath", inspection.workspacePath());
        model.addAttribute("workspaceCloseStatus", inspection);
        model.addAttribute("workspaceCloseReasons", inspection.reasons());
        model.addAttribute("modalTarget", "#modal-root");
    }

    private AppStateView currentViewWithSessionIfNeeded(boolean createIfMissing) {
        AppStateView view = appStateService.loadViewData();
        if (createIfMissing && view.activeSession() == null) {
            appStateService.ensureChatSession(agentProperties.getWorkspaceRoot());
            view = appStateService.loadViewData();
        }
        return view;
    }

    private List<DirectoryEntry> listDirectoryEntries(Path path) {
        try (var stream = Files.list(path)) {
            return stream.filter(Files::isDirectory)
                    .map(entry -> new DirectoryEntry(entry.getFileName().toString(), entry.toString(),
                            Files.isDirectory(entry)))
                    .sorted(Comparator.comparing((DirectoryEntry entry) -> entry.name().startsWith("."))
                            .thenComparing(DirectoryEntry::name, String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(DirectoryEntry::name))
                    .toList();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    String resolveModelLabel(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return null;
        }
        try {
            return modelCatalogService.getRequired(modelId).displayName();
        } catch (RuntimeException ignored) {
            return modelId;
        }
    }

    private ChatMessage toChatMessage(ChatMessageView view) {
        return chatPresentationService.toChatMessage(view, this::resolveModelLabel);
    }

    public record UsagePoint(String hour, String modelLabel, String modelKey, long requests, Long input, Long output,
            Long total) {
    }

    private ChangedFile toChangedFile(ChangedFileView view) {
        return view == null ? null : new ChangedFile(view.key(), view.source(), view.id(), view.path(), view.diff());
    }

    private Project toProject(ProjectView view) {
        return view == null
                ? null
                : new Project(view.id(), view.name(), view.path(), view.workspaceInitCommands(),
                        view.environmentVariables(), view.commandEnvironmentAllowlist());
    }

    private Map<String, String> activeProjectEnvironmentVariables(AppStateView view) {
        return appStateService.loadProjectEnvironmentVariables(view.activeProject().id());
    }

    private Workspace toWorkspace(WorkspaceView view) {
        return view == null
                ? null
                : new Workspace(view.id(), view.name(), view.path(), view.unread(), view.railStatus());
    }

    private WorkspaceAction toWorkspaceAction(ProjectView activeProject, WorkspaceView workspace) {
        boolean defaultWorkspace = activeProject != null && workspace.path().equals(activeProject.path());
        return new WorkspaceAction(workspace.id(), defaultWorkspace, !defaultWorkspace);
    }

    private Session toSession(SessionView view) {
        return view == null ? null : new Session(view.id(), view.name(), view.unread(), view.railStatus());
    }

    private String directoryDisplayName(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? path.toString() : fileName.toString();
    }

    private record McpCatalogPayload(List<McpServerPayload> servers) {
        private Set<Long> serverIds() {
            if (servers == null) {
                return Set.of();
            }
            Set<Long> ids = new LinkedHashSet<>();
            for (McpServerPayload server : servers) {
                if (server.id() != null) {
                    ids.add(server.id());
                }
            }
            return ids;
        }
    }

    private record McpServerPayload(Long id, String name, String url, boolean enabled,
            List<McpServerHeaderPayload> headers, List<Long> exposedProjectIds, Set<String> disabledToolNames) {
    }

    private record McpServerHeaderPayload(String name, String value) {
    }

    private enum BranchMode {
        CREATE("create"), CHECKOUT("checkout");

        private final String value;

        BranchMode(String value) {
            this.value = value;
        }

        String value() {
            return value;
        }

        static BranchMode fromValue(String value) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "create" -> CREATE;
                case "checkout" -> CHECKOUT;
                default -> throw new IllegalArgumentException("Invalid branch mode: " + value);
            };
        }
    }

    public record ChangedFile(String key, ReviewSource source, Integer id, String path, String diff) {
    }

    public record Project(long id, String name, String path, String workspaceInitCommands,
            List<ProjectEnvironmentVariable> environmentVariables, String commandEnvironmentAllowlist) {
    }

    public record Workspace(long id, String name, String path, boolean unread, RailStatus railStatus) {

        public boolean inProgress() {
            return railStatus == RailStatus.IN_PROGRESS;
        }

        public boolean failed() {
            return railStatus == RailStatus.FAILED;
        }
    }

    public record WorkspaceAction(long id, boolean defaultWorkspace, boolean deletable) {
    }

    public record Session(long id, String name, boolean unread, RailStatus railStatus) {

        public boolean inProgress() {
            return railStatus == RailStatus.IN_PROGRESS;
        }

        public boolean failed() {
            return railStatus == RailStatus.FAILED;
        }
    }

    public record DirectoryEntry(String name, String path, boolean directory) {
    }

    private record ChatSelection(AgentDefinition selectedAgent, ModelDefinition selectedModel,
            ThinkingLevel selectedThinking, boolean explicitModel, AgentDefinition defaultAgent,
            ModelDefinition defaultModel, ThinkingLevel defaultThinking) {
    }

}

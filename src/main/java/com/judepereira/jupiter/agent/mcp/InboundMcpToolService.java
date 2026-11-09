package com.judepereira.jupiter.agent.mcp;

import com.judepereira.jupiter.agent.catalog.AgentDefinitionService;
import com.judepereira.jupiter.agent.catalog.AgentPreferenceResolver;
import com.judepereira.jupiter.git.GitCommandRunner;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.ContextCompactionService;
import com.judepereira.jupiter.persistence.Persistence;
import com.judepereira.jupiter.ui.ChatStreamRuntime;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class InboundMcpToolService {
    private final AppStateService appState;
    private final AgentDefinitionService agents;
    private final AgentPreferenceResolver preferences;
    private final ContextCompactionService compaction;
    private final ChatStreamRuntime chatRuntime;
    private final GitCommandRunner git;

    public InboundMcpToolService(AppStateService appState, AgentDefinitionService agents,
            AgentPreferenceResolver preferences, ContextCompactionService compaction, ChatStreamRuntime chatRuntime,
            GitCommandRunner git) {
        this.appState = appState;
        this.agents = agents;
        this.preferences = preferences;
        this.compaction = compaction;
        this.chatRuntime = chatRuntime;
        this.git = git;
    }

    public Object call(String name, Map<String, Object> args, InboundMcpTokenService.Credential credential) {
        if (credential == null || !InboundMcpTokenService.TOOLS.contains(name) || !credential.tools().contains(name)) {
            throw denied();
        }
        if (args == null) {
            args = Map.of();
        }
        return switch (name) {
            case "list_projects" -> {
                keys(args);
                yield appState.loadViewData().projects().stream().filter(p -> allowed(p.id(), credential))
                        .map(p -> Map.of("id", p.id(), "name", p.name())).toList();
            }
            case "list_workspaces" -> {
                keys(args, "projectId");
                long project = positive(args, "projectId");
                requireProject(project, credential);
                yield appState.listWorkspaces(project).stream().map(w -> {
                    String branch = branchName(w.path());
                    return Map.of("id", w.id(), "name", w.name(), "branchName", branch, "branchDetached",
                            branch.isEmpty(), "projectId", project);
                }).toList();
            }
            case "list_sessions" -> listSessions(args, credential);
            case "list_agents" -> {
                keys(args, "projectId");
                requireProject(positive(args, "projectId"), credential);
                yield agents.listPrimaryAgents().stream().map(a -> Map.of("id", a.id(), "name", a.name(), "description",
                        a.description() == null ? "" : a.description())).toList();
            }
            case "create_workspace" -> createWorkspace(args, credential);
            case "post_message" -> postMessage(args, credential);
            case "summarize_session" -> summarize(args, credential);
            default -> throw new IllegalArgumentException("Unknown tool");
        };
    }

    private Object listSessions(Map<String, Object> args, InboundMcpTokenService.Credential c) {
        keys(args, "workspaceId", "projectId");
        Long project = args.containsKey("projectId") ? positive(args, "projectId") : null;
        if (project != null) {
            requireProject(project, c);
        }
        List<Persistence.WorkspaceView> workspaces = permittedProjects(c).stream()
                .filter(p -> project == null || p.id() == project)
                .flatMap(p -> appState.listWorkspaces(p.id()).stream())
                .filter(w -> !args.containsKey("workspaceId") || w.id() == positive(args, "workspaceId")).toList();
        if (args.containsKey("workspaceId") && workspaces.isEmpty()) {
            throw denied();
        }
        return workspaces.stream()
                .flatMap(w -> appState.listPrimarySessions(w.id()).stream()
                        .map(s -> Map.of("id", s.id(), "name", s.name(), "workspaceId", w.id(), "busy", s.inProgress(),
                                "projectId", projectForWorkspace(c, w.id()))))
                .toList();
    }

    private Object createWorkspace(Map<String, Object> args, InboundMcpTokenService.Credential c) {
        keys(args, "projectId", "branchName", "createBranch");
        long project = positive(args, "projectId");
        requireProject(project, c);
        if (!(args.get("branchName") instanceof String branch) || branch.isBlank()
                || !(args.get("createBranch") instanceof Boolean createBranch)) {
            throw new IllegalArgumentException("Invalid arguments");
        }
        var workspace = appState.createWorkspace(project, branch, createBranch);
        long sessionId = appState.listPrimarySessions(workspace.id()).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Initial session was not created")).id();
        return Map.of("workspaceId", workspace.id(), "sessionId", sessionId, "name", workspace.name());
    }

    private Object postMessage(Map<String, Object> args, InboundMcpTokenService.Credential c) {
        keys(args, "sessionId", "workspaceId", "projectId", "branchName", "createBranch", "message", "sessionName",
                "agentId", "modelId", "thinkingLevel");
        if (!(args.get("message") instanceof String message) || message.isBlank()) {
            throw new IllegalArgumentException("Message is required");
        }
        String sessionName = stringOrNull(args, "sessionName");
        String agentId = stringOrNull(args, "agentId");
        String modelId = stringOrNull(args, "modelId");
        String thinkingLevel = stringOrNull(args, "thinkingLevel");
        boolean session = args.containsKey("sessionId"), workspace = args.containsKey("workspaceId");
        boolean create = args.containsKey("projectId") || args.containsKey("branchName")
                || args.containsKey("createBranch");
        if ((session ? 1 : 0) + (workspace ? 1 : 0) + (create ? 1 : 0) != 1) {
            throw new IllegalArgumentException("Provide exactly one target selector");
        }
        if (session && sessionName != null) {
            throw new IllegalArgumentException("sessionName is only valid when targeting a workspace or creating one");
        }
        long sessionId;
        long workspaceId;
        long projectId;
        if (session) {
            sessionId = positive(args, "sessionId");
            requireSession(sessionId, c);
            var owner = permittedProjects(c).stream().flatMap(p -> appState.listWorkspaces(p.id()).stream())
                    .filter(w -> appState.listPrimarySessions(w.id()).stream().anyMatch(s -> s.id() == sessionId))
                    .findFirst().orElseThrow(InboundMcpToolService::denied);
            workspaceId = owner.id();
            projectId = projectForWorkspace(c, workspaceId);
        } else if (workspace) {
            long wid = positive(args, "workspaceId");
            var found = permittedWorkspace(wid, c);
            workspaceId = found.id();
            projectId = projectForWorkspace(c, workspaceId);
            sessionId = sessionName == null || sessionName.isBlank()
                    ? appState.createSession(workspaceId).id()
                    : appState.createSession(workspaceId, sessionName).id();
        } else {
            if (!c.tools().contains("create_workspace")) {
                throw denied();
            }
            long project = positive(args, "projectId");
            requireProject(project, c);
            if (!(args.get("branchName") instanceof String branch) || branch.isBlank()
                    || !(args.get("createBranch") instanceof Boolean createBranch)) {
                throw new IllegalArgumentException("Invalid arguments");
            }
            var made = appState.createWorkspace(project, branch, createBranch);
            workspaceId = made.id();
            projectId = project;
            sessionId = appState.listPrimarySessions(workspaceId).stream().findFirst()
                    .orElseThrow(() -> new IllegalStateException("Initial session was not created")).id();
            if (sessionName != null && !sessionName.isBlank()) {
                throw new IllegalArgumentException("sessionName is not supported when creating a workspace");
            }
        }
        var result = chatRuntime.submit(sessionId, message, agentId, modelId, thinkingLevel);
        return Map.of("accepted", true, "status", "accepted", "sessionId", sessionId, "workspaceId", workspaceId,
                "projectId", projectId, "assistantId", result.assistantId());
    }

    private Object summarize(Map<String, Object> args, InboundMcpTokenService.Credential c) {
        keys(args, "sessionId");
        long sid = positive(args, "sessionId");
        requireSession(sid, c);
        var agent = agents.defaultAgent();
        var resolution = preferences.resolve(agent, null, null, false);
        var result = compaction.summarizeSession(sid, agent, resolution.model(), resolution.thinkingLevel());
        return Map.of("summary", result.summary(), "cutoffTurnId",
                result.cutoffTurnId() == null ? 0 : result.cutoffTurnId());
    }

    private String branchName(String path) {
        var result = git.run(Path.of(path), List.of("git", "symbolic-ref", "--quiet", "--short", "HEAD"),
                Duration.ofSeconds(5));
        if (!result.succeeded() || result.stdout().isBlank()) {
            return "";
        }
        return result.stdout().trim();
    }

    private Persistence.WorkspaceView permittedWorkspace(long id, InboundMcpTokenService.Credential c) {
        return permittedProjects(c).stream().flatMap(p -> appState.listWorkspaces(p.id()).stream())
                .filter(w -> w.id() == id).findFirst().orElseThrow(InboundMcpToolService::denied);
    }
    private void requireSession(long id, InboundMcpTokenService.Credential c) {
        boolean exists = permittedProjects(c).stream().flatMap(p -> appState.listWorkspaces(p.id()).stream())
                .flatMap(w -> appState.listPrimarySessions(w.id()).stream()).anyMatch(s -> s.id() == id);
        if (!exists) {
            throw denied();
        }
    }
    private long projectForWorkspace(InboundMcpTokenService.Credential c, long workspaceId) {
        return permittedProjects(c).stream()
                .filter(p -> appState.listWorkspaces(p.id()).stream().anyMatch(w -> w.id() == workspaceId))
                .mapToLong(Persistence.ProjectView::id).findFirst().orElseThrow(InboundMcpToolService::denied);
    }
    private List<Persistence.ProjectView> permittedProjects(InboundMcpTokenService.Credential c) {
        return appState.loadViewData().projects().stream().filter(p -> allowed(p.id(), c)).toList();
    }
    private static boolean allowed(long id, InboundMcpTokenService.Credential c) {
        return c.allProjects() || c.projectIds().contains(id);
    }
    private void requireProject(long id, InboundMcpTokenService.Credential c) {
        if (permittedProjects(c).stream().noneMatch(p -> p.id() == id)) {
            throw denied();
        }
    }
    private static String stringOrNull(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException("Invalid " + key);
        }
        return s;
    }
    private static void keys(Map<String, Object> args, String... allowed) {
        if (!Set.of(allowed).containsAll(args.keySet())) {
            throw new IllegalArgumentException("Unexpected argument");
        }
    }
    private static long positive(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof Number n) || n.longValue() <= 0 || n.doubleValue() != n.longValue()) {
            throw new IllegalArgumentException("Invalid " + key);
        }
        return n.longValue();
    }
    private static IllegalArgumentException denied() {
        return new IllegalArgumentException("Resource unavailable");
    }
}

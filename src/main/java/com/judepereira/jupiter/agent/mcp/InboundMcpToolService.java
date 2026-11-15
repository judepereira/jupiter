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
                yield appState.listVisibleProjects().stream().filter(p -> allowed(p.id(), credential))
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
        List<WorkspaceOwner> workspaces = authorizedOwners(c, project).stream().filter(
                owner -> !args.containsKey("workspaceId") || owner.workspace().id() == positive(args, "workspaceId"))
                .toList();
        if (args.containsKey("workspaceId") && workspaces.isEmpty()) {
            throw denied();
        }
        return workspaces.stream()
                .flatMap(owner -> appState.listPrimarySessions(owner.workspace().id()).stream()
                        .map(s -> Map.of("id", s.id(), "name", s.name(), "workspaceId", owner.workspace().id(), "busy",
                                s.inProgress(), "projectId", owner.projectId())))
                .toList();
    }

    private Object createWorkspace(Map<String, Object> args, InboundMcpTokenService.Credential c) {
        keys(args, "projectId", "branchName", "createBranch");
        BranchRequest request = branchRequest(args);
        requireProject(request.projectId(), c);
        CreatedWorkspace created = createWorkspace(request);
        return Map.of("workspaceId", created.workspace().id(), "sessionId", created.sessionId(), "name",
                created.workspace().name());
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
        if (create && sessionName != null && !sessionName.isBlank()) {
            throw new IllegalArgumentException("sessionName is not supported when creating a workspace");
        }
        BranchRequest branch = create ? branchRequest(args) : null;
        WorkspaceOwner authorizedOwner = null;
        Long targetId = null;
        if (session) {
            targetId = positive(args, "sessionId");
            authorizedOwner = authorizeSession(targetId, c);
        } else if (workspace) {
            targetId = positive(args, "workspaceId");
            authorizedOwner = authorizeWorkspace(targetId, c);
        } else {
            if (!c.tools().contains("create_workspace")) {
                throw denied();
            }
            requireProject(branch.projectId(), c);
        }
        var selection = chatRuntime.validateSubmission(agentId, modelId, thinkingLevel);
        long sessionId;
        long workspaceId;
        long projectId;
        if (session) {
            sessionId = targetId;
            workspaceId = authorizedOwner.workspace().id();
            projectId = authorizedOwner.projectId();
        } else if (workspace) {
            workspaceId = authorizedOwner.workspace().id();
            projectId = authorizedOwner.projectId();
            sessionId = sessionName == null || sessionName.isBlank()
                    ? appState.createSession(workspaceId).id()
                    : appState.createSession(workspaceId, sessionName).id();
        } else {
            CreatedWorkspace created = createWorkspace(branch);
            workspaceId = created.workspace().id();
            projectId = branch.projectId();
            sessionId = created.sessionId();
        }
        var result = chatRuntime.submit(sessionId, message, selection);
        return Map.of("accepted", true, "status", "accepted", "sessionId", sessionId, "workspaceId", workspaceId,
                "projectId", projectId, "assistantId", result.assistantId());
    }

    private Object summarize(Map<String, Object> args, InboundMcpTokenService.Credential c) {
        keys(args, "sessionId");
        long sid = positive(args, "sessionId");
        authorizeSession(sid, c);
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

    private List<WorkspaceOwner> authorizedOwners(InboundMcpTokenService.Credential c, Long projectId) {
        return permittedProjects(c).stream().filter(p -> projectId == null || p.id() == projectId)
                .flatMap(p -> appState.listWorkspaces(p.id()).stream().map(w -> new WorkspaceOwner(w, p.id())))
                .toList();
    }

    private WorkspaceOwner authorizeWorkspace(long workspaceId, InboundMcpTokenService.Credential c) {
        return authorizedOwners(c, null).stream().filter(owner -> owner.workspace().id() == workspaceId).findFirst()
                .orElseThrow(InboundMcpToolService::denied);
    }

    private WorkspaceOwner authorizeSession(long sessionId, InboundMcpTokenService.Credential c) {
        return authorizedOwners(c, null).stream()
                .filter(owner -> appState.listPrimarySessions(owner.workspace().id()).stream()
                        .anyMatch(session -> session.id() == sessionId))
                .findFirst().orElseThrow(InboundMcpToolService::denied);
    }

    private CreatedWorkspace createWorkspace(BranchRequest request) {
        var workspace = appState.createWorkspace(request.projectId(), request.branchName(), request.createBranch());
        long sessionId = appState.listPrimarySessions(workspace.id()).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Initial session was not created")).id();
        return new CreatedWorkspace(workspace, sessionId);
    }

    private static BranchRequest branchRequest(Map<String, Object> args) {
        long project = positive(args, "projectId");
        if (!(args.get("branchName") instanceof String branch) || branch.isBlank()
                || !(args.get("createBranch") instanceof Boolean createBranch)) {
            throw new IllegalArgumentException("Invalid arguments");
        }
        return new BranchRequest(project, branch, createBranch);
    }
    private List<Persistence.ProjectView> permittedProjects(InboundMcpTokenService.Credential c) {
        return appState.listVisibleProjects().stream().filter(p -> allowed(p.id(), c)).toList();
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
    private record BranchRequest(long projectId, String branchName, boolean createBranch) {
    }

    private record CreatedWorkspace(Persistence.WorkspaceView workspace, long sessionId) {
    }

    private record WorkspaceOwner(Persistence.WorkspaceView workspace, long projectId) {
    }

    static ResourceUnavailableException denied() {
        return new ResourceUnavailableException();
    }

    static final class ResourceUnavailableException extends RuntimeException {
        private ResourceUnavailableException() {
            super("Resource unavailable");
        }
    }
}

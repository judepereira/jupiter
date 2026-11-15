package com.judepereira.jupiter.agent.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.judepereira.jupiter.agent.catalog.AgentDefinitionService;
import com.judepereira.jupiter.agent.catalog.AgentPreferenceResolver;
import com.judepereira.jupiter.git.GitCommandRunner;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.ContextCompactionService;
import com.judepereira.jupiter.persistence.Persistence;
import com.judepereira.jupiter.persistence.Persistence.ProjectView;
import com.judepereira.jupiter.persistence.Persistence.WorkspaceView;
import com.judepereira.jupiter.ui.ChatStreamRuntime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentMatchers;

class InboundMcpToolServiceTests {
    private final AppStateService appState = mock(AppStateService.class);
    private final InboundMcpTokenService.Credential credential = new InboundMcpTokenService.Credential("id", "name",
            false, Set.of(7L), Set.of("post_message", "create_workspace"));

    static Stream<String> invalidOptions() {
        return Stream.of("agentId", "modelId", "thinkingLevel");
    }

    @ParameterizedTest
    @MethodSource("invalidOptions")
    void invalidWorkspaceSelectionDoesNotCreateSessionOrWorkspace(String option) {
        var runtime = mock(ChatStreamRuntime.class);
        var service = service(runtime);
        authorizedWorkspace();
        when(runtime.validateSubmission(option.equals("agentId") ? "missing" : null,
                option.equals("modelId") ? "missing" : null, option.equals("thinkingLevel") ? "missing" : null))
                .thenThrow(new IllegalArgumentException("Invalid " + option));
        var args = new HashMap<String, Object>();
        args.put("workspaceId", 11L);
        args.put("message", "hello");
        args.put(option, "missing");

        assertThatThrownBy(() -> service.call("post_message", args, credential))
                .isInstanceOf(IllegalArgumentException.class);

        verify(appState, never()).createSession(anyLong());
        verify(appState, never()).createSession(anyLong(), anyString());
        verify(appState, never()).createWorkspace(anyLong(), anyString(), ArgumentMatchers.anyBoolean());
        verify(runtime, never()).submit(anyLong(), anyString(), ArgumentMatchers.any());
    }

    @ParameterizedTest
    @MethodSource("invalidOptions")
    void invalidProjectSelectionDoesNotCreateWorkspace(String option) {
        var runtime = mock(ChatStreamRuntime.class);
        var service = service(runtime);
        when(appState.listVisibleProjects()).thenReturn(List.of(project()));
        when(runtime.validateSubmission(option.equals("agentId") ? "missing" : null,
                option.equals("modelId") ? "missing" : null, option.equals("thinkingLevel") ? "missing" : null))
                .thenThrow(new IllegalArgumentException("Invalid " + option));
        var args = new HashMap<String, Object>();
        args.putAll(Map.of("projectId", 7L, "branchName", "feature/x", "createBranch", true, "message", "hello"));
        args.put(option, "missing");

        assertThatThrownBy(() -> service.call("post_message", args, credential))
                .isInstanceOf(IllegalArgumentException.class);

        verify(appState, never()).createSession(anyLong());
        verify(appState, never()).createSession(anyLong(), anyString());
        verify(appState, never()).createWorkspace(anyLong(), anyString(), ArgumentMatchers.anyBoolean());
        verify(runtime, never()).submit(anyLong(), anyString(), ArgumentMatchers.any());
    }

    @Test
    void projectCreationPermissionIsCheckedBeforeRuntimeValidation() {
        var runtime = mock(ChatStreamRuntime.class);
        var service = service(runtime);
        var noCreatePermission = new InboundMcpTokenService.Credential("id", "name", false, Set.of(7L),
                Set.of("post_message"));
        when(appState.listVisibleProjects()).thenReturn(List.of(project()));

        assertThatThrownBy(() -> service.call("post_message",
                Map.of("projectId", 7L, "branchName", "feature/x", "createBranch", true, "message", "hello"),
                noCreatePermission)).isInstanceOf(InboundMcpToolService.ResourceUnavailableException.class);
        verify(runtime, never()).validateSubmission(null, null, null);
        verify(appState, never()).createWorkspace(anyLong(), anyString(), ArgumentMatchers.anyBoolean());
    }

    @Test
    void createWorkspaceReturnsInitialSession() {
        var service = service(mock(ChatStreamRuntime.class));
        when(appState.listVisibleProjects()).thenReturn(List.of(project()));
        when(appState.createWorkspace(7, "feature/x", true)).thenReturn(workspace());
        when(appState.listPrimarySessions(11)).thenReturn(List.of(session()));

        assertThat(service.call("create_workspace",
                Map.of("projectId", 7L, "branchName", "feature/x", "createBranch", true), credential))
                .isEqualTo(Map.of("workspaceId", 11L, "sessionId", 13L, "name", "workspace"));
    }

    @Test
    void postMessageCreatesProjectWorkspaceAndSubmitsToInitialSession() {
        var runtime = mock(ChatStreamRuntime.class);
        var selection = mock(ChatStreamRuntime.SubmissionSelection.class);
        var service = service(runtime);
        when(appState.listVisibleProjects()).thenReturn(List.of(project()));
        when(appState.createWorkspace(7, "feature/x", true)).thenReturn(workspace());
        when(appState.listPrimarySessions(11)).thenReturn(List.of(session()));
        when(runtime.validateSubmission(null, null, null)).thenReturn(selection);
        when(runtime.submit(13, "hello", selection))
                .thenReturn(new ChatStreamRuntime.Submission(null, null, "assistant"));

        assertThat(service.call("post_message",
                Map.of("projectId", 7L, "branchName", "feature/x", "createBranch", true, "message", "hello"),
                credential))
                .isEqualTo(Map.of("accepted", true, "status", "accepted", "sessionId", 13L, "workspaceId", 11L,
                        "projectId", 7L, "assistantId", "assistant"));
    }

    @Test
    void postMessageToSessionAuthorizesThroughProjectWorkspaceAndPrimarySession() {
        var runtime = mock(ChatStreamRuntime.class);
        var selection = mock(ChatStreamRuntime.SubmissionSelection.class);
        var service = service(runtime);
        authorizedWorkspace();
        when(appState.listPrimarySessions(11)).thenReturn(List.of(session()));
        when(runtime.validateSubmission(null, null, null)).thenReturn(selection);
        when(runtime.submit(13, "hello", selection))
                .thenReturn(new ChatStreamRuntime.Submission(null, null, "assistant"));

        var response = service.call("post_message", Map.of("sessionId", 13L, "message", "hello"), credential);

        assertThat(response).isEqualTo(Map.of("accepted", true, "status", "accepted", "sessionId", 13L, "workspaceId",
                11L, "projectId", 7L, "assistantId", "assistant"));
        verify(appState, times(1)).listVisibleProjects();
        verify(appState, times(1)).listWorkspaces(7);
        verify(appState, times(1)).listPrimarySessions(11);
    }

    @Test
    void unauthorizedWorkspaceTargetIsUnavailableWithoutLoadingWorkspaceDetails() {
        var service = service(mock(ChatStreamRuntime.class));
        assertThatThrownBy(
                () -> service.call("post_message", Map.of("workspaceId", 99L, "message", "hello"), credential))
                .isInstanceOf(InboundMcpToolService.ResourceUnavailableException.class)
                .hasMessage("Resource unavailable");
        verify(appState, never()).loadViewData();
    }

    private InboundMcpToolService service(ChatStreamRuntime runtime) {
        return new InboundMcpToolService(appState, mock(AgentDefinitionService.class),
                mock(AgentPreferenceResolver.class), mock(ContextCompactionService.class), runtime,
                mock(GitCommandRunner.class));
    }

    private void authorizedWorkspace() {
        when(appState.listVisibleProjects()).thenReturn(List.of(project()));
        when(appState.listWorkspaces(7)).thenReturn(List.of(workspace()));
    }

    private static ProjectView project() {
        return new ProjectView(7, "project", "/tmp", "", List.of(), "", false, null);
    }

    private static WorkspaceView workspace() {
        return new WorkspaceView(11, "workspace", "/tmp", false, null);
    }

    private static Persistence.SessionView session() {
        return new Persistence.SessionView(13, "session", false, Persistence.RailStatus.NONE);
    }
}

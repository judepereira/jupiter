package com.judepereira.jupiter.git;

import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.Persistence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class GitAutoUpdateServiceTests {

    @Test
    void scheduledPassSkipsAllWorkspacesWhenGloballyDisabled() {
        AppStateService appStateService = mock(AppStateService.class);
        when(appStateService.loadAutoGitUpdateEnabled()).thenReturn(false);
        GitCommandRunner commandRunner = mock(GitCommandRunner.class);
        GitAutoUpdateService service = new GitAutoUpdateService(appStateService, commandRunner);

        service.updateOnSchedule();

        verify(appStateService, never()).listAutoGitUpdateWorkspaces();
        verifyNoInteractions(commandRunner);
    }

    @Test
    void passUpdatesEveryWorkspaceReturnedByPersistence(@TempDir Path tempDir) {
        AppStateService appStateService = mock(AppStateService.class);
        when(appStateService.loadAutoGitUpdateEnabled()).thenReturn(true);
        var first = workspace(1, "first", tempDir.resolve("first"));
        var second = workspace(2, "second", tempDir.resolve("second"));
        when(appStateService.listAutoGitUpdateWorkspaces()).thenReturn(List.of(first, second));
        List<List<String>> commands = new ArrayList<>();
        GitCommandRunner commandRunner = recordingSuccessfulMockRunner(commands);
        GitAutoUpdateService service = new GitAutoUpdateService(appStateService, commandRunner);

        service.runUpdatePass();

        verify(commandRunner, times(10)).run(any(), any(), eq(GitAutoUpdateService.COMMAND_TIMEOUT));
        verify(appStateService).resetWorkspaceAutoGitUpdateFailure(1);
        verify(appStateService).resetWorkspaceAutoGitUpdateFailure(2);
    }

    @Test
    void usesExpectedFastForwardOnlyCommandSequence(@TempDir Path tempDir) {
        AppStateService appStateService = mock(AppStateService.class);
        Path path = tempDir.resolve("workspace");
        var workspace = workspace(7, "project", path);
        List<List<String>> commands = new ArrayList<>();
        GitCommandRunner commandRunner = recordingSuccessfulRunner(commands);
        GitAutoUpdateService service = new GitAutoUpdateService(appStateService, commandRunner);

        var result = service.updateWorkspace(workspace);

        assertThat(result.status()).isEqualTo(GitAutoUpdateService.UpdateResult.Status.UP_TO_DATE);
        assertThat(commands).containsExactly(
                List.of("git", "symbolic-ref", "--quiet", "--short", "HEAD"),
                List.of("git", "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}"),
                List.of("git", "rev-parse", "--verify", "HEAD"),
                List.of("git", "pull", "--ff-only"),
                List.of("git", "rev-parse", "--verify", "HEAD"));
    }

    @Test
    void changedHeadEmitsInfoAndResetsFailure(@TempDir Path tempDir) {
        AppStateService appStateService = mock(AppStateService.class);
        var workspace = workspace(8, "project", tempDir.resolve("workspace"));
        var session = new Persistence.SessionView(81, "Session #1", false, Persistence.RailStatus.NONE);
        when(appStateService.findMostRecentlyOpenedVisiblePrimarySession(8)).thenReturn(Optional.of(session));
        GitCommandRunner commandRunner = runner(
                success("main\n"), success("origin/main\n"), success("before\n"), success("Already up to date\n"), success("after\n"));
        GitAutoUpdateService service = new GitAutoUpdateService(appStateService, commandRunner);

        var result = service.updateWorkspace(workspace);

        assertThat(result).isEqualTo(new GitAutoUpdateService.UpdateResult(
                GitAutoUpdateService.UpdateResult.Status.UPDATED, "before", "after", null, false));
        verify(appStateService).resetWorkspaceAutoGitUpdateFailure(8);
        verify(appStateService).appendInfoMessage(81, "Git updated workspace \"project\" to after.");
    }

    @Test
    void upToDateHeadResetsFailureWithoutInfo(@TempDir Path tempDir) {
        AppStateService appStateService = mock(AppStateService.class);
        var workspace = workspace(9, "project", tempDir.resolve("workspace"));
        GitAutoUpdateService service = new GitAutoUpdateService(appStateService, runner(
                success("main"), success("origin/main"), success("same"), success("Already up to date"), success("same")));

        var result = service.updateWorkspace(workspace);

        assertThat(result.status()).isEqualTo(GitAutoUpdateService.UpdateResult.Status.UP_TO_DATE);
        verify(appStateService).resetWorkspaceAutoGitUpdateFailure(9);
        verify(appStateService, never()).appendInfoMessage(anyLong(), anyString());
    }

    @Test
    void failureNotificationIsOncePerSessionAndResetsAfterSuccess(@TempDir Path tempDir) {
        AppStateService appStateService = mock(AppStateService.class);
        var workspace = workspace(10, "project", tempDir.resolve("workspace"));
        when(appStateService.appendAutoGitUpdateFailureMessage(eq(10L), anyString()))
                .thenReturn(new Persistence.AutoGitUpdateFailureNotification(true),
                        new Persistence.AutoGitUpdateFailureNotification(false),
                        new Persistence.AutoGitUpdateFailureNotification(true));
        GitCommandRunner commandRunner = mock(GitCommandRunner.class);
        when(commandRunner.run(any(), any(), eq(GitAutoUpdateService.COMMAND_TIMEOUT)))
                .thenReturn(success("main"), success("origin/main"), success("before"), failure("pull failed"),
                        success("main"), success("origin/main"), success("before"), failure("pull failed"),
                        success("main"), success("origin/main"), success("same"), success("Already up to date"), success("same"),
                        success("main"), success("origin/main"), success("before"), failure("pull failed"));
        GitAutoUpdateService service = new GitAutoUpdateService(appStateService, commandRunner);

        var first = service.updateWorkspace(workspace);
        var repeated = service.updateWorkspace(workspace);
        var success = service.updateWorkspace(workspace);
        var afterSuccess = service.updateWorkspace(workspace);

        assertThat(first.firstFailure()).isTrue();
        assertThat(repeated.firstFailure()).isFalse();
        assertThat(success.status()).isEqualTo(GitAutoUpdateService.UpdateResult.Status.UP_TO_DATE);
        assertThat(afterSuccess.firstFailure()).isTrue();
        verify(appStateService).resetWorkspaceAutoGitUpdateFailure(10);
        verify(appStateService, times(3)).appendAutoGitUpdateFailureMessage(eq(10L), anyString());
    }

    @Test
    void noUpstreamSkipsPullAndResetsFailure(@TempDir Path tempDir) {
        AppStateService appStateService = mock(AppStateService.class);
        var workspace = workspace(11, "project", tempDir.resolve("workspace"));
        GitCommandRunner commandRunner = runner(success("main"), failure("fatal: no upstream branch"));
        GitAutoUpdateService service = new GitAutoUpdateService(appStateService, commandRunner);

        var result = service.updateWorkspace(workspace);

        assertThat(result.status()).isEqualTo(GitAutoUpdateService.UpdateResult.Status.SKIPPED);
        assertThat(result.message()).isEqualTo("Git workspace has no upstream branch");
        verify(appStateService).resetWorkspaceAutoGitUpdateFailure(11);
        verify(commandRunner, times(2)).run(any(), any(), eq(GitAutoUpdateService.COMMAND_TIMEOUT));
    }

    @Test
    void manualUpdateIgnoresGlobalSetting(@TempDir Path tempDir) {
        AppStateService appStateService = mock(AppStateService.class);
        when(appStateService.loadAutoGitUpdateEnabled()).thenReturn(false);
        var workspace = workspace(12, "project", tempDir.resolve("workspace"));
        when(appStateService.loadAutoGitUpdateWorkspace(12)).thenReturn(workspace);
        GitAutoUpdateService service = new GitAutoUpdateService(appStateService, successfulRunner());

        var result = service.updateWorkspaceManually(12);

        assertThat(result.status()).isEqualTo(GitAutoUpdateService.UpdateResult.Status.UP_TO_DATE);
        verify(appStateService, never()).listAutoGitUpdateWorkspaces();
        verify(appStateService, never()).loadAutoGitUpdateEnabled();
    }

    @Test
    void startupEntryPointRunsAnUpdatePass() {
        AppStateService appStateService = mock(AppStateService.class);
        when(appStateService.loadAutoGitUpdateEnabled()).thenReturn(false);
        GitCommandRunner commandRunner = mock(GitCommandRunner.class);
        GitAutoUpdateService service = new GitAutoUpdateService(appStateService, commandRunner);

        service.updateAtStartup();

        verify(appStateService).loadAutoGitUpdateEnabled();
        verifyNoInteractions(commandRunner);
    }

    private static Persistence.WorkspaceView workspace(long id, String name, Path path) {
        return new Persistence.WorkspaceView(id, name, path.toString(), false, Persistence.RailStatus.NONE);
    }

    private static GitCommandRunner successfulRunner() {
        return recordingSuccessfulRunner(new ArrayList<>());
    }

    private static GitCommandRunner recordingSuccessfulRunner(List<List<String>> commands) {
        return (path, command, timeout) -> {
            commands.add(command);
            return successfulResult(command);
        };
    }

    private static GitCommandRunner recordingSuccessfulMockRunner(List<List<String>> commands) {
        GitCommandRunner commandRunner = mock(GitCommandRunner.class);
        when(commandRunner.run(any(), any(), eq(GitAutoUpdateService.COMMAND_TIMEOUT))).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(1);
            commands.add(command);
            return successfulResult(command);
        });
        return commandRunner;
    }

    private static GitCommandRunner.GitCommandResult successfulResult(List<String> command) {
        return switch (command.get(1)) {
            case "symbolic-ref" -> success("main");
            case "pull" -> success("");
            default -> success("same");
        };
    }

    private static GitCommandRunner runner(GitCommandRunner.GitCommandResult... results) {
        return mockRunner(results);
    }

    private static GitCommandRunner mockRunner(GitCommandRunner.GitCommandResult... results) {
        GitCommandRunner commandRunner = mock(GitCommandRunner.class);
        when(commandRunner.run(any(), any(), eq(GitAutoUpdateService.COMMAND_TIMEOUT)))
                .thenReturn(results[0], Arrays.copyOfRange(results, 1, results.length));
        return commandRunner;
    }

    private static GitCommandRunner.GitCommandResult success(String stdout) {
        return new GitCommandRunner.GitCommandResult(0, stdout, "");
    }

    private static GitCommandRunner.GitCommandResult failure(String stderr) {
        return new GitCommandRunner.GitCommandResult(1, "", stderr);
    }
}

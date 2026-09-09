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
    void changedHeadEmitsSummaryAndUsesExactMetadataCommands(@TempDir Path tempDir) {
        AppStateService appStateService = mock(AppStateService.class);
        var workspace = workspace(8, "project", tempDir.resolve("workspace"));
        var session = new Persistence.SessionView(81, "Session #1", false, Persistence.RailStatus.NONE);
        when(appStateService.findMostRecentlyOpenedVisiblePrimarySession(8)).thenReturn(Optional.of(session));
        List<List<String>> commands = new ArrayList<>();
        GitCommandRunner commandRunner = metadataRunner(commands, "before", "after", success("2\n"), success("latest subject\n"));

        var result = new GitAutoUpdateService(appStateService, commandRunner).updateWorkspace(workspace);

        assertThat(result).isEqualTo(new GitAutoUpdateService.UpdateResult(
                GitAutoUpdateService.UpdateResult.Status.UPDATED, "before", "after", null, false));
        assertThat(commands).containsExactly(
                List.of("git", "symbolic-ref", "--quiet", "--short", "HEAD"),
                List.of("git", "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}"),
                List.of("git", "rev-parse", "--verify", "HEAD"),
                List.of("git", "pull", "--ff-only"),
                List.of("git", "rev-parse", "--verify", "HEAD"),
                List.of("git", "rev-list", "--count", "before..after"),
                List.of("git", "log", "-1", "--format=%s", "after"));
        verify(appStateService).resetWorkspaceAutoGitUpdateFailure(8);
        verify(appStateService).appendInfoMessage(81,
                "Background git update brought 2 commits into this workspace. Latest commit: latest subject");
    }

    @Test
    void changedHeadUsesSingularAndFirstCrlfSafeSubjectLine(@TempDir Path tempDir) {
        AppStateService appStateService = mock(AppStateService.class);
        var workspace = workspace(18, "project", tempDir.resolve("workspace"));
        var session = new Persistence.SessionView(181, "Session", false, Persistence.RailStatus.NONE);
        when(appStateService.findMostRecentlyOpenedVisiblePrimarySession(18)).thenReturn(Optional.of(session));
        GitCommandRunner runner = metadataRunner(new ArrayList<>(), "before", "after", success("1"), success("one line\r\nsecond line"));

        new GitAutoUpdateService(appStateService, runner).updateWorkspace(workspace);

        verify(appStateService).appendInfoMessage(181,
                "Background git update brought 1 commit into this workspace. Latest commit: one line");
    }

    @Test
    void changedHeadTruncatesTo256UnicodeCodePoints(@TempDir Path tempDir) {
        AppStateService appStateService = mock(AppStateService.class);
        var workspace = workspace(19, "project", tempDir.resolve("workspace"));
        var session = new Persistence.SessionView(191, "Session", false, Persistence.RailStatus.NONE);
        when(appStateService.findMostRecentlyOpenedVisiblePrimarySession(19)).thenReturn(Optional.of(session));
        String subject = "a".repeat(255) + "\uD83D\uDE00" + "extra";
        new GitAutoUpdateService(appStateService, metadataRunner(new ArrayList<>(), "before", "after", success("3"), success(subject))).updateWorkspace(workspace);

        verify(appStateService).appendInfoMessage(191,
                "Background git update brought 3 commits into this workspace. Latest commit: " + "a".repeat(255) + "\uD83D\uDE00");
    }

    @Test
    void changedHeadAllowsBlankSubject(@TempDir Path tempDir) {
        AppStateService appStateService = mock(AppStateService.class);
        var workspace = workspace(20, "project", tempDir.resolve("workspace"));
        var session = new Persistence.SessionView(201, "Session", false, Persistence.RailStatus.NONE);
        when(appStateService.findMostRecentlyOpenedVisiblePrimarySession(20)).thenReturn(Optional.of(session));
        new GitAutoUpdateService(appStateService, metadataRunner(new ArrayList<>(), "before", "after", success("1"), success("\r\nbody"))).updateWorkspace(workspace);

        verify(appStateService).appendInfoMessage(201,
                "Background git update brought 1 commit into this workspace. Latest commit: ");
    }

    @Test
    void metadataFailuresAndInvalidCountsFailWithoutInfo(@TempDir Path tempDir) {
        assertMetadataFailure(tempDir, "count", failure("count failed"));
        assertMetadataFailure(tempDir, "subject", failure("subject failed"));
        assertMetadataFailure(tempDir, "zero", success("0"));
        assertMetadataFailure(tempDir, "invalid", success("not-a-number"));
    }

    private void assertMetadataFailure(Path tempDir, String name, GitCommandRunner.GitCommandResult metadataResult) {
        AppStateService appStateService = mock(AppStateService.class);
        when(appStateService.appendAutoGitUpdateFailureMessage(anyLong(), anyString()))
                .thenReturn(new Persistence.AutoGitUpdateFailureNotification(true));
        var workspace = workspace(21, name, tempDir.resolve(name));
        GitCommandRunner commandRunner;
        if (name.equals("count") || name.equals("zero") || name.equals("invalid")) {
            commandRunner = metadataRunner(new ArrayList<>(), "before", "after", metadataResult, success("subject"));
        } else {
            commandRunner = metadataRunner(new ArrayList<>(), "before", "after", success("2"), metadataResult);
        }
        var result = new GitAutoUpdateService(appStateService, commandRunner).updateWorkspace(workspace);
        assertThat(result.status()).isEqualTo(GitAutoUpdateService.UpdateResult.Status.FAILED);
        verify(appStateService, never()).appendInfoMessage(anyLong(), anyString());
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
    void noRemotesSkipUpstreamlessPullAndResetFailure(@TempDir Path tempDir) {
        AppStateService appStateService = mock(AppStateService.class);
        var workspace = workspace(11, "project", tempDir.resolve("workspace"));
        GitCommandRunner commandRunner = runner(success("main"), failure("fatal: no upstream branch"), success(""));
        GitAutoUpdateService service = new GitAutoUpdateService(appStateService, commandRunner);

        var result = service.updateWorkspace(workspace);

        assertThat(result.status()).isEqualTo(GitAutoUpdateService.UpdateResult.Status.SKIPPED);
        assertThat(result.message()).isEqualTo("Git workspace has no configured remote");
        verify(appStateService).resetWorkspaceAutoGitUpdateFailure(11);
        verify(commandRunner, times(3)).run(any(), any(), eq(GitAutoUpdateService.COMMAND_TIMEOUT));
    }

    @Test
    void upstreamlessPullUsesOriginAndPreservesSlashBranch(@TempDir Path tempDir) {
        AppStateService appStateService = mock(AppStateService.class);
        var workspace = workspace(13, "project", tempDir.resolve("workspace"));
        List<List<String>> commands = new ArrayList<>();
        GitCommandRunner commandRunner = runnerWithCommands(commands,
                success("feature/topic"), failure("fatal: no upstream branch"), success("origin\n"),
                success("abc\trefs/heads/feature/topic\n"), success("before"), success(""), success("before"));
        GitAutoUpdateService service = new GitAutoUpdateService(appStateService, commandRunner);

        var result = service.updateWorkspace(workspace);

        assertThat(result.status()).isEqualTo(GitAutoUpdateService.UpdateResult.Status.UP_TO_DATE);
        assertThat(commands).contains(List.of("git", "pull", "--ff-only", "origin", "feature/topic"));
    }

    @Test
    void upstreamlessPullUsesSoleNonOriginRemote(@TempDir Path tempDir) {
        AppStateService appStateService = mock(AppStateService.class);
        var workspace = workspace(14, "project", tempDir.resolve("workspace"));
        List<List<String>> commands = new ArrayList<>();
        GitCommandRunner commandRunner = runnerWithCommands(commands,
                success("main"), failure("no upstream"), success("upstream\n"), success("x\trefs/heads/main\n"),
                success("same"), success(""), success("same"));
        var result = new GitAutoUpdateService(appStateService, commandRunner).updateWorkspace(workspace);

        assertThat(result.status()).isEqualTo(GitAutoUpdateService.UpdateResult.Status.UP_TO_DATE);
        assertThat(commands).contains(List.of("git", "pull", "--ff-only", "upstream", "main"));
    }

    @Test
    void upstreamlessPullSkipsAmbiguousRemotes(@TempDir Path tempDir) {
        AppStateService appStateService = mock(AppStateService.class);
        var workspace = workspace(15, "project", tempDir.resolve("workspace"));
        var result = new GitAutoUpdateService(appStateService, runner(success("main"), failure("no upstream"), success("one\ntwo\n")))
                .updateWorkspace(workspace);

        assertThat(result.status()).isEqualTo(GitAutoUpdateService.UpdateResult.Status.SKIPPED);
        assertThat(result.message()).contains("multiple remotes");
    }

    @Test
    void upstreamlessPullSkipsMissingRemoteBranch(@TempDir Path tempDir) {
        AppStateService appStateService = mock(AppStateService.class);
        var workspace = workspace(16, "project", tempDir.resolve("workspace"));
        when(appStateService.appendAutoGitUpdateFailureMessage(eq(16L), anyString()))
                .thenReturn(new Persistence.AutoGitUpdateFailureNotification(true));
        var result = new GitAutoUpdateService(appStateService, runner(success("main"), failure("no upstream"),
                success("origin\n"), new GitCommandRunner.GitCommandResult(2, "", ""))).updateWorkspace(workspace);

        assertThat(result.status()).isEqualTo(GitAutoUpdateService.UpdateResult.Status.SKIPPED);
        assertThat(result.message()).contains("has no branch named main");
    }

    @Test
    void upstreamlessPullFailureIsFailed(@TempDir Path tempDir) {
        AppStateService appStateService = mock(AppStateService.class);
        var workspace = workspace(17, "project", tempDir.resolve("workspace"));
        when(appStateService.appendAutoGitUpdateFailureMessage(eq(17L), anyString()))
                .thenReturn(new Persistence.AutoGitUpdateFailureNotification(true));
        var result = new GitAutoUpdateService(appStateService, runner(success("main"), failure("no upstream"),
                success("origin\n"), success("x\trefs/heads/main\n"), success("before"), failure("transport failed")))
                .updateWorkspace(workspace);

        assertThat(result.status()).isEqualTo(GitAutoUpdateService.UpdateResult.Status.FAILED);
        assertThat(result.message()).contains("Git pull failed");
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

    private static GitCommandRunner metadataRunner(List<List<String>> commands, String before, String after,
                                                    GitCommandRunner.GitCommandResult count,
                                                    GitCommandRunner.GitCommandResult subject) {
        GitCommandRunner commandRunner = mock(GitCommandRunner.class);
        when(commandRunner.run(any(), any(), eq(GitAutoUpdateService.COMMAND_TIMEOUT))).thenAnswer(new org.mockito.stubbing.Answer<GitCommandRunner.GitCommandResult>() {
            int headLookups;

            @Override
            public GitCommandRunner.GitCommandResult answer(org.mockito.invocation.InvocationOnMock invocation) {
                List<String> command = invocation.getArgument(1);
                commands.add(command);
                if (command.get(1).equals("rev-parse") && command.getLast().equals("HEAD")) {
                    return headLookups++ == 0 ? success(before) : success(after);
                }
                if (command.get(1).equals("rev-list")) return count;
                if (command.get(1).equals("log")) return subject;
                return switch (command.get(1)) {
                    case "symbolic-ref" -> success("main");
                    case "rev-parse" -> success("origin/main");
                    case "pull" -> success("");
                    default -> success("");
                };
            }
        });
        return commandRunner;
    }

    private static GitCommandRunner runnerWithCommands(List<List<String>> commands, GitCommandRunner.GitCommandResult... results) {
        GitCommandRunner commandRunner = mock(GitCommandRunner.class);
        when(commandRunner.run(any(), any(), eq(GitAutoUpdateService.COMMAND_TIMEOUT))).thenAnswer(invocation -> {
            commands.add(invocation.getArgument(1));
            return results[commands.size() - 1];
        });
        return commandRunner;
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

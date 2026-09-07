package com.judepereira.jupiter.git;

import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.Persistence;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Log4j2
public class GitAutoUpdateService {

    static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(2);

    private final AppStateService appStateService;
    private final GitCommandRunner commandRunner;
    private final ConcurrentMap<String, Object> repositoryLocks = new ConcurrentHashMap<>();
    private final AtomicBoolean passRunning = new AtomicBoolean();

    public GitAutoUpdateService(AppStateService appStateService, GitCommandRunner commandRunner) {
        this.appStateService = appStateService;
        this.commandRunner = commandRunner;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void updateAtStartup() {
        runUpdatePass();
    }

    @Scheduled(fixedDelayString = "PT10M")
    public void updateOnSchedule() {
        runUpdatePass();
    }

    /** Runs one non-overlapping pass. This is also the entry point for a manual update-all action. */
    public void runUpdatePass() {
        if (!passRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            if (!appStateService.loadAutoGitUpdateEnabled()) {
                return;
            }
            for (Persistence.WorkspaceView workspace : appStateService.listAutoGitUpdateWorkspaces()) {
                updateWorkspace(workspace);
            }
        } finally {
            passRunning.set(false);
        }
    }

    /** Updates one workspace and is safe to call from a future manual controller. */
    public UpdateResult updateWorkspace(long workspaceId) {
        return updateWorkspace(appStateService.loadAutoGitUpdateWorkspace(workspaceId));
    }

    public UpdateResult updateWorkspaceManually(long workspaceId) {
        return updateWorkspace(workspaceId);
    }

    public UpdateResult updateWorkspace(Persistence.WorkspaceView workspace) {
        try {
            Path path = Path.of(workspace.path());
            String lockKey = repositoryLockKey(path);
            Object lock = repositoryLocks.computeIfAbsent(lockKey, ignored -> new Object());
            synchronized (lock) {
                return updateWorkspaceLocked(workspace, path);
            }
        } catch (RuntimeException e) {
            return fail(workspace, "Git update failed", e);
        }
    }

    private UpdateResult updateWorkspaceLocked(Persistence.WorkspaceView workspace, Path path) {
        try {
            GitCommandRunner.GitCommandResult branch = run(path, "git", "symbolic-ref", "--quiet", "--short", "HEAD");
            if (!branch.succeeded() || branch.stdout().trim().isBlank()) {
                return fail(workspace, "Could not determine the active Git branch", branch);
            }

            GitCommandRunner.GitCommandResult upstream = run(path, "git", "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}");
            String remote = null;
            if (!upstream.succeeded() || upstream.stdout().trim().isBlank()) {
                if (upstreamIndicatesNoUpstream(upstream)) {
                    RemoteSelection selection = selectFallbackRemote(workspace, path, branch.stdout().trim());
                    if (selection.result() != null) {
                        return selection.result();
                    }
                    remote = selection.remote();
                } else {
                    return fail(workspace, "Could not determine the Git upstream branch", upstream);
                }
            }

            GitCommandRunner.GitCommandResult before = run(path, "git", "rev-parse", "--verify", "HEAD");
            if (!before.succeeded() || before.stdout().trim().isBlank()) {
                return fail(workspace, "Could not determine the current Git revision", before);
            }

            GitCommandRunner.GitCommandResult pull = remote == null
                    ? run(path, "git", "pull", "--ff-only")
                    : run(path, "git", "pull", "--ff-only", remote, branch.stdout().trim());
            if (!pull.succeeded()) {
                return fail(workspace, "Git pull failed", pull);
            }

            GitCommandRunner.GitCommandResult after = run(path, "git", "rev-parse", "--verify", "HEAD");
            if (!after.succeeded() || after.stdout().trim().isBlank()) {
                return fail(workspace, "Could not determine the Git revision after pulling", after);
            }

            String beforeRevision = before.stdout().trim();
            String afterRevision = after.stdout().trim();
            if (!beforeRevision.equals(afterRevision)) {
                GitCommandRunner.GitCommandResult count = run(path, "git", "rev-list", "--count",
                        beforeRevision + ".." + afterRevision);
                if (!count.succeeded()) {
                    return fail(workspace, "Could not determine commits introduced by the Git update", count);
                }
                String commitCount = count.stdout().trim();
                if (!commitCount.matches("[0-9]+") || new BigInteger(commitCount).signum() <= 0) {
                    return fail(workspace, "Could not determine commits introduced by the Git update: invalid commit count", count);
                }
                BigInteger introducedCommitCount = new BigInteger(commitCount);

                GitCommandRunner.GitCommandResult subject = run(path, "git", "log", "-1", "--format=%s", afterRevision);
                if (!subject.succeeded()) {
                    return fail(workspace, "Could not determine the latest Git commit subject", subject);
                }

                appStateService.resetWorkspaceAutoGitUpdateFailure(workspace.id());
                String latestSubject = firstLine(subject.stdout());
                appStateService.findMostRecentlyOpenedVisiblePrimarySession(workspace.id())
                        .ifPresent(session -> appStateService.appendInfoMessage(session.id(),
                                "Background git update brought " + introducedCommitCount + (introducedCommitCount.equals(BigInteger.ONE) ? " commit" : " commits")
                                        + " into this workspace. Latest commit: " + truncateCodePoints(latestSubject, 256)));
                return UpdateResult.updated(beforeRevision, afterRevision);
            }
            appStateService.resetWorkspaceAutoGitUpdateFailure(workspace.id());
            return UpdateResult.upToDate(beforeRevision);
        } catch (RuntimeException e) {
            return fail(workspace, "Git update failed", e);
        }
    }

    private RemoteSelection selectFallbackRemote(Persistence.WorkspaceView workspace, Path path, String branch) {
        GitCommandRunner.GitCommandResult remotes = run(path, "git", "remote");
        if (!remotes.succeeded()) {
            return new RemoteSelection(null, fail(workspace, "Could not determine configured Git remotes", remotes));
        }
        List<String> configured = remotes.stdout().lines().map(String::trim).filter(name -> !name.isBlank()).toList();
        String remote;
        if (configured.contains("origin")) {
            remote = "origin";
        } else if (configured.size() == 1) {
            remote = configured.getFirst();
        } else if (configured.isEmpty()) {
            return new RemoteSelection(null, skip(workspace, "Git workspace has no configured remote"));
        } else {
            return new RemoteSelection(null, skip(workspace,
                    "Git workspace has no upstream branch and multiple remotes are configured; configure an upstream branch or keep an origin remote"));
        }

        GitCommandRunner.GitCommandResult remoteBranch = run(path, "git", "ls-remote", "--exit-code", "--heads", remote, "refs/heads/" + branch);
        if (!remoteBranch.succeeded()) {
            if (remoteBranch.exitCode() == 2 && remoteBranch.stdout().isBlank() && remoteBranch.stderr().isBlank()) {
                return new RemoteSelection(null, skip(workspace,
                        "Git workspace has no upstream branch and remote " + remote + " has no branch named " + branch));
            }
            return new RemoteSelection(null, fail(workspace, "Could not determine whether remote branch exists", remoteBranch));
        }
        return new RemoteSelection(remote, null);
    }

    private UpdateResult skip(Persistence.WorkspaceView workspace, String message) {
        appStateService.resetWorkspaceAutoGitUpdateFailure(workspace.id());
        return UpdateResult.skipped(message);
    }

    private record RemoteSelection(String remote, UpdateResult result) {
    }

    private UpdateResult fail(Persistence.WorkspaceView workspace, String summary, GitCommandRunner.GitCommandResult result) {
        String details = output(result.stdout(), result.stderr());
        return fail(workspace, summary + (details.isBlank() ? "" : "\n\n" + details));
    }

    private UpdateResult fail(Persistence.WorkspaceView workspace, String message, RuntimeException exception) {
        log.error("Automatic Git update failed for workspace {}", workspace.path(), exception);
        return fail(workspace, message + (exception.getMessage() == null ? "" : ": " + exception.getMessage()));
    }

    private UpdateResult fail(Persistence.WorkspaceView workspace, String message) {
        var notification = appStateService.appendAutoGitUpdateFailureMessage(workspace.id(),
                "Git update failed for workspace \"" + workspace.name() + "\": " + message);
        if (notification.firstFailure()) {
            log.error("Git update failed for workspace {}: {}", workspace.name(), message);
        }
        return UpdateResult.failed(message, notification.firstFailure());
    }

    private GitCommandRunner.GitCommandResult run(Path path, String... command) {
        return commandRunner.run(path, List.of(command), COMMAND_TIMEOUT);
    }

    private String repositoryLockKey(Path path) {
        try {
            Path realPath = path.toRealPath();
            Path gitPath = realPath.resolve(".git");
            if (Files.isRegularFile(gitPath)) {
                String gitdir = Files.readString(gitPath).trim();
                if (gitdir.startsWith("gitdir:")) {
                    Path worktreeGitDir = Path.of(gitdir.substring("gitdir:".length()).trim()).toAbsolutePath().normalize();
                    return worktreeGitDir.getParent().getParent().toString();
                }
            }
            return gitPath.toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            return path.toAbsolutePath().normalize().toString();
        }
    }

    private boolean upstreamIndicatesNoUpstream(GitCommandRunner.GitCommandResult result) {
        String text = (result.stdout() + "\n" + result.stderr()).toLowerCase();
        return text.contains("no upstream") || text.contains("no such ref") || text.contains("does not point to a valid object");
    }

    private String output(String stdout, String stderr) {
        return List.of(stdout == null ? "" : stdout.trim(), stderr == null ? "" : stderr.trim()).stream()
                .filter(value -> !value.isBlank())
                .reduce((left, right) -> left + "\n" + right).orElse("");
    }

    private String firstLine(String value) {
        String line = value == null ? "" : value;
        int newline = line.indexOf('\n');
        if (newline >= 0) {
            line = line.substring(0, newline);
        }
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    private String truncateCodePoints(String value, int maximum) {
        if (value.codePointCount(0, value.length()) <= maximum) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maximum));
    }

    public record UpdateResult(Status status, String beforeRevision, String afterRevision, String message, boolean firstFailure) {
        public enum Status { SKIPPED, UP_TO_DATE, UPDATED, FAILED }

        static UpdateResult skipped(String message) {
            return new UpdateResult(Status.SKIPPED, null, null, message, false);
        }

        static UpdateResult upToDate(String revision) {
            return new UpdateResult(Status.UP_TO_DATE, revision, revision, null, false);
        }

        static UpdateResult updated(String before, String after) {
            return new UpdateResult(Status.UPDATED, before, after, null, false);
        }

        static UpdateResult failed(String message, boolean firstFailure) {
            return new UpdateResult(Status.FAILED, null, null, message, firstFailure);
        }
    }
}

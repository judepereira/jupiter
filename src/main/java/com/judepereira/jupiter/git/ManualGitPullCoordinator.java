package com.judepereira.jupiter.git;

import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.Persistence;
import com.judepereira.jupiter.ui.balloon.SystemBalloonService;
import jakarta.annotation.PreDestroy;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns the lifetime and deduplication of user-requested Git pulls. */
@Service
@Log4j2
public class ManualGitPullCoordinator {
    private final AppStateService appStateService;
    private final GitAutoUpdateService gitAutoUpdateService;
    private final SystemBalloonService systemBalloonService;
    private final ExecutorService executor;
    private final ConcurrentMap<Long, Boolean> activePulls = new ConcurrentHashMap<>();
    private final boolean noOp;

    public static ManualGitPullCoordinator noOp() {
        return new ManualGitPullCoordinator();
    }

    private ManualGitPullCoordinator() {
        this.appStateService = null;
        this.gitAutoUpdateService = null;
        this.systemBalloonService = null;
        this.executor = null;
        this.noOp = true;
    }

    public ManualGitPullCoordinator(AppStateService appStateService, GitAutoUpdateService gitAutoUpdateService,
                                    SystemBalloonService systemBalloonService) {
        this(appStateService, gitAutoUpdateService, systemBalloonService, Executors.newVirtualThreadPerTaskExecutor());
    }

    public ManualGitPullCoordinator(AppStateService appStateService, GitAutoUpdateService gitAutoUpdateService,
                                    SystemBalloonService systemBalloonService, ExecutorService executor) {
        this.appStateService = appStateService;
        this.gitAutoUpdateService = gitAutoUpdateService;
        this.systemBalloonService = systemBalloonService;
        this.executor = executor;
        this.noOp = false;
    }

    public boolean isPulling(long workspaceId) {
        return !noOp && activePulls.containsKey(workspaceId);
    }

    /** Returns false when an equivalent pull is already running. */
    public boolean dispatch(long workspaceId) {
        if (noOp) {
            return false;
        }
        if (activePulls.putIfAbsent(workspaceId, Boolean.TRUE) != null) {
            return false;
        }
        try {
            Persistence.WorkspaceView workspace = appStateService.loadAutoGitUpdateWorkspace(workspaceId);
            if (workspace == null) {
                throw new IllegalStateException("Workspace " + workspaceId + " could not be found");
            }
            executor.submit(() -> run(workspaceId, workspace));
            return true;
        } catch (Throwable failure) {
            activePulls.remove(workspaceId);
            log.error("Manual Git pull could not be queued for workspace {}", workspaceId, failure);
            systemBalloonService.publishError("Git Pull", "Git pull could not be started: " + message(failure));
            return false;
        }
    }

    private void run(long workspaceId, Persistence.WorkspaceView workspace) {
        try {
            GitAutoUpdateService.UpdateResult result = gitAutoUpdateService.updateWorkspaceManually(workspace.id());
            switch (result.status()) {
                case UPDATED -> systemBalloonService.publishSuccess("Git Pull", "Updated workspace \"" + workspace.name() + "\".");
                case UP_TO_DATE -> systemBalloonService.publishSuccess("Git Pull", "Workspace \"" + workspace.name() + "\" is already up to date.");
                case SKIPPED -> systemBalloonService.publishWarning("Git Pull", result.message());
                case FAILED -> systemBalloonService.publishError("Git Pull", result.message());
            }
        } catch (Throwable failure) {
            log.error("Unexpected manual Git pull failure for workspace {}", workspace.id(), failure);
            systemBalloonService.publishError("Git Pull", "Git pull failed: " + message(failure));
        } finally {
            activePulls.remove(workspaceId);
        }
    }

    private String message(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    @PreDestroy
    void shutdown() {
        if (!noOp) {
            executor.shutdown();
        }
    }
}

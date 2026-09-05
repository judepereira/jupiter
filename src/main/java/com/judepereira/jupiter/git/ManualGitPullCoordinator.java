package com.judepereira.jupiter.git;

import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.Persistence;
import com.judepereira.jupiter.ui.balloon.SystemBalloonService;
import jakarta.annotation.PreDestroy;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;

/** Owns the lifetime and deduplication of user-requested Git pulls. */
@Service
@Log4j2
public class ManualGitPullCoordinator {
    public enum DispatchResult {
        ACCEPTED,
        ALREADY_RUNNING,
        FAILED
    }

    private final AppStateService appStateService;
    private final GitAutoUpdateService gitAutoUpdateService;
    private final SystemBalloonService systemBalloonService;
    private final ExecutorService executor;
    private final ConcurrentMap<Long, Boolean> activePulls = new ConcurrentHashMap<>();

    public ManualGitPullCoordinator(AppStateService appStateService, GitAutoUpdateService gitAutoUpdateService,
                                    SystemBalloonService systemBalloonService,
                                    @Qualifier("manualGitPullExecutor") ExecutorService executor) {
        this.appStateService = appStateService;
        this.gitAutoUpdateService = gitAutoUpdateService;
        this.systemBalloonService = systemBalloonService;
        this.executor = executor;
    }

    public boolean isPulling(long workspaceId) {
        return activePulls.containsKey(workspaceId);
    }

    public DispatchResult dispatch(long workspaceId) {
        if (activePulls.putIfAbsent(workspaceId, Boolean.TRUE) != null) {
            return DispatchResult.ALREADY_RUNNING;
        }
        try {
            Persistence.WorkspaceView workspace = appStateService.loadAutoGitUpdateWorkspace(workspaceId);
            if (workspace == null) {
                throw new IllegalStateException("Workspace " + workspaceId + " could not be found");
            }
            executor.submit(() -> run(workspaceId, workspace));
            return DispatchResult.ACCEPTED;
        } catch (Throwable failure) {
            activePulls.remove(workspaceId);
            log.error("Manual Git pull could not be queued for workspace {}", workspaceId, failure);
            systemBalloonService.publishError("Git Pull", "Git pull could not be started: " + message(failure));
            return DispatchResult.FAILED;
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
        executor.shutdown();
    }
}

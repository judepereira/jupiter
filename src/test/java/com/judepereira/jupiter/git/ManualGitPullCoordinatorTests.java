package com.judepereira.jupiter.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.Persistence;
import com.judepereira.jupiter.ui.balloon.SystemBalloonService;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ManualGitPullCoordinatorTests {
    @Test
    void duplicateDispatchRunsGitOnceAndReturnsToIdle() {
        Fixture f = fixture();
        when(f.git.updateWorkspaceManually(7))
                .thenReturn(result(GitAutoUpdateService.UpdateResult.Status.UPDATED, "updated"));

        assertThat(f.coordinator.dispatch(7)).isEqualTo(ManualGitPullCoordinator.DispatchResult.ACCEPTED);
        assertThat(f.coordinator.dispatch(7)).isEqualTo(ManualGitPullCoordinator.DispatchResult.ALREADY_RUNNING);
        assertThat(f.coordinator.isPulling(7)).isTrue();
        f.executor.runNext();
        assertThat(f.coordinator.isPulling(7)).isFalse();
        verify(f.git).updateWorkspaceManually(7);
        verify(f.balloon).publishSuccess("Git Pull", "Updated workspace \"Alpha\".");
    }

    @Test
    void publishesTheStandardBalloonForEachOutcome() {
        for (var status : GitAutoUpdateService.UpdateResult.Status.values()) {
            Fixture f = fixture();
            String message = status == GitAutoUpdateService.UpdateResult.Status.UPDATED ? "changed" : "outcome";
            when(f.git.updateWorkspaceManually(7)).thenReturn(result(status, message));
            f.coordinator.dispatch(7);
            f.executor.runNext();
            switch (status) {
                case UPDATED -> verify(f.balloon).publishSuccess("Git Pull", "Updated workspace \"Alpha\".");
                case UP_TO_DATE ->
                    verify(f.balloon).publishSuccess("Git Pull", "Workspace \"Alpha\" is already up to date.");
                case SKIPPED -> verify(f.balloon).publishWarning("Git Pull", message);
                case FAILED -> verify(f.balloon).publishError("Git Pull", message);
            }
            assertThat(f.coordinator.isPulling(7)).isFalse();
        }
    }

    @Test
    void unexpectedWorkerFailureResetsStatusAndPublishesError() {
        Fixture f = fixture();
        when(f.git.updateWorkspaceManually(7)).thenThrow(new IllegalStateException("boom"));
        f.coordinator.dispatch(7);
        f.executor.runNext();
        assertThat(f.coordinator.isPulling(7)).isFalse();
        verify(f.balloon).publishError("Git Pull", "Git pull failed: boom");
    }

    @Test
    void dispatchLoadErrorReleasesActiveKeyAndPropagatesSameError() {
        Fixture f = fixture();
        AssertionError fatal = new AssertionError("load failed");
        doThrow(fatal).when(f.app).loadAutoGitUpdateWorkspace(7);

        assertThatThrownBy(() -> f.coordinator.dispatch(7)).isSameAs(fatal);
        assertThat(f.coordinator.isPulling(7)).isFalse();
        verifyNoInteractions(f.balloon);

        doReturn(new Persistence.WorkspaceView(7, "Alpha", "/repo", false, Persistence.RailStatus.NONE)).when(f.app)
                .loadAutoGitUpdateWorkspace(7);
        assertThat(f.coordinator.dispatch(7)).isEqualTo(ManualGitPullCoordinator.DispatchResult.ACCEPTED);
    }

    @Test
    void workerErrorIsCapturedBySubmittedFutureAndDedupKeyIsReleasedWithoutBalloon() throws Exception {
        Fixture f = fixture();
        AssertionError fatal = new AssertionError("worker failed");
        doThrow(fatal).when(f.git).updateWorkspaceManually(7);

        f.coordinator.dispatch(7);
        Future<?> future = f.executor.lastSubmitted;
        f.executor.runNext();

        assertThatThrownBy(future::get).isInstanceOf(ExecutionException.class).extracting(Throwable::getCause)
                .isSameAs(fatal);
        assertThat(f.coordinator.isPulling(7)).isFalse();
        assertThat(f.coordinator.dispatch(7)).isEqualTo(ManualGitPullCoordinator.DispatchResult.ACCEPTED);
        verifyNoInteractions(f.balloon);
    }

    @Test
    void nullWorkspaceResetsStatusAndPublishesErrorWithoutQueuingWork() {
        Fixture f = fixture();
        when(f.app.loadAutoGitUpdateWorkspace(7)).thenReturn(null);

        assertThat(f.coordinator.dispatch(7)).isEqualTo(ManualGitPullCoordinator.DispatchResult.FAILED);
        assertThat(f.coordinator.isPulling(7)).isFalse();
        verify(f.balloon).publishError(eq("Git Pull"), contains("Git pull could not be started"));
        assertThat(f.executor.tasks).isEmpty();
    }

    @Test
    void submissionFailureResetsStatusAndPublishesError() {
        Fixture f = fixture();
        f.executor.reject = true;
        assertThat(f.coordinator.dispatch(7)).isEqualTo(ManualGitPullCoordinator.DispatchResult.FAILED);
        assertThat(f.coordinator.isPulling(7)).isFalse();
        verify(f.balloon).publishError(eq("Git Pull"), contains("Git pull could not be started"));
        f.executor.reject = false;
        assertThat(f.coordinator.dispatch(7)).isEqualTo(ManualGitPullCoordinator.DispatchResult.ACCEPTED);
    }

    private static Fixture fixture() {
        AppStateService app = mock(AppStateService.class);
        when(app.loadAutoGitUpdateWorkspace(7))
                .thenReturn(new Persistence.WorkspaceView(7, "Alpha", "/repo", false, Persistence.RailStatus.NONE));
        GitAutoUpdateService git = mock(GitAutoUpdateService.class);
        SystemBalloonService balloon = mock(SystemBalloonService.class);
        QueuedExecutor executor = new QueuedExecutor();
        return new Fixture(new ManualGitPullCoordinator(app, git, balloon, executor), app, git, balloon, executor);
    }

    private static GitAutoUpdateService.UpdateResult result(GitAutoUpdateService.UpdateResult.Status status,
            String message) {
        return new GitAutoUpdateService.UpdateResult(status, "before", "after", message, false);
    }

    private record Fixture(ManualGitPullCoordinator coordinator, AppStateService app, GitAutoUpdateService git,
            SystemBalloonService balloon, QueuedExecutor executor) {
    }

    private static final class QueuedExecutor extends AbstractExecutorService {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private boolean shutdown;
        private boolean reject;
        private Future<?> lastSubmitted;
        @Override
        public void execute(Runnable command) {
            if (reject) {
                throw new IllegalStateException("rejected");
            }
            tasks.add(command);
        }
        @Override
        public Future<?> submit(Runnable task) {
            FutureTask<Void> future = new FutureTask<>(task, null);
            lastSubmitted = future;
            execute(future);
            return future;
        }
        @Override
        public void shutdown() {
            shutdown = true;
        }
        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> result = List.copyOf(tasks);
            tasks.clear();
            return result;
        }
        @Override
        public boolean isShutdown() {
            return shutdown;
        }
        @Override
        public boolean isTerminated() {
            return shutdown && tasks.isEmpty();
        }
        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }
        void runNext() {
            tasks.remove().run();
        }
    }
}

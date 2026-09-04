package com.judepereira.jupiter.lifecycle;

import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.Persistence.LifecycleHookSettings;
import com.judepereira.jupiter.persistence.TestAppStateSupport;
import com.judepereira.jupiter.ui.balloon.SystemBalloonService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class LifecycleHookServiceTests {

    @Test
    void runsMultilineBashInTmpWithProjectAndReservedEnvironment(@TempDir Path tempDir) throws Exception {
        AppStateService appStateService = TestAppStateSupport.appStateService();
        var projectPath = Files.createTempDirectory(tempDir, "project-");
        appStateService.addOrReopenProject("Persisted Project", projectPath.toString());
        long sessionId = appStateService.loadViewData().activeSession().id();
        appStateService.updateProjectEnvironmentVariables(appStateService.loadSessionProjectId(sessionId), List.of(
                new com.judepereira.jupiter.persistence.Persistence.ProjectEnvironmentVariable("JUPITER_PROJECT_NAME", "project override"),
                new com.judepereira.jupiter.persistence.Persistence.ProjectEnvironmentVariable("JUPITER_WORKSPACE_NAME", "workspace override"),
                new com.judepereira.jupiter.persistence.Persistence.ProjectEnvironmentVariable("JUPITER_SESSION_NAME", "session override"),
                new com.judepereira.jupiter.persistence.Persistence.ProjectEnvironmentVariable("JUPITER_CUSTOM", "custom value")));
        Path output = tempDir.resolve("hook-output");
        SystemBalloonService balloons = mock(SystemBalloonService.class);
        LifecycleHookService service = service(appStateService, balloons, tempDir);
        try {
            appStateService.updateLifecycleHookSettings(new LifecycleHookSettings("""
                    printf '%%s\\n' "$JUPITER_PROJECT_NAME" > '%s'
                    printf '%%s\\n' "$JUPITER_WORKSPACE_NAME" >> '%s'
                    printf '%%s\\n' "$JUPITER_SESSION_NAME" >> '%s'
                    printf '%%s\\n' "$JUPITER_CUSTOM" >> '%s'
                    printf '%%s\\n' "$PATH" >> '%s'
                    pwd >> '%s'
                    """.formatted(output, output, output, output, output, output, output), null, null, 30));

            var result = service.dispatch(LifecycleHookService.LifecycleEvent.ASSISTANT_COMPLETED, sessionId)
                    .get(5, TimeUnit.SECONDS);

            assertThat(result.status()).isEqualTo(LifecycleHookService.HookStatus.SUCCEEDED);
            assertThat(Files.readAllLines(output)).contains("Persisted Project", "Default Workspace", "Session #1", "custom value", "/tmp");
            assertThat(Files.readAllLines(output).get(4)).isNotBlank();
            assertThat(Files.list(tempDir).filter(path -> path.getFileName().toString().startsWith(".jupiter-lifecycle-")).toList()).isEmpty();
        } finally {
            service.shutdown();
        }
    }

    @Test
    void blankScriptDoesNotResolveSessionOrLaunch(@TempDir Path tempDir) throws Exception {
        AppStateService appStateService = TestAppStateSupport.appStateService();
        appStateService.updateLifecycleHookSettings(new LifecycleHookSettings(" \n ", null, null, 30));
        SystemBalloonService balloons = mock(SystemBalloonService.class);
        LifecycleHookService service = service(appStateService, balloons, tempDir);
        try {
            var result = service.dispatch(LifecycleHookService.LifecycleEvent.ASSISTANT_COMPLETED, 999999).get(1, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(LifecycleHookService.HookStatus.SKIPPED);
            verifyNoInteractions(balloons);
        } finally {
            service.shutdown();
        }
    }

    @Test
    void reportsNonZeroExit(@TempDir Path tempDir) throws Exception {
        AppStateService appStateService = configuredAppState(tempDir, "exit 7");
        long sessionId = appStateService.loadViewData().activeSession().id();
        SystemBalloonService balloons = mock(SystemBalloonService.class);
        LifecycleHookService service = service(appStateService, balloons, tempDir);
        try {
            var result = service.dispatch(LifecycleHookService.LifecycleEvent.ASSISTANT_ERRORED, sessionId).get(5, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(LifecycleHookService.HookStatus.NON_ZERO_EXIT);
            assertThat(result.exitCode()).isEqualTo(7);
            verify(balloons).publishError("Lifecycle action failed", "The configured lifecycle action failed for session " + sessionId + " (returned a non-zero exit code).");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void reportsInjectedLaunchFailureAndDeletesScript(@TempDir Path tempDir) throws Exception {
        AppStateService appStateService = configuredAppState(tempDir, "echo should-not-run");
        long sessionId = appStateService.loadViewData().activeSession().id();
        SystemBalloonService balloons = mock(SystemBalloonService.class);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        LifecycleHookService service = new LifecycleHookService(appStateService, balloons,
                new LifecycleHookRuntime(executor, ignored -> { throw new IOException("test launch failure"); }, tempDir));
        try {
            var result = service.dispatch(LifecycleHookService.LifecycleEvent.ASSISTANT_ERRORED, sessionId).get(5, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(LifecycleHookService.HookStatus.LAUNCH_FAILED);
            assertThat(Files.list(tempDir).filter(path -> path.getFileName().toString().startsWith(".jupiter-lifecycle-")).toList()).isEmpty();
            verify(balloons).publishError("Lifecycle action failed", "The configured lifecycle action failed for session " + sessionId + " (could not be started).");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void terminatesTimedOutProcessAndChild(@TempDir Path tempDir) throws Exception {
        Path childPid = tempDir.resolve("child-pid");
        AppStateService appStateService = configuredAppState(tempDir, "sleep 30 & child=$!; echo $child > '" + childPid + "'; wait $child");
        appStateService.updateLifecycleHookSettings(new LifecycleHookSettings("", "", "sleep 30 & child=$!; echo $child > '" + childPid + "'; wait $child", 1));
        long sessionId = appStateService.loadViewData().activeSession().id();
        SystemBalloonService balloons = mock(SystemBalloonService.class);
        LifecycleHookService service = service(appStateService, balloons, tempDir);
        try {
            var result = service.dispatch(LifecycleHookService.LifecycleEvent.SUBAGENT_COMPLETED, sessionId).get(5, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(LifecycleHookService.HookStatus.TIMED_OUT);
            long childPidValue = Long.parseLong(Files.readString(childPid).trim());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < deadline && ProcessHandle.of(childPidValue).map(ProcessHandle::isAlive).orElse(false)) {
                Thread.sleep(25);
            }
            assertThat(ProcessHandle.of(childPidValue).map(ProcessHandle::isAlive).orElse(false)).isFalse();
            verify(balloons).publishError("Lifecycle action failed", "The configured lifecycle action failed for session " + sessionId + " (timed out).");
        } finally {
            service.shutdown();
        }
    }

    private static AppStateService configuredAppState(Path tempDir, String script) throws IOException {
        AppStateService appStateService = TestAppStateSupport.appStateService();
        appStateService.addOrReopenProject("Project", Files.createTempDirectory(tempDir, "project-").toString());
        appStateService.updateLifecycleHookSettings(new LifecycleHookSettings(null, script, null, 30));
        return appStateService;
    }

    private static LifecycleHookService service(AppStateService appStateService, SystemBalloonService balloons, Path tempDir) {
        return new LifecycleHookService(appStateService, balloons,
                new LifecycleHookRuntime(Executors.newVirtualThreadPerTaskExecutor(), LifecycleHookService::startProcess, tempDir));
    }
}

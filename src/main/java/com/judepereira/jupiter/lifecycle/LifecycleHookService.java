package com.judepereira.jupiter.lifecycle;

import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.Persistence;
import com.judepereira.jupiter.ui.balloon.SystemBalloonService;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Executes one explicitly selected, persisted lifecycle action without changing persisted lifecycle state. */
@Log4j2
@Service
public class LifecycleHookService {

    private static final Path TEMP_DIRECTORY = Path.of("/tmp");
    private static final int MAX_DIAGNOSTIC_OUTPUT_BYTES = 16 * 1024;
    private static final long TERMINATION_GRACE_MILLIS = 250;

    private final AppStateService appStateService;
    private final SystemBalloonService systemBalloonService;
    private final ExecutorService executor;
    private final ProcessLauncher processLauncher;
    private final Path tempDirectory;
    private final Object lifecycleLock = new Object();
    private final Map<Process, Boolean> activeProcesses = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<HookExecutionResult>> activeTasks = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean shutdownStarted = new AtomicBoolean(false);

    @Autowired
    public LifecycleHookService(AppStateService appStateService, SystemBalloonService systemBalloonService) {
        this(appStateService, systemBalloonService, Executors.newVirtualThreadPerTaskExecutor(),
                LifecycleHookService::startProcess, TEMP_DIRECTORY);
    }

    LifecycleHookService(AppStateService appStateService, SystemBalloonService systemBalloonService,
                         ExecutorService executor, ProcessLauncher processLauncher, Path tempDirectory) {
        this.appStateService = appStateService;
        this.systemBalloonService = systemBalloonService;
        this.executor = executor;
        this.processLauncher = processLauncher;
        this.tempDirectory = tempDirectory;
    }

    /** Queues the selected action and returns a future useful to callers that need to observe its outcome. */
    public CompletableFuture<HookExecutionResult> dispatch(LifecycleEvent event, long sessionId) {
        if (event == null) {
            throw new IllegalArgumentException("Lifecycle event is required");
        }

        Persistence.LifecycleHookSettings settings = appStateService.loadLifecycleHookSettings();
        String script = event.scriptFrom(settings);
        if (script == null || script.isBlank()) {
            return CompletableFuture.completedFuture(new HookExecutionResult(event, HookStatus.SKIPPED, null, 0, 0));
        }

        Persistence.LifecycleHookContext context = appStateService.loadLifecycleHookContext(sessionId);
        CompletableFuture<HookExecutionResult> result = new CompletableFuture<>();
        synchronized (lifecycleLock) {
            if (shutdownStarted.get()) {
                result.complete(new HookExecutionResult(event, HookStatus.CANCELLED, null, 0, 0));
                return result;
            }
            activeTasks.add(result);
            result.whenComplete((ignored, failure) -> activeTasks.remove(result));
            try {
                executor.submit(() -> {
                    try {
                        result.complete(run(event, script, context, settings.timeoutSeconds()));
                    } catch (Throwable failure) {
                        log.error("Lifecycle action failed: event={}, sessionId={}, reason={}", event, sessionId,
                                failure.getClass().getSimpleName());
                        systemBalloonService.publishError("Lifecycle action failed",
                                "The configured lifecycle action could not be executed for session " + sessionId + ".");
                        result.complete(new HookExecutionResult(event, HookStatus.LAUNCH_FAILED, null, 0, 0));
                    }
                });
            } catch (RuntimeException failure) {
                activeTasks.remove(result);
                log.error("Lifecycle action could not be queued: event={}, sessionId={}, reason={}", event, sessionId,
                        failure.getClass().getSimpleName());
                systemBalloonService.publishError("Lifecycle action failed",
                        "The configured lifecycle action could not be executed for session " + sessionId + ".");
                result.complete(new HookExecutionResult(event, HookStatus.LAUNCH_FAILED, null, 0, 0));
            }
        }
        return result;
    }

    private HookExecutionResult run(LifecycleEvent event, String script, Persistence.LifecycleHookContext context,
                                    int timeoutSeconds) {
        Path scriptFile = null;
        Process process = null;
        OutputCapture stdout = new OutputCapture(MAX_DIAGNOSTIC_OUTPUT_BYTES);
        OutputCapture stderr = new OutputCapture(MAX_DIAGNOSTIC_OUTPUT_BYTES);
        Thread stdoutReader = null;
        Thread stderrReader = null;
        try {
            scriptFile = Files.createTempFile(tempDirectory, ".jupiter-lifecycle-", ".sh");
            Files.writeString(scriptFile, script, StandardCharsets.UTF_8);
            restrictFile(scriptFile);

            ProcessLaunchRequest request = new ProcessLaunchRequest(scriptFile, environment(context));
            synchronized (lifecycleLock) {
                if (shutdownStarted.get()) {
                    return new HookExecutionResult(event, HookStatus.CANCELLED, null, 0, 0);
                }
                process = processLauncher.launch(request);
                activeProcesses.put(process, Boolean.TRUE);
            }

            Process runningProcess = process;
            stdoutReader = Thread.ofVirtual().name("lifecycle-hook-stdout").start(() -> readOutput(runningProcess.getInputStream(), stdout));
            stderrReader = Thread.ofVirtual().name("lifecycle-hook-stderr").start(() -> readOutput(runningProcess.getErrorStream(), stderr));

            boolean finished;
            try {
                finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                terminate(process);
                Thread.currentThread().interrupt();
                return new HookExecutionResult(event, HookStatus.CANCELLED, null, stdout.size(), stderr.size());
            }
            if (!finished) {
                terminate(process);
                joinReader(stdoutReader);
                joinReader(stderrReader);
                reportFailure(event, context.sessionId(), HookStatus.TIMED_OUT, null, stdout, stderr);
                return new HookExecutionResult(event, HookStatus.TIMED_OUT, null, stdout.size(), stderr.size());
            }

            joinReader(stdoutReader);
            joinReader(stderrReader);
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                reportFailure(event, context.sessionId(), HookStatus.NON_ZERO_EXIT, exitCode, stdout, stderr);
                return new HookExecutionResult(event, HookStatus.NON_ZERO_EXIT, exitCode, stdout.size(), stderr.size());
            }
            return new HookExecutionResult(event, HookStatus.SUCCEEDED, exitCode, 0, 0);
        } catch (Exception failure) {
            reportFailure(event, context.sessionId(), HookStatus.LAUNCH_FAILED, null, stdout, stderr);
            return new HookExecutionResult(event, HookStatus.LAUNCH_FAILED, null, stdout.size(), stderr.size());
        } finally {
            if (process != null) {
                if (process.isAlive()) {
                    terminate(process);
                }
                activeProcesses.remove(process);
            }
            if (scriptFile != null) {
                try {
                    Files.deleteIfExists(scriptFile);
                } catch (IOException e) {
                    log.error("Failed to delete lifecycle action temporary file");
                }
            }
        }
    }

    private Map<String, String> environment(Persistence.LifecycleHookContext context) {
        Map<String, String> environment = new HashMap<>(context.projectEnvironmentVariables());
        environment.put("JUPITER_PROJECT_NAME", context.projectName());
        environment.put("JUPITER_WORKSPACE_NAME", context.workspaceName());
        environment.put("JUPITER_SESSION_NAME", context.sessionName());
        return environment;
    }

    private void reportFailure(LifecycleEvent event, long sessionId, HookStatus status, Integer exitCode,
                               OutputCapture stdout, OutputCapture stderr) {
        log.error("Lifecycle action failed: event={}, sessionId={}, status={}, exitCode={}, stdoutBytes={}, stderrBytes={}",
                event, sessionId, status, exitCode, stdout.size(), stderr.size());
        systemBalloonService.publishError("Lifecycle action failed",
                "The configured lifecycle action failed for session " + sessionId + " (" + status.displayName + ").");
    }

    static Process startProcess(ProcessLaunchRequest request) throws IOException {
        ProcessBuilder builder = new ProcessBuilder("setsid", "/bin/bash", request.scriptFile().toString());
        builder.directory(TEMP_DIRECTORY.toFile());
        builder.environment().putAll(request.environment());
        return builder.start();
    }

    private void terminate(Process process) {
        if (!process.isAlive()) {
            return;
        }
        long pid = process.pid();
        signalProcessGroup("TERM", pid);
        destroyDescendants(process, false);
        waitBriefly(process);
        signalProcessGroup("KILL", pid);
        destroyDescendants(process, true);
        process.destroyForcibly();
        waitBriefly(process);
    }

    private void signalProcessGroup(String signal, long pid) {
        try {
            Process signalProcess = new ProcessBuilder("/bin/kill", "-" + signal, "-" + pid).start();
            signalProcess.waitFor(TERMINATION_GRACE_MILLIS, TimeUnit.MILLISECONDS);
            signalProcess.destroyForcibly();
        } catch (Exception ignored) {
            // ProcessHandle destruction below is still useful when kill is unavailable.
        }
    }

    private void destroyDescendants(Process process, boolean forcibly) {
        List<ProcessHandle> descendants = process.toHandle().descendants().toList();
        descendants.reversed().forEach(handle -> {
            if (forcibly) {
                handle.destroyForcibly();
            } else {
                handle.destroy();
            }
        });
    }

    private void waitBriefly(Process process) {
        try {
            process.waitFor(TERMINATION_GRACE_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void readOutput(InputStream input, OutputCapture output) {
        try (input) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.append(buffer, read);
            }
        } catch (IOException ignored) {
            // The process may close its streams while it is being terminated.
        }
    }

    private void joinReader(Thread reader) {
        if (reader == null) {
            return;
        }
        try {
            reader.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void restrictFile(Path file) {
        try {
            Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // The temp directory normally supports POSIX permissions; the file remains securely created by the OS.
        } catch (IOException e) {
            throw new IllegalStateException("Failed to secure lifecycle action temporary file", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        synchronized (lifecycleLock) {
            if (!shutdownStarted.compareAndSet(false, true)) {
                return;
            }
            executor.shutdownNow();
        }
        activeProcesses.keySet().forEach(this::terminate);
        activeTasks.forEach(task -> task.complete(new HookExecutionResult(null, HookStatus.CANCELLED, null, 0, 0)));
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        activeProcesses.keySet().forEach(this::terminate);
    }

    @FunctionalInterface
    interface ProcessLauncher {
        Process launch(ProcessLaunchRequest request) throws Exception;
    }

    record ProcessLaunchRequest(Path scriptFile, Map<String, String> environment) {
    }

    public enum LifecycleEvent {
        ASSISTANT_COMPLETED {
            @Override
            String scriptFrom(Persistence.LifecycleHookSettings settings) {
                return settings.assistantCompletedScript();
            }
        },
        ASSISTANT_ERRORED {
            @Override
            String scriptFrom(Persistence.LifecycleHookSettings settings) {
                return settings.assistantErroredScript();
            }
        },
        SUBAGENT_COMPLETED {
            @Override
            String scriptFrom(Persistence.LifecycleHookSettings settings) {
                return settings.subagentCompletedScript();
            }
        };

        abstract String scriptFrom(Persistence.LifecycleHookSettings settings);
    }

    public enum HookStatus {
        SKIPPED("skipped"),
        SUCCEEDED("succeeded"),
        NON_ZERO_EXIT("returned a non-zero exit code"),
        TIMED_OUT("timed out"),
        LAUNCH_FAILED("could not be started"),
        CANCELLED("cancelled");

        private final String displayName;

        HookStatus(String displayName) {
            this.displayName = displayName;
        }
    }

    public record HookExecutionResult(LifecycleEvent event, HookStatus status, Integer exitCode,
                                      int stdoutBytes, int stderrBytes) {
    }

    private static final class OutputCapture {
        private final int limit;
        private int size;
        private int retained;

        private OutputCapture(int limit) {
            this.limit = limit;
        }

        private synchronized void append(byte[] bytes, int length) {
            size = Math.min(Integer.MAX_VALUE - length, size) + length;
            retained = Math.min(limit, retained + length);
        }

        private synchronized int size() {
            return size;
        }
    }
}

package com.judepereira.jupiter.terminal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.security.ProcessEnvironmentSanitizer;
import com.judepereira.jupiter.security.RuntimeEnvironment;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@Log4j2
@Service
public class TerminalManager {

    private static final int OUTPUT_REPLAY_CAP = 200_000;
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(2);

    private final ObjectMapper objectMapper;
    private final List<TerminalLifecycleListener> lifecycleListeners;
    private final RuntimeEnvironment runtimeEnvironment;
    private final ConcurrentMap<String, TerminalRuntime> terminals = new ConcurrentHashMap<>();
    private final AtomicInteger terminalSequence = new AtomicInteger(1);

    public TerminalManager(ObjectMapper objectMapper, List<TerminalLifecycleListener> lifecycleListeners,
            RuntimeEnvironment runtimeEnvironment) {
        this.objectMapper = objectMapper;
        this.lifecycleListeners = lifecycleListeners;
        this.runtimeEnvironment = runtimeEnvironment;
    }

    public TerminalHandle createTerminal(String workspaceRoot, Map<String, String> environmentVariables) {
        return createTerminal(workspaceRoot, "Terminal " + terminalSequence.getAndIncrement(), environmentVariables);
    }

    public TerminalHandle createTerminal(String workspaceRoot, String title,
            Map<String, String> projectEnvironmentVariables) {
        String terminalId = UUID.randomUUID().toString();
        PtyProcess process = startProcess(workspaceRoot, projectEnvironmentVariables);
        final TerminalRuntime runtime;
        try {
            runtime = new TerminalRuntime(terminalId, title, process);
        } catch (RuntimeException e) {
            try {
                process.destroy();
            } catch (Exception cleanupFailure) {
                log.warn("Failed to destroy terminal process after setup failure", cleanupFailure);
            }
            throw e;
        }
        terminals.put(terminalId, runtime);
        runtime.startReader();
        return new TerminalHandle(terminalId, title);
    }

    public void attach(String terminalId, WebSocketSession session) {
        runtime(terminalId).attach(session);
    }

    public void detach(String terminalId, WebSocketSession session) {
        TerminalRuntime runtime = terminals.get(terminalId);
        if (runtime != null) {
            runtime.detach(session);
        }
    }

    public void write(String terminalId, String data) {
        runtime(terminalId).write(data);
    }

    public void resize(String terminalId, int cols, int rows) {
        runtime(terminalId).resize(cols, rows);
    }

    public boolean hasTerminal(String terminalId) {
        return terminals.containsKey(terminalId);
    }

    void registerRuntime(String terminalId, TerminalRuntime runtime) {
        terminals.put(terminalId, runtime);
    }

    public void closeTerminal(String terminalId) {
        TerminalRuntime runtime = terminals.get(terminalId);
        if (runtime == null) {
            return;
        }
        runtime.closeProcess();
    }

    @PreDestroy
    public void closeAll() {
        for (String terminalId : List.copyOf(terminals.keySet())) {
            closeTerminal(terminalId);
        }
    }

    private PtyProcess startProcess(String workspaceRoot, Map<String, String> environmentVariables) {
        try {
            String shell = Optional.ofNullable(System.getenv("SHELL")).filter(value -> !value.isBlank())
                    .orElse("/bin/bash");
            Map<String, String> env = terminalEnvironment(environmentVariables, runtimeEnvironment);
            env.put("TERM", "xterm-256color");
            return startProcess(workspaceRoot, new String[]{shell, "-l"}, env);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start terminal", e);
        }
    }

    static PtyProcess startProcess(String workspaceRoot, String[] command, Map<String, String> environment)
            throws IOException {
        ProcessEnvironmentSanitizer.sanitizeTrustedTerminal(environment);
        return new PtyProcessBuilder(command).setEnvironment(environment)
                .setDirectory(Path.of(workspaceRoot).toAbsolutePath().normalize().toString()).setConsole(false)
                .setRedirectErrorStream(true).setInitialColumns(120).setInitialRows(32).start();
    }

    static Map<String, String> terminalEnvironment(Map<String, String> projectEnvironmentVariables,
            RuntimeEnvironment runtimeEnvironment) {
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.putAll(runtimeEnvironment.asMap());
        environment.putAll(projectEnvironmentVariables);
        ProcessEnvironmentSanitizer.sanitizeTrustedTerminal(environment);
        return environment;
    }

    private TerminalRuntime runtime(String terminalId) {
        TerminalRuntime runtime = terminals.get(terminalId);
        if (runtime == null) {
            throw new IllegalStateException("Unknown terminal: " + terminalId);
        }
        return runtime;
    }

    private void notifyExited(String terminalId, int exitCode) {
        for (TerminalLifecycleListener lifecycleListener : lifecycleListeners) {
            lifecycleListener.onTerminalExited(terminalId, exitCode);
        }
    }

    private void sendJson(WebSocketSession session, Object payload) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
            }
        } catch (Exception e) {
            log.error("Failed to send websocket payload", e);
        }
    }

    public interface TerminalLifecycleListener {
        void onTerminalExited(String terminalId, int exitCode);
    }

    final class TerminalRuntime {
        private final String terminalId;
        private final String title;
        private final PtyProcess process;
        private final InputStream processInput;
        private final OutputStream processOutput;
        private final CopyOnWriteArraySet<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
        private final AtomicBoolean cleanedUp = new AtomicBoolean(false);
        private final AtomicBoolean inputClosed = new AtomicBoolean(false);
        private final AtomicBoolean outputClosed = new AtomicBoolean(false);
        private final CompletableFuture<Void> readerCompletion = new CompletableFuture<>();
        private final Object outputLock = new Object();
        private final Object processInputLock = new Object();
        private final Object processOutputLock = new Object();
        private final StringBuilder outputBuffer = new StringBuilder();

        TerminalRuntime(String terminalId, String title, PtyProcess process) {
            this.terminalId = terminalId;
            this.title = title;
            this.process = process;
            try {
                this.processInput = process.getInputStream();
                this.processOutput = process.getOutputStream();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to acquire terminal streams", e);
            }
        }

        void startReader() {
            Thread.ofVirtual().name("terminal-reader-" + terminalId).start(this::pumpOutput);
        }

        private void pumpOutput() {
            int exitCode = -1;
            try {
                var reader = new InputStreamReader(processInput, StandardCharsets.UTF_8);
                char[] buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    String chunk = new String(buffer, 0, read);
                    synchronized (outputLock) {
                        if (cleanedUp.get()) {
                            continue;
                        }
                        appendOutput(chunk);
                        sendToSessions(Map.of("type", "output", "data", chunk));
                    }
                }
                exitCode = process.waitFor();
            } catch (Exception e) {
                log.error("Terminal {} failed", terminalId, e);
                synchronized (outputLock) {
                    sendToSessions(Map.of("type", "error", "message",
                            e.getMessage() == null ? "terminal_error" : e.getMessage()));
                }
            } finally {
                try {
                    cleanup(exitCode);
                } catch (RuntimeException | Error e) {
                    readerCompletion.completeExceptionally(e);
                    throw e;
                } finally {
                    readerCompletion.complete(null);
                }
            }
        }

        void attach(WebSocketSession session) {
            synchronized (outputLock) {
                sessions.add(session);
                if (outputBuffer.length() > 0) {
                    sendJson(session, Map.of("type", "output", "data", outputBuffer.toString()));
                }
            }
        }

        private void detach(WebSocketSession session) {
            sessions.remove(session);
        }

        private void write(String data) {
            if (data == null) {
                return;
            }
            try {
                synchronized (processOutputLock) {
                    if (outputClosed.get()) {
                        throw new IOException("Terminal input is closed");
                    }
                    processOutput.write(data.getBytes(StandardCharsets.UTF_8));
                    processOutput.flush();
                }
            } catch (Exception e) {
                throw new IllegalStateException("Failed to write to terminal", e);
            }
        }

        private void resize(int cols, int rows) {
            try {
                process.setWinSize(new WinSize(cols, rows));
            } catch (Exception e) {
                throw new IllegalStateException("Failed to resize terminal", e);
            }
        }

        private void closeProcess() {
            closeProcess(CLOSE_WAIT);
        }

        void closeProcess(Duration waitDuration) {
            RuntimeException destroyFailure = null;
            try {
                process.destroy();
            } catch (RuntimeException e) {
                destroyFailure = e;
            }

            boolean interrupted = false;
            try {
                readerCompletion.get(waitDuration.toNanos(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                // Closing the input unblocks the reader before fallback cleanup removes the
                // runtime.
                cleanup(-1);
            } catch (InterruptedException e) {
                interrupted = true;
                cleanup(-1);
            } catch (Exception e) {
                // The reader's failure is reported by pumpOutput; fallback cleanup remains
                // required.
                cleanup(-1);
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            if (destroyFailure != null) {
                throw destroyFailure;
            }
        }

        private void sendToSessions(Map<String, Object> payload) {
            for (WebSocketSession session : sessions) {
                if (!session.isOpen()) {
                    sessions.remove(session);
                    continue;
                }
                try {
                    synchronized (session) {
                        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
                    }
                } catch (Exception e) {
                    log.error("Failed to stream terminal output", e);
                }
            }
        }

        void appendOutput(String chunk) {
            outputBuffer.append(chunk);
            int overflow = outputBuffer.length() - OUTPUT_REPLAY_CAP;
            if (overflow > 0) {
                outputBuffer.delete(0, overflow);
            }
        }

        private void cleanup(int exitCode) {
            if (!cleanedUp.compareAndSet(false, true)) {
                return;
            }
            terminals.remove(terminalId);
            closeProcessInput();
            closeProcessOutput();
            synchronized (outputLock) {
                sendToSessions(Map.of("type", "exit", "code", exitCode));
            }
            try {
                notifyExited(terminalId, exitCode);
            } catch (Exception e) {
                log.error("Terminal {} lifecycle listener failed", terminalId, e);
            } finally {
                for (WebSocketSession session : sessions) {
                    try {
                        if (session.isOpen()) {
                            session.close();
                        }
                    } catch (Exception e) {
                        log.error("Failed to close websocket session", e);
                    }
                }
                sessions.clear();
            }
        }

        private void closeProcessInput() {
            synchronized (processInputLock) {
                if (!inputClosed.compareAndSet(false, true)) {
                    return;
                }
                try {
                    processInput.close();
                } catch (Exception e) {
                    log.error("Failed to close terminal output for {}", terminalId, e);
                }
            }
        }

        private void closeProcessOutput() {
            synchronized (processOutputLock) {
                if (!outputClosed.compareAndSet(false, true)) {
                    return;
                }
                try {
                    processOutput.close();
                } catch (Exception e) {
                    log.error("Failed to close terminal input for {}", terminalId, e);
                }
            }
        }
    }
}

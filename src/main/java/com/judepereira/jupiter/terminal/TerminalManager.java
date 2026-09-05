package com.judepereira.jupiter.terminal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import jakarta.annotation.PreDestroy;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Log4j2
@Service
public class TerminalManager {

    private static final int OUTPUT_REPLAY_CAP = 200_000;

    private final ObjectMapper objectMapper;
    private final List<TerminalLifecycleListener> lifecycleListeners;
    private final ConcurrentMap<String, TerminalRuntime> terminals = new ConcurrentHashMap<>();
    private final AtomicInteger terminalSequence = new AtomicInteger(1);

    public TerminalManager(ObjectMapper objectMapper, List<TerminalLifecycleListener> lifecycleListeners) {
        this.objectMapper = objectMapper;
        this.lifecycleListeners = lifecycleListeners;
    }

    public TerminalHandle createTerminal(String workspaceRoot, Map<String, String> environmentVariables) {
        return createTerminal(workspaceRoot, "Terminal " + terminalSequence.getAndIncrement(), environmentVariables);
    }

    public TerminalHandle createTerminal(String workspaceRoot, String title, Map<String, String> projectEnvironmentVariables) {
        String terminalId = UUID.randomUUID().toString();
        PtyProcess process = startProcess(workspaceRoot, projectEnvironmentVariables);
        TerminalRuntime runtime = new TerminalRuntime(terminalId, title, process);
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
            String shell = Optional.ofNullable(System.getenv("SHELL")).filter(value -> !value.isBlank()).orElse("/bin/bash");
            Map<String, String> env = terminalEnvironment(environmentVariables);
            env.put("TERM", "xterm-256color");
            return new PtyProcessBuilder(new String[]{shell, "-l"}) // Force a login shell.
                    .setEnvironment(env)
                    .setDirectory(Path.of(workspaceRoot).toAbsolutePath().normalize().toString())
                    .setConsole(false)
                    .setRedirectErrorStream(true)
                    .setInitialColumns(120)
                    .setInitialRows(32)
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start terminal", e);
        }
    }

    static Map<String, String> terminalEnvironment(Map<String, String> projectEnvironmentVariables) {
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.putAll(projectEnvironmentVariables);
        environment.remove("JUPITER_HTTP_AUTH_PASSWORD");
        environment.remove("JUPITER_HTTP_AUTH_USERNAME");
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

    private final class TerminalRuntime {
        private final String terminalId;
        private final String title;
        private final PtyProcess process;
        private final CopyOnWriteArraySet<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
        private final AtomicBoolean cleanedUp = new AtomicBoolean(false);
        private final Object outputLock = new Object();
        private final StringBuilder outputBuffer = new StringBuilder();

        private TerminalRuntime(String terminalId, String title, PtyProcess process) {
            this.terminalId = terminalId;
            this.title = title;
            this.process = process;
        }

        private void startReader() {
            Thread.ofVirtual().name("terminal-reader-" + terminalId).start(this::pumpOutput);
        }

        private void pumpOutput() {
            int exitCode = -1;
            try (var reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                char[] buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    String chunk = new String(buffer, 0, read);
                    synchronized (outputLock) {
                        appendOutput(chunk);
                        sendToSessions(Map.of("type", "output", "data", chunk));
                    }
                }
                exitCode = process.waitFor();
            } catch (Exception e) {
                log.error("Terminal {} failed", terminalId, e);
                synchronized (outputLock) {
                    sendToSessions(Map.of("type", "error", "message", e.getMessage() == null ? "terminal_error" : e.getMessage()));
                }
            } finally {
                cleanup(exitCode);
            }
        }

        private void attach(WebSocketSession session) {
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
                OutputStream outputStream = process.getOutputStream();
                outputStream.write(data.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
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
            terminals.remove(terminalId);
            process.destroy();
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

        private void appendOutput(String chunk) {
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
            synchronized (outputLock) {
                terminals.remove(terminalId);
                sendToSessions(Map.of("type", "exit", "code", exitCode));
                notifyExited(terminalId, exitCode);
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
    }
}

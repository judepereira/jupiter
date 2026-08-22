package com.judepereira.jupiter.agent.tools.impl;

import com.judepereira.jupiter.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter.agent.llm.dto.ToolSchema;
import com.judepereira.jupiter.agent.tools.*;

import com.judepereira.jupiter.agent.harness.StreamCancelledException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.judepereira.jupiter.agent.llm.dto.ToolParameter.string;

public class RunCommandTool implements AgentTool {
    private final List<String> forbidden = List.of("rm -rf /", "shutdown", "reboot", "mkfs", ":(){ :|:& };:");
    private static final ToolDefinition DEF = new ToolDefinition(
            "run_command",
            "Run a shell command in workspace (restricted)",
            ToolSchema.object(
                    string("command", "shell command to run"),
                    string("workingDir", "optional relative working directory")
            ).required("command")
    );

    @Override
    public String name() {
        return "run_command";
    }

    @Override
    public ToolDefinition definition() { return DEF; }

    @Override
    public ToolExecutionResult execute(Map<String, Object> args, ToolExecutionContext context) throws Exception {
        if (!context.isAllowCommand()) {
            return new ToolExecutionResult(false, "command execution disabled by configuration", Map.of());
        }
        String cmd = (String) args.get("command");
        String working = (String) args.getOrDefault("workingDir", "");
        if (cmd == null) {
            return new ToolExecutionResult(false, "missing command", Map.of());
        }
        for (String f : forbidden) {
            if (cmd.contains(f)) {
                return new ToolExecutionResult(false, "command denied by safety policy", Map.of());
            }
        }
        Path wd = FileUtils.resolveWorkspacePath(context.getWorkspaceRoot(), working);
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", cmd);
        pb.directory(wd.toFile());
        pb.environment().putAll(context.getEnvironmentVariables());
        Process p = pb.start();
        // drain stdout and stderr concurrently to avoid blocking due to pipe buffers
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        Thread tOut = new Thread(() -> {
            try (var is = p.getInputStream(); var ir = new InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8);
                 var br = new BufferedReader(ir)) {
                br.lines().forEach(l -> out.append(l).append('\n'));
            } catch (Exception e) {
                // ignore
            }
        });
        Thread tErr = new Thread(() -> {
            try (var is = p.getErrorStream(); var ir = new InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8);
                 var br = new BufferedReader(ir)) {
                br.lines().forEach(l -> err.append(l).append('\n'));
            } catch (Exception e) {
                // ignore
            }
        });
        tOut.start();
        tErr.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(context.getCommandTimeoutSeconds());
        while (true) {
            if (context.getCancellationToken() != null && context.getCancellationToken().isCancelled()) {
                p.destroyForcibly();
                try {
                    tOut.join(200);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                try {
                    tErr.join(200);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                throw new StreamCancelledException();
            }
            if (p.waitFor(100, TimeUnit.MILLISECONDS)) {
                break;
            }
            if (System.nanoTime() >= deadline) {
                p.destroyForcibly();
                try {
                    tOut.join(200);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                try {
                    tErr.join(200);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return new ToolExecutionResult(false, "command timed out", Map.of());
            }
        }
        // wait for drain threads to complete
        try {
            tOut.join(1000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        try {
            tErr.join(1000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (context.getCancellationToken() != null && context.getCancellationToken().isCancelled()) {
            throw new StreamCancelledException();
        }
        int code = p.exitValue();
        Map<String, Object> machine = Map.of("exitCode", code, "stdout", out.toString(), "stderr", err.toString());
        String text = "exitCode=" + code + "\n" + out.toString() + err.toString();
        return new ToolExecutionResult(code == 0, text, machine);
    }
}

package com.judepereira.jupiter2.agent.tools.impl;

import com.judepereira.jupiter2.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter2.agent.tools.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class RunCommandTool implements AgentTool {
    private final ToolDefinition def;
    private final List<String> forbidden = List.of("rm -rf /", "shutdown", "reboot", "mkfs", ":(){ :|:& };:");

    public RunCommandTool() {
        Map<String, Object> schema = Map.of(
                "command", Map.of("type", "string", "description", "shell command to run"),
                "workingDir", Map.of("type", "string", "description", "optional relative working directory")
        );
        this.def = new ToolDefinition(name(), "Run a shell command in workspace (restricted)", schema);
    }

    @Override
    public String name() { return "run_command"; }

    @Override
    public ToolDefinition definition() { return def; }

    @Override
    public ToolExecutionResult execute(Map<String, Object> args, ToolExecutionContext context) throws Exception {
        if (!context.isAllowCommand()) return new ToolExecutionResult(false, "command execution disabled by configuration", Map.of());
        String cmd = (String) args.get("command");
        String working = (String) args.getOrDefault("workingDir", "");
        if (cmd == null) return new ToolExecutionResult(false, "missing command", Map.of());
        for (String f : forbidden) if (cmd.contains(f)) return new ToolExecutionResult(false, "command denied by safety policy", Map.of());
        Path wd = FileUtils.resolveWorkspacePath(context.getWorkspaceRoot(), working);
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", cmd);
        pb.directory(wd.toFile());
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
        boolean finished = p.waitFor(context.getCommandTimeoutSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            // ensure threads finish
            try { tOut.join(200); } catch (InterruptedException ignored) {}
            try { tErr.join(200); } catch (InterruptedException ignored) {}
            return new ToolExecutionResult(false, "command timed out", Map.of());
        }
        // wait for drain threads to complete
        try { tOut.join(1000); } catch (InterruptedException ignored) {}
        try { tErr.join(1000); } catch (InterruptedException ignored) {}
        int code = p.exitValue();
        Map<String, Object> machine = Map.of("exitCode", code, "stdout", out.toString(), "stderr", err.toString());
        String text = "exitCode=" + code + "\n" + out.toString() + err.toString();
        return new ToolExecutionResult(code == 0, text, machine);
    }
}

package com.judepereira.jupiter.agent.tools.impl;

import com.judepereira.jupiter.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter.agent.llm.dto.ToolSchema;
import com.judepereira.jupiter.agent.tools.AgentTool;
import com.judepereira.jupiter.agent.tools.ToolExecutionContext;
import com.judepereira.jupiter.agent.tools.ToolExecutionResult;

import com.judepereira.jupiter.agent.harness.StreamCancelledException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.judepereira.jupiter.agent.llm.dto.ToolParameter.string;

public class RunCommandTool implements AgentTool {
    private static final int INLINE_OUTPUT_LIMIT_BYTES = 4 * 1024;
    private static final int PREVIEW_EDGE_BYTES = 2 * 1024;
    private final List<String> forbidden = List.of("rm -rf /", "shutdown", "reboot", "mkfs", ":(){ :|:& };:");
    private static final ToolDefinition DEF = ToolDefinition.builtIn(
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
    public ToolDefinition definition() {
        return DEF;
    }

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
        Map<String, String> environment = pb.environment();
        environment.putAll(context.getEnvironmentVariables());
        environment.remove("JUPITER_HTTP_AUTH_PASSWORD");
        environment.remove("JUPITER_HTTP_AUTH_USERNAME");
        Process p = pb.start();
        StringBuilder stdoutBuilder = new StringBuilder();
        StringBuilder stderrBuilder = new StringBuilder();
        Thread tOut = new Thread(() -> {
            try (var is = p.getInputStream(); var ir = new InputStreamReader(is, StandardCharsets.UTF_8);
                 var br = new BufferedReader(ir)) {
                br.lines().forEach(l -> stdoutBuilder.append(l).append('\n'));
            } catch (Exception ignored) {
                // ignore
            }
        });
        Thread tErr = new Thread(() -> {
            try (var is = p.getErrorStream(); var ir = new InputStreamReader(is, StandardCharsets.UTF_8);
                 var br = new BufferedReader(ir)) {
                br.lines().forEach(l -> stderrBuilder.append(l).append('\n'));
            } catch (Exception ignored) {
                // ignore
            }
        });
        tOut.start();
        tErr.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(context.getCommandTimeoutSeconds());
        boolean finished = false;
        while (!finished) {
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
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                break;
            }
            long waitMillis = Math.max(1L, Math.min(TimeUnit.NANOSECONDS.toMillis(remainingNanos), 100L));
            finished = p.waitFor(waitMillis, TimeUnit.MILLISECONDS);
        }
        if (!finished) {
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

        String stdout = formatOutput("stdout", stdoutBuilder.toString());
        String stderr = formatOutput("stderr", stderrBuilder.toString());
        Map<String, Object> machine = Map.of(
                "exitCode", code,
                "stdout", stdout,
                "stderr", stderr);
        String text = "exitCode=" + code + "\n" + stdout + stderr;
        return new ToolExecutionResult(code == 0, text, machine);
    }

    private String formatOutput(String streamName, String output) throws Exception {
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= INLINE_OUTPUT_LIMIT_BYTES) {
            return output;
        }

        Path fullOutput = Files.createTempFile(streamName, ".txt");
        Files.writeString(fullOutput, output, StandardCharsets.UTF_8);
        return utf8Prefix(bytes, PREVIEW_EDGE_BYTES)
                + "\n...\n...\n"
                + utf8Suffix(bytes, PREVIEW_EDGE_BYTES)
                + "\n\n"
                + fullOutput;
    }

    private static String utf8Prefix(byte[] bytes, int maxBytes) throws CharacterCodingException {
        int length = Math.min(maxBytes, bytes.length);
        while (length > 0) {
            try {
                return decodeUtf8(bytes, 0, length);
            } catch (CharacterCodingException e) {
                length--;
            }
        }
        return "";
    }

    private static String utf8Suffix(byte[] bytes, int maxBytes) throws CharacterCodingException {
        int start = Math.max(0, bytes.length - maxBytes);
        while (start < bytes.length) {
            try {
                return decodeUtf8(bytes, start, bytes.length - start);
            } catch (CharacterCodingException e) {
                start++;
            }
        }
        return "";
    }

    private static String decodeUtf8(byte[] bytes, int offset, int length) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, length))
                .toString();
    }
}

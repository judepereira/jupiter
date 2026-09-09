package com.judepereira.jupiter.agent.tools.impl;

import com.judepereira.jupiter.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter.agent.llm.dto.ToolSchema;
import com.judepereira.jupiter.agent.harness.StreamCancelledException;
import com.judepereira.jupiter.agent.tools.AgentTool;
import com.judepereira.jupiter.agent.tools.ToolExecutionContext;
import com.judepereira.jupiter.agent.tools.ToolExecutionResult;
import com.judepereira.jupiter.security.ProcessEnvironmentSanitizer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
        // ProcessBuilder encodes command-line arguments using the JVM's native encoding. On a
        // POSIX locale that turns non-ASCII command text into '?', before the shell sees it.
        // Put the script in a UTF-8 file so the shell reads the original command bytes instead.
        Path commandScript = Files.createTempFile("jupiter-command", ".sh");
        Files.writeString(commandScript, cmd, StandardCharsets.UTF_8);
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", commandScript.toString());
        pb.directory(wd.toFile());
        Map<String, String> environment = pb.environment();
        environment.clear();
        environment.putAll(buildCommandEnvironment(System.getenv(), context.getCommandEnvironmentAllowlist(), context.getEnvironmentVariables()));
        environment.put("LANG", "C.utf8");
        environment.put("LC_ALL", "C.utf8");
        Process p;
        p = pb.start();
        OutputCapture stdoutCapture = new OutputCapture("stdout");
        OutputCapture stderrCapture = new OutputCapture("stderr");
        Thread tOut = new Thread(() -> capture(p.getInputStream(), stdoutCapture));
        Thread tErr = new Thread(() -> capture(p.getErrorStream(), stderrCapture));
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
                Files.deleteIfExists(commandScript);
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
            Files.deleteIfExists(commandScript);
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
        Files.deleteIfExists(commandScript);
        int code = p.exitValue();

        String stdout = formatOutput(stdoutCapture);
        String stderr = formatOutput(stderrCapture);
        Map<String, Object> machine = Map.of(
                "exitCode", code,
                "stdout", stdout,
                "stderr", stderr);
        String text = "exitCode=" + code + "\n" + stdout + stderr;
        return new ToolExecutionResult(code == 0, text, machine);
    }

    static Map<String, String> buildCommandEnvironment(Map<String, String> hostEnvironment,
                                                        java.util.Set<String> allowlist,
                                                        Map<String, String> projectEnvironment) {
        Map<String, String> environment = new java.util.HashMap<>();
        for (String name : allowlist) {
            String value = hostEnvironment.get(name);
            if (value != null) {
                environment.put(name, value);
            }
        }
        environment.putAll(projectEnvironment);
        ProcessEnvironmentSanitizer.sanitize(environment);
        return Map.copyOf(environment);
    }

    private static void capture(InputStream input, OutputCapture capture) {
        try (input; OutputStream output = Files.newOutputStream(capture.path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                capture.accept(buffer, read);
            }
            capture.finish(output);
        } catch (IOException ignored) {
            // The process may be forcibly terminated while its output is being read.
        }
    }

    private String formatOutput(OutputCapture capture) throws Exception {
        if (capture.size <= INLINE_OUTPUT_LIMIT_BYTES) {
            return Files.readString(capture.path, StandardCharsets.UTF_8);
        }

        return utf8Prefix(capture.prefix.toByteArray(), PREVIEW_EDGE_BYTES)
                + "\n...\n...\n"
                + utf8Suffix(capture.suffixBytes(), PREVIEW_EDGE_BYTES)
                + "\n\n"
                + capture.path;
    }

    private static final class OutputCapture {
        private final String streamName;
        private final Path path;
        private final ByteArrayOutputStream prefix = new ByteArrayOutputStream(PREVIEW_EDGE_BYTES);
        private final byte[] suffix = new byte[PREVIEW_EDGE_BYTES];
        private int suffixLength;
        private long size;

        private OutputCapture(String streamName) throws IOException {
            this.streamName = streamName;
            this.path = Files.createTempFile(streamName, ".capture");
        }

        private void accept(byte[] buffer, int length) throws IOException {
            size += length;
            int prefixLength = Math.min(length, PREVIEW_EDGE_BYTES - prefix.size());
            prefix.write(buffer, 0, prefixLength);
            for (int i = 0; i < length; i++) {
                if (suffixLength < PREVIEW_EDGE_BYTES) {
                    suffix[suffixLength++] = buffer[i];
                } else {
                    System.arraycopy(suffix, 1, suffix, 0, PREVIEW_EDGE_BYTES - 1);
                    suffix[PREVIEW_EDGE_BYTES - 1] = buffer[i];
                }
            }
        }

        private void finish(OutputStream output) throws IOException {
            if (size > 0 && suffix[suffixLength - 1] != '\n') {
                output.write('\n');
                accept(new byte[]{'\n'}, 1);
            }
            // Output is written by the capture loop.
        }

        private byte[] suffixBytes() {
            return java.util.Arrays.copyOf(suffix, suffixLength);
        }
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

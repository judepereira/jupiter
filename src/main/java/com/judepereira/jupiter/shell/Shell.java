package com.judepereira.jupiter.shell;

import lombok.Value;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class Shell {

    private Shell() {
    }

    @Value
    public static class ExecutionResult {
        String stdout;
        String stdin;
        int exitCode;
    }

    public static ExecutionResult execute(File workingDirectory, String... command) throws IOException, InterruptedException {
        if (command == null || command.length == 0) {
            throw new IllegalArgumentException("command must be provided");
        }

        var pb = new ProcessBuilder(command);
        if (workingDirectory != null) {
            pb.directory(workingDirectory);
        }

        var process = pb.start();

        var stdoutSb = new StringBuilder();
        var stderrSb = new StringBuilder();

        Thread outThread = Thread.ofVirtual().start(() -> readStream(process.getInputStream(), stdoutSb));
        Thread errThread = Thread.ofVirtual().start(() -> readStream(process.getErrorStream(), stderrSb));

        int exit = process.waitFor();
        outThread.join();
        errThread.join();

        return new ExecutionResult(stdoutSb.toString(), stderrSb.toString(), exit);
    }

    private static void readStream(InputStream in, StringBuilder sb) {
        try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read process stream", e);
        }
    }
}

package com.judepereira.jupiter.git;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Component
public class ProcessGitCommandRunner implements GitCommandRunner {

    @Override
    public GitCommandResult run(Path workingDirectory, List<String> command, Duration timeout) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(workingDirectory.toFile());
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            builder.environment().put("GIT_SSH_COMMAND", "ssh -oBatchMode=yes");
            Process process = builder.start();
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<byte[]> stdout = executor.submit(() -> process.getInputStream().readAllBytes());
                Future<byte[]> stderr = executor.submit(() -> process.getErrorStream().readAllBytes());
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    return new GitCommandResult(-1, "", "Git command timed out after " + timeout);
                }
                return new GitCommandResult(process.exitValue(),
                        new String(stdout.get(), StandardCharsets.UTF_8),
                        new String(stderr.get(), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            return new GitCommandResult(-1, "", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new GitCommandResult(-1, "", "Git command interrupted");
        } catch (ExecutionException e) {
            return new GitCommandResult(-1, "", e.getCause().getMessage());
        }
    }
}

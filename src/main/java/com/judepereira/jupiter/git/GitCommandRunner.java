package com.judepereira.jupiter.git;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

@FunctionalInterface
public interface GitCommandRunner {

    GitCommandResult run(Path workingDirectory, List<String> command, Duration timeout);

    record GitCommandResult(int exitCode, String stdout, String stderr) {
        public GitCommandResult {
            stdout = stdout == null ? "" : stdout;
            stderr = stderr == null ? "" : stderr;
        }

        public boolean succeeded() {
            return exitCode == 0;
        }
    }
}

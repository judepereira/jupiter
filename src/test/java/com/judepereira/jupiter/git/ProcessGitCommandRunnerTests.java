package com.judepereira.jupiter.git;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ProcessGitCommandRunnerTests {
    @Test
    void realChildDoesNotReceiveEncryptionKey() {
        assumeTrue(System.getProperty("os.name").equals("Linux"));
        GitCommandRunner.GitCommandResult result = new ProcessGitCommandRunner().runWithEnvironment(
                Path.of("/tmp"), java.util.List.of("/usr/bin/env"), Duration.ofSeconds(2),
                Map.of("JUPITER_ENCRYPTION_KEY", "secret", "GIT_SENTINEL", "preserved"));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).doesNotContain("JUPITER_ENCRYPTION_KEY=")
                .contains("GIT_SENTINEL=preserved");
    }
}

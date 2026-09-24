package com.judepereira.jupiter.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActiveStreamRegistryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void activeWorkspaceRejectsAndInactiveWorkspaceRuns() {
        ActiveStreamRegistryService registry = new ActiveStreamRegistryService();
        registry.register("assistant", 1, tempDir.toString());
        var ran = new boolean[1];

        assertThat(registry.runIfNoActiveStreamForWorkspace(tempDir.toString(), () -> ran[0] = true)).isFalse();
        registry.unregister("assistant");
        assertThat(registry.runIfNoActiveStreamForWorkspace(tempDir.toString(), () -> ran[0] = true)).isTrue();
        assertThat(ran[0]).isTrue();
    }

    @Test
    void symlinkAndRealWorkspaceAliasesMatch() throws IOException {
        Path real = Files.createDirectory(tempDir.resolve("real"));
        Path link = tempDir.resolve("link");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            assumeTrue(false, "symbolic links are unsupported");
        }
        ActiveStreamRegistryService registry = new ActiveStreamRegistryService();
        registry.register("assistant", 1, real.toString());

        assertThat(registry.hasActiveStreamForWorkspace(link.toString())).isTrue();
    }

    @Test
    void normalizesRelativePathsAndTracksMultipleStreams() {
        ActiveStreamRegistryService registry = new ActiveStreamRegistryService();
        Path workspace = Path.of("relative", "workspace");
        registry.register("first", 1, workspace.toString());
        registry.register("second", 2, workspace.toAbsolutePath().resolve(".").toString());

        assertThat(registry.hasActiveStreamForWorkspace(workspace.toAbsolutePath().toString())).isTrue();
        registry.unregister("first");
        assertThat(registry.hasActiveStreamForWorkspace(workspace.toString())).isTrue();
        registry.unregister("second");
        assertThat(registry.hasActiveStreamForWorkspace(workspace.toString())).isFalse();
    }

    @Test
    void unregisteringUnknownAssistantIdIsNoOp() {
        ActiveStreamRegistryService registry = new ActiveStreamRegistryService();

        assertThatCode(() -> registry.unregister("unknown")).doesNotThrowAnyException();
    }

    @Test
    void ignoresNullBlankAndInvalidRoots() {
        ActiveStreamRegistryService registry = new ActiveStreamRegistryService();
        registry.register("blank", 1, " ");
        registry.register("null", 2, null);

        assertThat(registry.hasActiveStreamForWorkspace(null)).isFalse();
        assertThat(registry.hasActiveStreamForWorkspace(" ")).isFalse();
        assertThat(registry.runIfNoActiveStreamForWorkspace("\0", () -> {
        })).isFalse();
    }

    @Test
    void registrationWaitsForAdmittedOperationOnSameWorkspace() throws Exception {
        ActiveStreamRegistryService registry = new ActiveStreamRegistryService();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var operation = executor.submit(() -> registry.runIfNoActiveStreamForWorkspace(tempDir.toString(), () -> {
                entered.countDown();
                await(release);
            }));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            var registration = executor.submit(() -> registry.register("assistant", 1, tempDir.toString()));
            assertThat(registration.isDone()).isFalse();
            release.countDown();
            assertThat(operation.get(1, TimeUnit.SECONDS)).isTrue();
            registration.get(1, TimeUnit.SECONDS);
            assertThat(registry.hasActiveStreamForWorkspace(tempDir.toString())).isTrue();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void unrelatedWorkspaceRegistrationAndAdmissionAreIndependent() throws Exception {
        ActiveStreamRegistryService registry = new ActiveStreamRegistryService();
        Path other = Files.createDirectory(tempDir.resolve("other"));
        Path third = Files.createDirectory(tempDir.resolve("third"));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(3);
        try {
            executor.submit(() -> registry.runIfNoActiveStreamForWorkspace(tempDir.toString(), () -> {
                entered.countDown();
                await(release);
            }));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            var registration = executor.submit(() -> registry.register("other", 2, other.toString()));
            var admission = executor.submit(() -> registry.runIfNoActiveStreamForWorkspace(third.toString(), () -> {
            }));
            registration.get(1, TimeUnit.SECONDS);
            assertThat(admission.get(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}

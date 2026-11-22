package com.judepereira.jupiter.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActiveStreamRegistryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void reportsWorkspaceActiveUntilStreamUnregisters() {
        ActiveStreamRegistryService registry = new ActiveStreamRegistryService();
        registry.register("assistant", 1, tempDir.toString());
        assertThat(registry.hasActiveStreamForWorkspace(tempDir.toString())).isTrue();
        registry.unregister("assistant");
        assertThat(registry.hasActiveStreamForWorkspace(tempDir.toString())).isFalse();
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
        assertThat(registry.hasActiveStreamForWorkspace("\0")).isFalse();
    }

}

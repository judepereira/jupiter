package com.judepereira.jupiter.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppStateServiceDisplayImageTests {

    @Test
    void loadDisplayImageViewRejectsSymlinkEscape(@TempDir Path workspace) throws Exception {
        Path outside = Files.createTempFile("jupiter-outside-", ".png");
        try {
            Files.write(outside, new byte[] {(byte) 0x89, 'P', 'N', 'G'});
            Path link = workspace.resolve("images/escape.png");
            link.getParent().toFile().mkdirs();
            Files.createSymbolicLink(link, outside);

            assertThatThrownBy(() -> AppStateService.verifyDisplayImagePathWithinWorkspace(workspace.toString(), "images/escape.png"))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Path escapes workspace");
        } finally {
            Files.deleteIfExists(outside);
        }
    }
}

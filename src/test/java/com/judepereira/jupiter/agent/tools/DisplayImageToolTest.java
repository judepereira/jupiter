package com.judepereira.jupiter.agent.tools;

import com.judepereira.jupiter.agent.tools.impl.DisplayImageTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DisplayImageToolTest {

    @Test
    void displaysValidPng(@TempDir Path tmp) throws Exception {
        Path image = tmp.resolve("images/cat.png");
        image.getParent().toFile().mkdirs();
        Files.write(image, new byte[] {(byte) 0x89, 'P', 'N', 'G'});

        DisplayImageTool tool = new DisplayImageTool();
        ToolExecutionResult result = tool.execute(Map.of("path", "images/cat.png", "alt", "Cat"), new ToolExecutionContext(tmp, true, true, 5, null, null, null, null, null, null, null));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMachine().get("displayType")).isEqualTo("image");
        assertThat(result.getMachine().get("path")).isEqualTo("images/cat.png");
        assertThat(result.getMachine().get("alt")).isEqualTo("Cat");
        assertThat(result.getMachine().get("mediaType")).isEqualTo("image/png");
    }

    @Test
    void failsWhenFileMissing(@TempDir Path tmp) throws Exception {
        DisplayImageTool tool = new DisplayImageTool();
        ToolExecutionResult result = tool.execute(Map.of("path", "missing.png"), new ToolExecutionContext(tmp, true, true, 5, null, null, null, null, null, null, null));

        assertFalse(result.isSuccess());
        assertThat(result.getText()).contains("file not found");
    }

    @Test
    void failsOnUnsupportedFileType(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("notes.txt");
        Files.writeString(file, "not an image");

        DisplayImageTool tool = new DisplayImageTool();
        ToolExecutionResult result = tool.execute(Map.of("path", "notes.txt"), new ToolExecutionContext(tmp, true, true, 5, null, null, null, null, null, null, null));

        assertFalse(result.isSuccess());
        assertThat(result.getText()).contains("unsupported image type");
    }

    @Test
    void failsOnSymlinkEscape(@TempDir Path tmp) throws Exception {
        Path outside = Files.createTempFile("jupiter-outside-", ".png");
        try {
            Files.write(outside, new byte[] {(byte) 0x89, 'P', 'N', 'G'});
            Path link = tmp.resolve("images/escape.png");
            link.getParent().toFile().mkdirs();
            Files.createSymbolicLink(link, outside);

            DisplayImageTool tool = new DisplayImageTool();
            assertThrows(Exception.class, () -> tool.execute(Map.of("path", "images/escape.png"), new ToolExecutionContext(tmp, true, true, 5, null, null, null, null, null, null, null)));
        } finally {
            Files.deleteIfExists(outside);
        }
    }
}

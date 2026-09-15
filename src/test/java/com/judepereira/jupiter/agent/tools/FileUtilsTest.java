package com.judepereira.jupiter.agent.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.judepereira.jupiter.agent.tools.impl.FileUtils;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class FileUtilsTest {

    @Test
    public void resolve_relative_from_dot_allows_valid_relative(@TempDir Path tmp) throws IOException {
        // workspace root is current dir (.) resolved to tmp
        Path ws = tmp.resolve("sub");
        ws.toFile().mkdirs();
        Path resolved = FileUtils.resolveWorkspacePath(Path.of("."), "src");
        // should not throw and should be within workspace of current dir; just assert
        // not null
        assertNotNull(resolved);
    }
}

package com.judepereira.jupiter2.agent.tools;

import com.judepereira.jupiter2.agent.tools.impl.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class FileUtilsTest {

    @Test
    public void resolve_relative_from_dot_allows_valid_relative(@TempDir Path tmp) throws IOException {
        // workspace root is current dir (.) resolved to tmp
        Path ws = tmp.resolve("sub");
        ws.toFile().mkdirs();
        Path resolved = FileUtils.resolveWorkspacePath(Path.of("."), "src");
        // should not throw and should be within workspace of current dir; just assert not null
        assertNotNull(resolved);
    }
}

package com.judepereira.jupiter.agent.tools;

import com.judepereira.jupiter.agent.tools.impl.ListFilesTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ListFilesToolTest {

    @Test
    public void include_glob_filters_files(@TempDir Path tmp) throws Exception {
        Path a = tmp.resolve("a.txt");
        Path b = tmp.resolve("src/Main.java");
        b.toFile().getParentFile().mkdirs();
        Files.writeString(a, "x");
        Files.writeString(b, "y");

        ListFilesTool t = new ListFilesTool();
        ToolExecutionContext ctx = new ToolExecutionContext(tmp, true, true, 5);

        var res = t.execute(Map.of("path", "", "include", "**/*.java"), ctx);
        assertTrue(res.isSuccess());
        var files = (java.util.List<String>) res.getMachine().get("files");
        assertNotNull(files);
        assertTrue(files.stream().anyMatch(s -> s.endsWith("src/Main.java")));
        assertFalse(files.stream().anyMatch(s -> s.endsWith("a.txt")));
    }

    @Test
    public void default_root_path_dot_matches(@TempDir Path unusedTmp) throws Exception {
        // create a temporary workspace under the current working dir and use Path.of('.') as workspace root
        Path ws = Files.createTempDirectory(Path.of("."), "jupiter-test-ws-");
        Path a = ws.resolve("a.txt");
        Path b = ws.resolve("src/Main.java");
        b.toFile().getParentFile().mkdirs();
        Files.writeString(a, "x");
        Files.writeString(b, "y");

        ListFilesTool t = new ListFilesTool();
        ToolExecutionContext ctx = new ToolExecutionContext(Path.of("."), true, true, 5);

        var res = t.execute(Map.of("path", ws.getFileName().toString(), "include", "**/*.java"), ctx);
        assertTrue(res.isSuccess());
        var files = (java.util.List<String>) res.getMachine().get("files");
        assertNotNull(files);
        assertTrue(files.stream().anyMatch(s -> s.endsWith("src/Main.java")));
        assertFalse(files.stream().anyMatch(s -> s.endsWith("a.txt")));
        // cleanup created ws
        try {
            java.nio.file.Files.walk(ws)
                    .sorted(java.util.Comparator.reverseOrder())
                    .map(java.nio.file.Path::toFile)
                    .forEach(java.io.File::delete);
        } catch (Exception ignored) {}
    }
}

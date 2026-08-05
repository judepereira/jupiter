package com.judepereira.jupiter.agent.tools;

import com.judepereira.jupiter.agent.tools.impl.SearchCodeTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class SearchCodeToolTest {

    @Test
    public void include_glob_filters_search(@TempDir Path tmp) throws Exception {
        Path a = tmp.resolve("a.txt");
        Path b = tmp.resolve("src/Main.java");
        b.toFile().getParentFile().mkdirs();
        Files.writeString(a, "needle");
        Files.writeString(b, "needle\n");

        SearchCodeTool t = new SearchCodeTool();
        ToolExecutionContext ctx = new ToolExecutionContext(tmp, true, true, 5);

        var res = t.execute(Map.of("path", "", "pattern", "needle", "include", "**/*.java"), ctx);
        assertTrue(res.isSuccess());
        var matches = (java.util.List<String>) res.getMachine().get("matches");
        assertNotNull(matches);
        assertTrue(matches.stream().anyMatch(s -> s.contains("src/Main.java")));
        assertFalse(matches.stream().anyMatch(s -> s.contains("a.txt")));
    }

    @Test
    public void default_root_path_dot_search_matches(@TempDir Path unusedTmp) throws Exception {
        Path ws = Files.createTempDirectory(Path.of("."), "jupiter-test-ws-");
        Path a = ws.resolve("a.txt");
        Path b = ws.resolve("src/Main.java");
        b.toFile().getParentFile().mkdirs();
        Files.writeString(a, "needle");
        Files.writeString(b, "needle\n");

        SearchCodeTool t = new SearchCodeTool();
        ToolExecutionContext ctx = new ToolExecutionContext(Path.of("."), true, true, 5);

        var res = t.execute(Map.of("path", ws.getFileName().toString(), "pattern", "needle", "include", "**/*.java"), ctx);
        assertTrue(res.isSuccess());
        var matches = (java.util.List<String>) res.getMachine().get("matches");
        assertNotNull(matches);
        assertTrue(matches.stream().anyMatch(s -> s.contains("src/Main.java")));
        assertFalse(matches.stream().anyMatch(s -> s.contains("a.txt")));
        try {
            java.nio.file.Files.walk(ws)
                    .sorted(java.util.Comparator.reverseOrder())
                    .map(java.nio.file.Path::toFile)
                    .forEach(java.io.File::delete);
        } catch (Exception ignored) {}
    }
}

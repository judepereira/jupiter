package com.judepereira.jupiter.agent.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.judepereira.jupiter.agent.tools.impl.RipgrepToolSupport;
import com.judepereira.jupiter.agent.tools.impl.SearchCodeTool;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SearchCodeToolTest {

    @Test
    public void include_glob_filters_search(@TempDir Path tmp) throws Exception {
        Path a = tmp.resolve("a.txt");
        Path b = tmp.resolve("src/Main.java");
        b.toFile().getParentFile().mkdirs();
        Files.writeString(a, "needle");
        Files.writeString(b, "needle\n");

        SearchCodeTool t = new SearchCodeTool(new RipgrepToolSupport());
        ToolExecutionContext ctx = new ToolExecutionContext(tmp, true, true, 5, null, null, null, null, Map.of(),
                Set.of(), ToolProgressSink.noop(), null);

        var res = t.execute(Map.of("path", "", "pattern", "needle", "include", "**/*.java"), ctx);
        assertTrue(res.isSuccess());
        var matches = (List<String>) res.getMachine().get("matches");
        assertNotNull(matches);
        assertTrue(matches.stream().anyMatch(s -> s.contains("src/Main.java")));
        assertFalse(matches.stream().anyMatch(s -> s.contains("a.txt")));
    }

    @Test
    public void include_agents_md_matches_root_and_nested_files(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("AGENTS.md"), "needle");
        Path nested = tmp.resolve("docs");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("AGENTS.md"), "needle");

        SearchCodeTool t = new SearchCodeTool(new RipgrepToolSupport());
        ToolExecutionContext ctx = new ToolExecutionContext(tmp, true, true, 5, null, null, null, null, Map.of(),
                Set.of(), ToolProgressSink.noop(), null);

        var res = t.execute(Map.of("path", "", "pattern", "needle", "include", "**/AGENTS.md"), ctx);
        assertTrue(res.isSuccess());
        var matches = (List<String>) res.getMachine().get("matches");
        assertNotNull(matches);
        assertTrue(matches.stream().anyMatch(s -> s.contains("AGENTS.md:1:needle")));
        assertTrue(matches.stream().anyMatch(s -> s.contains("docs/AGENTS.md:1:needle")));
    }

    @Test
    public void default_root_path_dot_search_matches(@TempDir Path unusedTmp) throws Exception {
        Path ws = Files.createTempDirectory(Path.of("."), "jupiter-test-ws-");
        Path a = ws.resolve("a.txt");
        Path b = ws.resolve("src/Main.java");
        b.toFile().getParentFile().mkdirs();
        Files.writeString(a, "needle");
        Files.writeString(b, "needle\n");

        SearchCodeTool t = new SearchCodeTool(new RipgrepToolSupport());
        ToolExecutionContext ctx = new ToolExecutionContext(Path.of("."), true, true, 5, null, null, null, null,
                Map.of(), Set.of(), ToolProgressSink.noop(), null);

        var res = t.execute(Map.of("path", ws.getFileName().toString(), "pattern", "needle", "include", "**/*.java"),
                ctx);
        assertTrue(res.isSuccess());
        var matches = (List<String>) res.getMachine().get("matches");
        assertNotNull(matches);
        assertTrue(matches.stream().anyMatch(s -> s.contains("src/Main.java")));
        assertFalse(matches.stream().anyMatch(s -> s.contains("a.txt")));
        try {
            Files.walk(ws).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        } catch (Exception ignored) {
        }
    }
}

package com.judepereira.jupiter.agent.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.judepereira.jupiter.agent.tools.impl.ListFilesTool;
import com.judepereira.jupiter.agent.tools.impl.RipgrepToolSupport;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ListFilesToolTest {

    @Test
    public void include_glob_filters_files(@TempDir Path tmp) throws Exception {
        Path a = tmp.resolve("a.txt");
        Path b = tmp.resolve("src/Main.java");
        b.toFile().getParentFile().mkdirs();
        Files.writeString(a, "x");
        Files.writeString(b, "y");

        ListFilesTool t = new ListFilesTool(new RipgrepToolSupport());
        ToolExecutionContext ctx = new ToolExecutionContext(tmp, true, true, 5, null, null, null, null, Map.of(),
                Set.of(), ToolProgressSink.noop(), null);

        var res = t.execute(Map.of("path", "", "include", "**/*.java"), ctx);
        assertTrue(res.isSuccess());
        var files = (List<String>) res.getMachine().get("files");
        assertNotNull(files);
        assertTrue(files.stream().anyMatch(s -> s.endsWith("src/Main.java")));
        assertFalse(files.stream().anyMatch(s -> s.endsWith("a.txt")));
    }

    @Test
    public void include_agents_md_matches_root_and_nested_files(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("AGENTS.md"), "root");
        Path nested = tmp.resolve("docs");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("AGENTS.md"), "nested");

        ListFilesTool t = new ListFilesTool(new RipgrepToolSupport());
        ToolExecutionContext ctx = new ToolExecutionContext(tmp, true, true, 5, null, null, null, null, Map.of(),
                Set.of(), ToolProgressSink.noop(), null);

        var res = t.execute(Map.of("path", "", "include", "**/AGENTS.md"), ctx);
        assertTrue(res.isSuccess());
        var files = (List<String>) res.getMachine().get("files");
        assertNotNull(files);
        assertTrue(files.stream().anyMatch(s -> s.endsWith("AGENTS.md") && !s.contains("docs/")));
        assertTrue(files.stream().anyMatch(s -> s.endsWith("docs/AGENTS.md")));
    }

    @Test
    public void default_root_path_dot_matches(@TempDir Path unusedTmp) throws Exception {
        // create a temporary workspace under the current working dir and use
        // Path.of('.') as workspace root
        Path ws = Files.createTempDirectory(Path.of("."), "jupiter-test-ws-");
        Path a = ws.resolve("a.txt");
        Path b = ws.resolve("src/Main.java");
        b.toFile().getParentFile().mkdirs();
        Files.writeString(a, "x");
        Files.writeString(b, "y");

        ListFilesTool t = new ListFilesTool(new RipgrepToolSupport());
        ToolExecutionContext ctx = new ToolExecutionContext(Path.of("."), true, true, 5, null, null, null, null,
                Map.of(), Set.of(), ToolProgressSink.noop(), null);

        var res = t.execute(Map.of("path", ws.getFileName().toString(), "include", "**/*.java"), ctx);
        assertTrue(res.isSuccess());
        var files = (List<String>) res.getMachine().get("files");
        assertNotNull(files);
        assertTrue(files.stream().anyMatch(s -> s.endsWith("src/Main.java")));
        assertFalse(files.stream().anyMatch(s -> s.endsWith("a.txt")));
        // cleanup created ws
        try {
            Files.walk(ws).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        } catch (Exception ignored) {
        }
    }
}

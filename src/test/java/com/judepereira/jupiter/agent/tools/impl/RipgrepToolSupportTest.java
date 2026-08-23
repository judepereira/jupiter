package com.judepereira.jupiter.agent.tools.impl;

import com.judepereira.jupiter.agent.tools.ToolExecutionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class RipgrepToolSupportTest {

    @Test
    public void assertAvailable_passes_when_rg_is_on_path() {
        assertDoesNotThrow(() -> new RipgrepToolSupport().assertAvailable());
    }

    @Test
    public void listFiles_include_matches_root_and_nested_agents_md(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("AGENTS.md"), "root\n");
        Path nested = Files.createDirectories(tmp.resolve("docs"));
        Files.writeString(nested.resolve("AGENTS.md"), "nested\n");

        ToolExecutionResult list = new RipgrepToolSupport().listFiles(tmp, "", "**/AGENTS.md", 5);

        assertTrue(list.isSuccess());
        List<String> files = castStrings(list, "files");
        assertTrue(files.stream().anyMatch(s -> s.endsWith("AGENTS.md") && !s.contains("docs/")));
        assertTrue(files.stream().anyMatch(s -> s.endsWith("docs/AGENTS.md")));
    }

    @Test
    public void searchCode_include_matches_root_and_nested_agents_md(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("AGENTS.md"), "needle\n");
        Path nested = Files.createDirectories(tmp.resolve("docs"));
        Files.writeString(nested.resolve("AGENTS.md"), "needle\n");

        ToolExecutionResult search = new RipgrepToolSupport().searchCode(tmp, "", "needle", "**/AGENTS.md", 5);

        assertTrue(search.isSuccess());
        List<String> matches = castStrings(search, "matches");
        assertTrue(matches.stream().anyMatch(s -> s.contains("AGENTS.md:1:needle")));
        assertTrue(matches.stream().anyMatch(s -> s.contains("docs/AGENTS.md:1:needle")));
    }

    @Test
    public void listFiles_excludes_hidden_and_gitignored_files_by_default(@TempDir Path tmp) throws Exception {
        initGitRepo(tmp);
        Files.writeString(tmp.resolve("visible.txt"), "visible\n");
        Files.writeString(tmp.resolve(".hidden.txt"), "hidden\n");
        Files.writeString(tmp.resolve("ignored.txt"), "ignored\n");
        Files.writeString(tmp.resolve(".gitignore"), "ignored.txt\n");

        ToolExecutionResult list = new RipgrepToolSupport().listFiles(tmp, "", "", 5);

        assertTrue(list.isSuccess());
        List<String> files = castStrings(list, "files");
        assertTrue(files.stream().anyMatch(s -> s.endsWith("visible.txt")));
        assertFalse(files.stream().anyMatch(s -> s.endsWith(".hidden.txt")));
        assertFalse(files.stream().anyMatch(s -> s.endsWith("ignored.txt")));
    }

    @Test
    public void searchCode_excludes_hidden_and_gitignored_files_by_default(@TempDir Path tmp) throws Exception {
        initGitRepo(tmp);
        Files.writeString(tmp.resolve("visible.txt"), "needle\n");
        Files.writeString(tmp.resolve(".hidden.txt"), "needle\n");
        Files.writeString(tmp.resolve("ignored.txt"), "needle\n");
        Files.writeString(tmp.resolve(".gitignore"), "ignored.txt\n");

        ToolExecutionResult search = new RipgrepToolSupport().searchCode(tmp, "", "needle", "", 5);

        assertTrue(search.isSuccess());
        List<String> matches = castStrings(search, "matches");
        assertTrue(matches.stream().anyMatch(s -> s.contains("visible.txt:1:needle")));
        assertFalse(matches.stream().anyMatch(s -> s.contains(".hidden.txt")));
        assertFalse(matches.stream().anyMatch(s -> s.contains("ignored.txt")));
    }

    @Test
    public void searchCode_treats_no_matches_as_success(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("visible.txt"), "different\n");

        ToolExecutionResult search = new RipgrepToolSupport().searchCode(tmp, "", "needle", "", 5);

        assertTrue(search.isSuccess());
        assertTrue(castStrings(search, "matches").isEmpty());
    }

    @Test
    public void listFiles_treats_no_matches_as_success(@TempDir Path tmp) {
        ToolExecutionResult list = new RipgrepToolSupport().listFiles(tmp, "", "**/*.java", 5);

        assertTrue(list.isSuccess());
        assertTrue(castStrings(list, "files").isEmpty());
    }

    @Test
    public void searchCode_rejects_path_escape_via_dotdot(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("workspace"));
        Files.writeString(tmp.resolve("outside.txt"), "needle\n");

        ToolExecutionResult search = new RipgrepToolSupport().searchCode(tmp.resolve("workspace"), "..", "needle", "", 5);

        assertFalse(search.isSuccess());
        assertTrue(search.getText().contains("failed to resolve path"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> castStrings(ToolExecutionResult result, String key) {
        return (List<String>) result.getMachine().get(key);
    }

    private static void initGitRepo(Path tmp) throws Exception {
        Process process = new ProcessBuilder("git", "init", "-q").directory(tmp.toFile()).start();
        assertEquals(0, process.waitFor());
    }
}

package com.judepereira.jupiter.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ToolFileProviderTest {

    private Path tmpDir;
    private ToolFileProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        tmpDir = Files.createTempDirectory("toolfiletest");
        provider = new ToolFileProvider(tmpDir);
    }

    @Test
    void listFiles_and_write_read_roundtrip() throws Exception {
        // initially empty directory
        String list = provider.listFiles(".");
        assertTrue(list.isBlank());

        // write a file then list
        String res = provider.writeFile("sub/hello.txt", "hi");
        assertTrue(res.contains("Wrote file"));
        String files = provider.listFiles("sub");
        assertTrue(files.contains("hello.txt"));

        String content = provider.readFile("sub/hello.txt");
        assertEquals("hi", content);
    }

    @Test
    void bash_runs_simple_command() throws Exception {
        String out = provider.bash("echo foo");
        assertNotNull(out);
        assertTrue(out.contains("exit=0"));
        assertTrue(out.contains("foo"));
    }

    @Test
    void applyPatch_reports_failures_gracefully() throws Exception {
        // invalid patch text should cause non-zero exit or error text
        String bad = "this is not a patch";
        String res = provider.applyPatch(bad);
        assertNotNull(res);
        // either non-zero exit or stderr complaining
        assertTrue(res.startsWith("exit=") || res.contains("error") || res.contains("fatal") || res.contains("STDERR"));
    }

    @Test
    void glob_finds_matching_files() throws Exception {
        provider.writeFile("a.txt", "x");
        provider.writeFile("b.md", "x");
        provider.writeFile("dir/c.txt", "x");
        provider.writeFile("dir/d.log", "x");

        String out = provider.glob("**/*.txt", ".");
        List<String> lines = out.isBlank() ? List.of() : Arrays.stream(out.split("\n")).collect(Collectors.toList());

        // normalize separators for comparison
        List<String> norm = lines.stream().map(s -> s.replace('\\', '/')).collect(Collectors.toList());

        assertTrue(norm.stream().anyMatch(s -> s.equals("a.txt")), "should include a.txt");
        assertTrue(norm.stream().anyMatch(s -> s.equals("dir/c.txt")), "should include dir/c.txt");
        assertFalse(norm.stream().anyMatch(s -> s.endsWith("b.md")), "should not include b.md");
        assertFalse(norm.stream().anyMatch(s -> s.endsWith("dir/d.log")), "should not include dir/d.log");
    }

    @Test
    void glob_validates_inputs() throws Exception {
        // blank pattern
        assertEquals("Pattern is required", provider.glob("", "."));

        // missing path
        String missing = provider.glob("**/*.txt", "no-such-dir");
        assertTrue(missing.startsWith("Path does not exist:"));

        // non-directory path
        provider.writeFile("somefile.txt", "x");
        String notDir = provider.glob("**/*.txt", "somefile.txt");
        assertTrue(notDir.startsWith("Not a directory:"));
    }

    @Test
    void grep_finds_matches_with_line_numbers() throws Exception {
        provider.writeFile("logs/test.txt", String.join("\n", "first", "foo is here", "another foo", "last"));

        String out = provider.grep("foo", ".", null);
        List<String> lines = out.isBlank() ? List.of() : Arrays.stream(out.split("\n")).collect(Collectors.toList());
        List<String> norm = lines.stream().map(s -> s.replace('\\', '/')).collect(Collectors.toList());

        // expect two matches with correct line numbers
        assertTrue(norm.stream().anyMatch(s -> s.startsWith("logs/test.txt:2:")), "should contain line 2");
        assertTrue(norm.stream().anyMatch(s -> s.startsWith("logs/test.txt:3:")), "should contain line 3");
    }

    @Test
    void grep_honors_include_glob() throws Exception {
        provider.writeFile("a.md", "foo");
        provider.writeFile("b.txt", "foo");

        String out = provider.grep("foo", ".", "**/*.md");
        String norm = out.replace('\\', '/');
        assertTrue(norm.contains("a.md:"));
        assertFalse(norm.contains("b.txt:"));
    }

    @Test
    void grep_validates_inputs() throws Exception {
        // blank pattern
        assertEquals("Pattern is required", provider.grep("", ".", null));

        // invalid regex
        String invalid = provider.grep("[", ".", null);
        assertTrue(invalid.startsWith("Invalid regex pattern:"));

        // missing path
        String missing = provider.grep("foo", "no-such-dir", null);
        assertTrue(missing.startsWith("Path does not exist:"));

        // non-directory path
        provider.writeFile("afile.txt", "x");
        String notDir = provider.grep("foo", "afile.txt", null);
        assertTrue(notDir.startsWith("Not a directory:"));
    }
}

package com.judepereira.jupiter.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
}

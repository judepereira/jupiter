package com.judepereira.jupiter.ai.tools;

import lombok.SneakyThrows;
import lombok.val;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class FileToolTest {

    @Test
    @SneakyThrows
    void glob() {
        val dir = Files.createTempDirectory("foo");

        assertTrue(new File(dir.toFile(), "bar.txt").createNewFile());

        var res = new FileTool(dir).glob("*.txt", null);
        assertTrue(res.contains("bar.txt"), res);

        res = new FileTool(dir).glob("BAR*", null);
        assertTrue(res.contains("bar.txt"), res);
    }

    @Test
    @SneakyThrows
    void grep() {
        val dir = Files.createTempDirectory("foo");

        Files.writeString(new File(dir.toFile(), "bar.txt").toPath(), "foo");

        var res = new FileTool(dir).grep("foo", null, null);
        assertTrue(res.contains("bar.txt"), res);

        res = new FileTool(dir).grep("foo", null, "*.txt");
        assertTrue(res.contains("bar.txt"), res);

        res = new FileTool(dir).grep("foo", null, "*.bin");
        assertFalse(res.contains("bar.txt"), res);
    }

    @Test
    @SneakyThrows
    void writeFile() {
        val dir = Files.createTempDirectory("foo");

        val res = new FileTool(dir).writeFile("bar.txt", "foo");
        assertTrue(res.contains("Wrote"), res);

        assertEquals("foo", Files.readString(new File(dir.toFile(), "bar.txt").toPath()));
    }

    @Test
    @SneakyThrows
    void bash() {
        val dir = Files.createTempDirectory("foo");

        val res = new FileTool(dir).bash("uptime");
        assertTrue(res.contains("load average"), res);
    }
}

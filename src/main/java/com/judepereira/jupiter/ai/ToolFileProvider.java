package com.judepereira.jupiter.ai;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Collectors;

/**
 * Basic set of tools exposed to the AI system for interacting with the project workspace.
 */
@Component
public class ToolFileProvider {

    private final Path projectRoot;

    public ToolFileProvider() {
        this.projectRoot = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    // package-visible constructor to allow tests to inject a temporary project root
    ToolFileProvider(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
    }

    private Path resolve(String path) {
        Path p = Paths.get(path);
        if (p.isAbsolute()) {
            return p.normalize();
        }
        return projectRoot.resolve(p).normalize();
    }

    @Tool
    public String listFiles(String path) throws IOException {
        Path p = resolve(path == null || path.isBlank() ? "." : path);
        if (!Files.exists(p)) {
            return "Path does not exist: " + p;
        }
        if (!Files.isDirectory(p)) {
            return "Not a directory: " + p;
        }
        try (var stream = Files.list(p)) {
            return stream
                    .sorted(Comparator.comparing(Path::getFileName))
                    .map(entry -> {
                        String type = Files.isDirectory(entry) ? "(dir)" : "(file)";
                        return entry.getFileName().toString() + " " + type;
                    })
                    .collect(Collectors.joining("\n"));
        }
    }

    @Tool
    public String readFile(String path) throws IOException {
        Path p = resolve(path);
        if (!Files.exists(p)) {
            return "Path does not exist: " + p;
        }
        if (Files.isDirectory(p)) {
            return "Path is a directory: " + p;
        }
        byte[] bytes = Files.readAllBytes(p);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Tool
    public String writeFile(String path, String content) throws IOException {
        Path p = resolve(path);
        Files.createDirectories(p.getParent() == null ? projectRoot : p.getParent());
        Files.write(p, content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8));
        return "Wrote file: " + p.toString();
    }

    /**
     * Apply a unified diff patch using `git apply`. The patch text is passed to stdin.
     */
    @Tool
    public String applyPatch(String patchText) throws IOException, InterruptedException {
        if (patchText == null) {
            return "No patch provided";
        }
        ProcessBuilder pb = new ProcessBuilder("git", "apply", "--whitespace=fix");
        pb.directory(projectRoot.toFile());
        Process p = pb.start();
        // write patch to stdin
        try (var os = p.getOutputStream()) {
            os.write(patchText.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        boolean finished = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            return "git apply timed out";
        }
        String stdout = streamToString(p.getInputStream());
        String stderr = streamToString(p.getErrorStream());
        int code = p.exitValue();
        return "exit=" + code + "\nSTDOUT:\n" + stdout + "\nSTDERR:\n" + stderr;
    }

    @Tool
    public String bash(String command) throws IOException, InterruptedException {
        if (command == null) {
            return "No command provided";
        }
        String shell = System.getProperty("os.name").toLowerCase().contains("win") ? "cmd.exe" : "/bin/sh";
        ProcessBuilder pb;
        if (shell.endsWith("cmd.exe")) {
            pb = new ProcessBuilder(shell, "/c", command);
        } else {
            pb = new ProcessBuilder(shell, "-c", command);
        }
        pb.directory(projectRoot.toFile());
        pb.redirectErrorStream(false);
        Process p = pb.start();

        boolean finished = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            return "Command timed out";
        }
        String stdout = streamToString(p.getInputStream());
        String stderr = streamToString(p.getErrorStream());
        int code = p.exitValue();
        return "exit=" + code + "\nSTDOUT:\n" + stdout + "\nSTDERR:\n" + stderr;
    }

    private static String streamToString(InputStream in) throws IOException {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) != -1) {
            bout.write(buf, 0, r);
        }
        return new String(bout.toByteArray(), StandardCharsets.UTF_8);
    }
}

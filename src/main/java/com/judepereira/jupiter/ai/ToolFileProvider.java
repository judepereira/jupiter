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
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Basic set of tools exposed to the AI system for interacting with the project workspace.
 */
@Component
public class ToolFileProvider {

    private final List<Path> allowedRoots;
    private final Path primaryRoot;

    public ToolFileProvider() {
        this.allowedRoots = List.of(Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize());
        this.primaryRoot = this.allowedRoots.get(0);
    }

    ToolFileProvider(List<String> roots) {
        if (roots == null || roots.isEmpty()) {
            this.allowedRoots = List.of(Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize());
        } else {
            this.allowedRoots = roots.stream().map(p -> Paths.get(p).toAbsolutePath().normalize()).toList();
        }
        this.primaryRoot = this.allowedRoots.get(0);
    }

    ToolFileProvider(Path projectRoot) {
        this.allowedRoots = List.of(projectRoot.toAbsolutePath().normalize());
        this.primaryRoot = this.allowedRoots.get(0);
    }

    private Path resolve(String path) {
        Path p = Paths.get(path);
        Path candidate = p.isAbsolute() ? p.normalize() : primaryRoot.resolve(p).normalize();

        for (Path root : allowedRoots) {
            if (candidate.startsWith(root)) return candidate;
        }
        throw new SecurityException("Access to path outside allowed roots: " + candidate);
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
    public String glob(String pattern, String path) throws IOException {
        if (pattern == null || pattern.isBlank()) {
            return "Pattern is required";
        }
        Path base = resolve(path == null || path.isBlank() ? "." : path);
        if (!Files.exists(base)) {
            return "Path does not exist: " + base;
        }
        if (!Files.isDirectory(base)) {
            return "Not a directory: " + base;
        }

        PathMatcher matcher = base.getFileSystem().getPathMatcher("glob:" + pattern);
        try (var stream = Files.walk(base)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(base::relativize)
                    .filter(matcher::matches)
                    .map(Path::toString)
                    .sorted()
                    .collect(Collectors.joining("\n"));
        }
    }

    @Tool
    public String grep(String pattern, String path, String include) throws IOException {
        if (pattern == null || pattern.isBlank()) {
            return "Pattern is required";
        }

        final Pattern pat;
        try {
            pat = Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            return "Invalid regex pattern: " + e.getMessage();
        }

        Path base = resolve(path == null || path.isBlank() ? "." : path);
        if (!Files.exists(base)) {
            return "Path does not exist: " + base;
        }
        if (!Files.isDirectory(base)) {
            return "Not a directory: " + base;
        }

        boolean useInclude = include != null && !include.isBlank();
        final PathMatcher includeMatcher = useInclude ? base.getFileSystem().getPathMatcher("glob:" + include) : null;

        List<String> results = new ArrayList<>();

        try (var stream = Files.walk(base)) {
            stream.filter(Files::isRegularFile).forEach(filePath -> {
                Path rel = base.relativize(filePath);
                if (useInclude && !includeMatcher.matches(rel)) {
                    return;
                }
                try (var lines = Files.lines(filePath, StandardCharsets.UTF_8)) {
                    final int[] lineNo = {0};
                    lines.forEachOrdered(line -> {
                        lineNo[0]++;
                        try {
                            if (pat.matcher(line).find()) {
                                results.add(rel.toString() + ":" + lineNo[0] + ": " + line);
                            }
                        } catch (Exception ex) {

                        }
                    });
                } catch (IOException e) {

                }
            });
        }

        return String.join("\n", results);
    }

    @Tool
    public String writeFile(String path, String content) throws IOException {
        Path p = resolve(path);
        Files.createDirectories(p.getParent() == null ? primaryRoot : p.getParent());
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
        pb.directory(primaryRoot.toFile());
        Process p = pb.start();

        try (var os = p.getOutputStream()) {
            os.write(patchText.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        boolean finished = p.waitFor(30, TimeUnit.SECONDS);
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
        String shell = "/bin/bash";
        ProcessBuilder pb = new ProcessBuilder(shell, "-c", command);
        pb.directory(primaryRoot.toFile());
        pb.redirectErrorStream(false);
        Process p = pb.start();

        boolean finished = p.waitFor(30, TimeUnit.SECONDS);
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
        return bout.toString(StandardCharsets.UTF_8);
    }
}

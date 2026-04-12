package com.judepereira.jupiter.ai.tools;

import lombok.extern.log4j.Log4j2;
import lombok.val;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Log4j2
public class FileTool {

    public static final String RG_NOT_FOUND = "rg not found. Please ask the user to install ripgrep.";
    public static final int READ_FILE_MAX_LINES = 1000;
    private final Path primaryRoot;

    public FileTool(Path projectRoot) {
        this.primaryRoot = projectRoot;
    }

    private Path resolve(String path) {
        Path p = Paths.get(path);
        return p.isAbsolute() ? p.normalize() : primaryRoot.resolve(p).normalize();
    }

    @Tool(description = "List files in a directory. If path is not provided, lists files in the current working directory.")
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

    @Tool(description = "Read a file.")
    public String readFile(
            @ToolParam(description = "The path of the file to be read") String path,
            @ToolParam(description = "Lines to skip (if empty, no lines are skipped)", required = false) Integer skip,
            @ToolParam(description = "Maximum number of lines to return (default: "
                    + READ_FILE_MAX_LINES + "; max: " + READ_FILE_MAX_LINES + ")", required = false) Integer limit)
            throws IOException {
        Path p = resolve(path);
        if (!Files.exists(p)) {
            return "Path does not exist: " + p;
        }
        if (Files.isDirectory(p)) {
            return "Path is a directory: " + p;
        }

        try (val stream = new BufferedReader(Files.newBufferedReader(p, StandardCharsets.UTF_8))
                .lines()
                .skip(skip == null ? 0 : skip)
                .limit(limit == null ? READ_FILE_MAX_LINES : Math.min(limit, READ_FILE_MAX_LINES))) {
            return stream.collect(Collectors.joining("\n"));
        }
    }

    @Tool(description = "Find file paths/names using wildcard patterns. eg: */src/**/*.java")
    public String glob(String pattern, String path) throws IOException {
        Path base = resolve(path == null || path.isBlank() ? "." : path);

        String err = valid(pattern, base, true);
        if (err != null) {
            return err;
        }

        List<String> cmd = new ArrayList<>();
        cmd.add("rg");
        cmd.add("--files");
        cmd.add("--iglob");
        cmd.add(pattern);

        // todo: if glob returns more than 10 KB, truncate it, preferrably stdout and stderr individually (so 1024 on both sides max). Refactor runCommand to send stdout and stderr separately. do the same for grep too.

        try {
            return runCommand(cmd, base);
        } catch (IOException e) {
            log.error("glob failed", e);
            return e.getMessage();
        }
    }

    @Tool(description = "Find content in text files")
    public String grep(
            @ToolParam(description = "The pattern (RE2-like) to grep for") String pattern,
            @ToolParam(description = "The path in which to grep for. When empty, it defaults to the project's root directory") String path,
            @ToolParam(description = "The file extension or pattern to include. When empty, all files are grepped", required = false) String include) {
        Path base = resolve(path == null || path.isBlank() ? "." : path);

        String err = valid(pattern, base, false);
        if (err != null) {
            return err;
        }

        List<String> cmd = new ArrayList<>();
        cmd.add("rg");
        cmd.add("-n");
        cmd.add("--with-filename");
        if (include != null && !include.isBlank()) {
            cmd.add("-g");
            cmd.add(include);
        }
        cmd.add("-i");
        cmd.add(pattern);
        cmd.add(".");

        try {
            return runCommand(cmd, base);
        } catch (IOException e) {
            log.error("grep failed", e);
            return e.getMessage();
        }
    }

    private static @Nullable String valid(String pattern, Path base, boolean dirExpected) {
        if (StringUtils.isBlank(pattern)) {
            return "Pattern is required";
        }
        if (!Files.exists(base)) {
            return "Path does not exist: " + base;
        }
        if (dirExpected && !Files.isDirectory(base)) {
            return "Not a directory: " + base;
        }
        return null;
    }

    private String runCommand(List<String> cmd, Path dir) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(false);
        Process p = pb.start();

        try {
            boolean finished = p.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return "Command timed out";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for command", e);
        }

        String stdout = streamToString(p.getInputStream());
        String stderr = streamToString(p.getErrorStream());
        int code = p.exitValue();
        return "exit=" + code + "\nSTDOUT:\n" + stdout + "\nSTDERR:\n" + stderr;
    }

    @Tool(description = "Write a file. If the parent directory does not exist, it will be created.")
    public String writeFile(String path, String content) {
        Path p = resolve(path);
        try {
            Files.createDirectories(p.getParent() == null ? primaryRoot : p.getParent());
            Files.write(p, content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.error("write failed", e);
            return e.getMessage();
        }
        return "Wrote file: " + p;
    }

    @Tool(description = "Apply a unified diff patch using `git apply`. The patch text is passed to stdin.")
    public String applyPatch(String patchText) {
        if (patchText == null) {
            return "No patch provided";
        }

        // todo: unify with runCommand.
        ProcessBuilder pb = new ProcessBuilder("git", "apply", "--whitespace=fix");
        pb.directory(primaryRoot.toFile());
        try {
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
        } catch (Exception e) {
            log.error("patch failed", e);
            return e.getMessage();
        }
    }

    @Tool(description = "Run a command in a bash shell")
    public String bash(String command) throws IOException, InterruptedException {
        if (command == null) {
            return "No command provided";
        }

        // todo: unify with runCommand.
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

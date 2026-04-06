package com.judepereira.jupiter.ai.tools;

import org.springframework.ai.tool.annotation.Tool;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class FileTool {

    public static final String RG_NOT_FOUND = "rg not found. Please ask the user to install ripgrep.";
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
    public String readFile(String path) throws IOException {
        Path p = resolve(path);
        if (!Files.exists(p)) {
            return "Path does not exist: " + p;
        }
        if (Files.isDirectory(p)) {
            return "Path is a directory: " + p;
        }

        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[1025];
            int r = in.read(buf);
            if (r == -1) return "Empty file";

            int toDecode = Math.min(r, 1024);
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            String content;
            try {
                content = decoder.decode(ByteBuffer.wrap(buf, 0, toDecode)).toString();
            } catch (CharacterCodingException e) {
                return "File is not text: " + p;
            }

            if (r > 1024) {
                content = content + "\n[TRUNCATED to 1024 bytes. Full length: " + p.toFile().length() + " bytes]";
            }
            return content;
        }
    }

    @Tool(description = "Find file paths/names using wildcard patterns. eg: */src/**/*.java")
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

        List<String> cmd = new ArrayList<>();
        cmd.add("rg");
        cmd.add("--files");
        cmd.add("-g");
        cmd.add(pattern);

        try {
            return runCommand(cmd, base);
        } catch (IOException e) {
            return "rg not found. Please ask the user to install ripgrep.";
        }
    }

    @Tool(description = "Find content in text files")
    public String grep(String pattern, String path, String include) {
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

        List<String> cmd = new ArrayList<>();
        cmd.add("rg");
        cmd.add("-n");
        cmd.add("--hidden");
        cmd.add("--no-ignore-vcs");
        cmd.add("--with-filename");
        if (include != null && !include.isBlank()) {
            cmd.add("-g");
            cmd.add(include);
        }
        cmd.add(pattern);
        cmd.add(".");

        try {
            return runCommand(cmd, base);
        } catch (IOException e) {
            return RG_NOT_FOUND;
        }
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
            return "Write failed: " + e.getMessage();
        }
        return "Wrote file: " + p;
    }

    @Tool(description = "Apply a unified diff patch using `git apply`. The patch text is passed to stdin.")
    public String applyPatch(String patchText) {
        if (patchText == null) {
            return "No patch provided";
        }
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
            return "Failed to apply patch: " + e.getMessage();
        }
    }

    @Tool(description = "Run a command in a bash shell")
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

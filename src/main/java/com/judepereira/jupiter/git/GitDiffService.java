package com.judepereira.jupiter.git;

import com.judepereira.jupiter.shell.Shell;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class GitDiffService {

    public List<String> listChangedFiles(String projectPath) {
        File dir = validateProjectPath(projectPath);

        try {
            var res = Shell.execute(dir, "git", "diff", "--name-only");
            if (res.getExitCode() != 0) {
                throw new IllegalStateException("git diff --name-only failed: " + res.getStdin());
            }

            String stdout = Objects.requireNonNullElse(res.getStdout(), "");
            var out = new ArrayList<String>();
            for (String line : stdout.split("\n")) {
                if (!line.isBlank()) out.add(line.trim());
            }
            return out;
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Failed to execute git: " + e.getMessage(), e);
        }
    }

    public GitFileDiff getFileDiff(String projectPath, String relativeFilePath) {
        File dir = validateProjectPath(projectPath);
        if (relativeFilePath == null || relativeFilePath.isBlank()) {
            throw new IllegalArgumentException("relativeFilePath is required");
        }

        try {
            var res = Shell.execute(dir, "git", "diff", "--", relativeFilePath);
            if (res.getExitCode() != 0) {
                throw new IllegalStateException("git diff failed for file '" + relativeFilePath + "': " + res.getStdin());
            }

            return new GitFileDiff(relativeFilePath, Objects.requireNonNullElse(res.getStdout(), ""));
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Failed to execute git: " + e.getMessage(), e);
        }
    }

    private File validateProjectPath(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            throw new IllegalArgumentException("projectPath is required");
        }
        var dir = new File(projectPath);
        if (!dir.exists()) {
            throw new IllegalArgumentException("Project path does not exist: " + projectPath);
        }
        if (!dir.isDirectory()) {
            throw new IllegalArgumentException("Project path is not a directory: " + projectPath);
        }
        return dir;
    }
}

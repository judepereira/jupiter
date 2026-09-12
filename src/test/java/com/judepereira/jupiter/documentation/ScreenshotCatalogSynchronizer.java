package com.judepereira.jupiter.documentation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/** Synchronizes direct catalog PNGs using decoded pixels rather than file bytes. */
public final class ScreenshotCatalogSynchronizer {
    private ScreenshotCatalogSynchronizer() {
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: ScreenshotCatalogSynchronizer <generated-catalog> <tracked-catalog>");
        }
        Path repository = Path.of("").toAbsolutePath().normalize();
        Summary summary = synchronize(repository, Path.of(args[0]), Path.of(args[1]));
        System.out.printf("Screenshot catalog synchronized: %d unchanged, %d updated, %d added, %d removed%n",
                summary.unchanged(), summary.updated(), summary.added(), summary.removed());
    }

    public static Summary synchronize(Path repository, Path generated, Path tracked) {
        Path root = repository.toAbsolutePath().normalize();
        Path generatedDirectory = safePath(root, generated, "generated catalog");
        Path trackedDirectory = safePath(root, tracked, "tracked catalog");
        try {
            requireDirectory(generatedDirectory, "Generated catalog");
            CatalogFiles generatedFiles = directPngFiles(generatedDirectory, "generated catalog", true);
            generatedFiles.validateImages();

            CatalogFiles trackedFiles;
            if (Files.exists(trackedDirectory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                requireDirectory(trackedDirectory, "Tracked catalog");
                trackedFiles = directPngFiles(trackedDirectory, "tracked catalog", false);
                trackedFiles.validateImages();
            } else {
                trackedFiles = new CatalogFiles(trackedDirectory);
            }
            Files.createDirectories(trackedDirectory);

            int unchanged = 0;
            int updated = 0;
            int added = 0;
            for (Map.Entry<String, Path> entry : generatedFiles.paths.entrySet()) {
                String name = entry.getKey();
                Path destination = trackedFiles.path(name);
                if (trackedFiles.paths.containsKey(name)) {
                    if (samePixels(entry.getValue(), destination)) {
                        unchanged++;
                    } else {
                        replaceSafely(entry.getValue(), destination);
                        updated++;
                    }
                } else {
                    replaceSafely(entry.getValue(), destination);
                    added++;
                }
            }
            int removed = 0;
            for (String name : trackedFiles.paths.keySet()) {
                if (!generatedFiles.paths.containsKey(name)) {
                    Files.delete(trackedFiles.path(name));
                    removed++;
                }
            }
            return new Summary(unchanged, updated, added, removed);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Failed to synchronize screenshot catalogs: " + exception.getMessage(), exception);
        }
    }

    private static Path safePath(Path root, Path path, String description) {
        if (path.isAbsolute()) {
            throw new IllegalArgumentException(description + " must be relative to the repository: " + path);
        }
        Path resolved = root.resolve(path).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException(description + " escapes the repository: " + path);
        }
        Path current = root;
        Path relative = root.relativize(resolved);
        for (Path part : relative) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException(description + " must not contain symbolic links: " + path);
            }
        }
        return resolved;
    }

    private static void requireDirectory(Path path, String description) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(description + " is not a directory: " + path);
        }
    }

    private static CatalogFiles directPngFiles(Path directory, String description, boolean generated) throws IOException {
        CatalogFiles files = new CatalogFiles(directory);
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path path : entries) {
                if (Files.isSymbolicLink(path)) {
                    throw new IOException(description + " contains a symbolic link: " + path);
                }
                if (Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    if (generated) {
                        throw new IOException(description + " contains a nested directory: " + path);
                    }
                    continue;
                }
                if (!Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (!path.getFileName().toString().endsWith(".png")) {
                    if (generated) {
                        throw new IOException(description + " contains a non-PNG file: " + path);
                    }
                    continue;
                }
                files.paths.put(path.getFileName().toString(), path);
            }
        }
        return files;
    }

    private static boolean samePixels(Path first, Path second) throws IOException {
        BufferedImage a = readImage(first);
        BufferedImage b = readImage(second);
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return false;
        }
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static BufferedImage readImage(Path path) throws IOException {
        BufferedImage image = ImageIO.read(path.toFile());
        if (image == null) {
            throw new IOException("Unreadable PNG image: " + path);
        }
        return image;
    }

    private static void replaceSafely(Path source, Path destination) throws IOException {
        if (Files.isSymbolicLink(destination)) {
            throw new IOException("Destination must not be a symbolic link: " + destination);
        }
        Path temporary = Files.createTempFile(destination.getParent(), "." + destination.getFileName(), ".tmp");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public record Summary(int unchanged, int updated, int added, int removed) {
    }

    private static final class CatalogFiles {
        private final Path directory;
        private final Map<String, Path> paths = new HashMap<>();

        private CatalogFiles(Path directory) {
            this.directory = directory;
        }

        private Path path(String name) {
            return paths.getOrDefault(name, directory.resolve(name));
        }

        private void validateImages() throws IOException {
            for (Path path : paths.values()) {
                readImage(path);
            }
        }
    }
}

package com.judepereira.jupiter.documentation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ScreenshotCatalogSynchronizerTest {
    @TempDir Path repository;

    @Test
    void preservesBytesWhenPixelsAreIdentical() throws Exception {
        Path generated = directory("generated");
        Path tracked = directory("tracked");
        Files.write(generated.resolve("same.png"), png(Color.RED, 2, 2));
        byte[] trackedBytes = pngWithText(Color.RED, 2, 2, "old");
        Files.write(tracked.resolve("same.png"), trackedBytes);

        ScreenshotCatalogSynchronizer.Summary summary = synchronize(generated, tracked);

        assertThat(Files.readAllBytes(tracked.resolve("same.png"))).isEqualTo(trackedBytes);
        assertThat(summary).isEqualTo(new ScreenshotCatalogSynchronizer.Summary(1, 0, 0, 0));
    }

    @Test
    void replacesChangedPixels() throws Exception {
        Path generated = directory("generated");
        Path tracked = directory("tracked");
        write(generated.resolve("image.png"), Color.BLUE, 1, 1);
        write(tracked.resolve("image.png"), Color.RED, 1, 1);

        ScreenshotCatalogSynchronizer.Summary summary = synchronize(generated, tracked);

        assertThat(ImageIO.read(tracked.resolve("image.png").toFile()).getRGB(0, 0)).isEqualTo(Color.BLUE.getRGB());
        assertThat(summary.updated()).isEqualTo(1);
    }

    @Test
    void replacesChangedDimensions() throws Exception {
        Path generated = directory("generated");
        Path tracked = directory("tracked");
        write(generated.resolve("image.png"), Color.RED, 2, 1);
        write(tracked.resolve("image.png"), Color.RED, 1, 1);

        ScreenshotCatalogSynchronizer.Summary summary = synchronize(generated, tracked);

        assertThat(ImageIO.read(tracked.resolve("image.png").toFile()).getWidth()).isEqualTo(2);
        assertThat(summary.updated()).isEqualTo(1);
    }

    @Test
    void addsNewImagesAndRemovesOnlyStalePngs() throws Exception {
        Path generated = directory("generated");
        Path tracked = directory("tracked");
        write(generated.resolve("new.png"), Color.BLUE, 1, 1);
        write(tracked.resolve("stale.png"), Color.BLACK, 1, 1);
        Files.writeString(tracked.resolve("README.txt"), "preserve");

        ScreenshotCatalogSynchronizer.Summary summary = synchronize(generated, tracked);

        assertThat(Files.exists(tracked.resolve("new.png"))).isTrue();
        assertThat(Files.exists(tracked.resolve("stale.png"))).isFalse();
        assertThat(Files.readString(tracked.resolve("README.txt"))).isEqualTo("preserve");
        assertThat(summary).isEqualTo(new ScreenshotCatalogSynchronizer.Summary(0, 0, 1, 1));
    }

    @Test
    void rejectsUnexpectedGeneratedFilesBeforeChangingDestination() throws Exception {
        Path generated = directory("generated");
        Path tracked = directory("tracked");
        write(generated.resolve("valid.png"), Color.BLUE, 1, 1);
        Files.writeString(generated.resolve("unexpected.txt"), "unexpected");
        byte[] original = png(Color.RED, 1, 1);
        Files.write(tracked.resolve("valid.png"), original);

        assertThatThrownBy(() -> synchronize(generated, tracked)).hasMessageContaining("non-PNG file");
        assertThat(Files.readAllBytes(tracked.resolve("valid.png"))).isEqualTo(original);
    }

    @Test
    void rejectsGeneratedNestedDirectoriesBeforeChangingDestination() throws Exception {
        Path generated = directory("generated");
        Path tracked = directory("tracked");
        write(generated.resolve("valid.png"), Color.BLUE, 1, 1);
        Files.createDirectory(generated.resolve("nested"));
        byte[] original = png(Color.RED, 1, 1);
        Files.write(tracked.resolve("valid.png"), original);

        assertThatThrownBy(() -> synchronize(generated, tracked)).hasMessageContaining("nested directory");
        assertThat(Files.readAllBytes(tracked.resolve("valid.png"))).isEqualTo(original);
    }

    @Test
    void preservesTrackedNonPngFiles() throws Exception {
        Path generated = directory("generated");
        Path tracked = directory("tracked");
        write(generated.resolve("valid.png"), Color.BLUE, 1, 1);
        Files.writeString(tracked.resolve("README.txt"), "preserve");

        synchronize(generated, tracked);

        assertThat(Files.readString(tracked.resolve("README.txt"))).isEqualTo("preserve");
    }

    @Test
    void rejectsTrackedSymlinkBeforeMutation() throws Exception {
        Path generated = directory("generated");
        Path tracked = directory("tracked");
        write(generated.resolve("valid.png"), Color.BLUE, 1, 1);
        Path outside = Files.createTempFile("outside", ".png");
        try {
            Files.createSymbolicLink(tracked.resolve("link.png"), outside);
        } catch (UnsupportedOperationException | FileSystemException exception) {
            assumeTrue(false, "symbolic links unavailable: " + exception.getMessage());
        }

        assertThatThrownBy(() -> synchronize(generated, tracked)).hasMessageContaining("symbolic link");
    }

    @Test
    void malformedGeneratedImageFailsBeforeChangingDestination() throws Exception {
        Path generated = directory("generated");
        Path tracked = directory("tracked");
        Files.writeString(generated.resolve("bad.png"), "not an image");
        byte[] original = png(Color.RED, 1, 1);
        Files.write(tracked.resolve("valid.png"), original);

        assertThatThrownBy(() -> synchronize(generated, tracked)).hasMessageContaining("Unreadable PNG image");
        assertThat(Files.readAllBytes(tracked.resolve("valid.png"))).isEqualTo(original);
    }

    @Test
    void malformedTrackedImageFailsBeforeChangingDestination() throws Exception {
        Path generated = directory("generated");
        Path tracked = directory("tracked");
        write(generated.resolve("valid.png"), Color.BLUE, 1, 1);
        Files.writeString(tracked.resolve("bad.png"), "not an image");
        byte[] original = png(Color.RED, 1, 1);
        Files.write(tracked.resolve("valid.png"), original);

        assertThatThrownBy(() -> synchronize(generated, tracked)).hasMessageContaining("Unreadable PNG image");
        assertThat(Files.readAllBytes(tracked.resolve("valid.png"))).isEqualTo(original);
    }

    @Test
    void rejectsAbsoluteAndEscapingPaths() throws Exception {
        Path generated = directory("generated");
        Path tracked = directory("tracked");

        assertThatThrownBy(() -> ScreenshotCatalogSynchronizer.synchronize(repository, generated, tracked.resolve("absolute").toAbsolutePath()))
                .hasMessageContaining("must be relative");
        assertThatThrownBy(() -> ScreenshotCatalogSynchronizer.synchronize(repository, Path.of("../outside"), Path.of("tracked")))
                .hasMessageContaining("escapes the repository");
    }

    @Test
    void rejectsSymlinkCatalogsWhenSupported() throws Exception {
        Path outside = Files.createTempDirectory("outside");
        Path link = repository.resolve("generated-link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | FileSystemException exception) {
            assumeTrue(false, "symbolic links unavailable: " + exception.getMessage());
        }
        assertThatThrownBy(() -> ScreenshotCatalogSynchronizer.synchronize(repository, Path.of("generated-link"), Path.of("tracked")))
                .hasMessageContaining("symbolic link");
    }

    private Path directory(String name) throws Exception {
        return Files.createDirectories(repository.resolve(name));
    }

    private ScreenshotCatalogSynchronizer.Summary synchronize(Path generated, Path tracked) {
        return ScreenshotCatalogSynchronizer.synchronize(repository, repository.relativize(generated), repository.relativize(tracked));
    }

    private static void write(Path path, Color color, int width, int height) throws Exception {
        Files.write(path, png(color, width, height));
    }

    private static byte[] png(Color color, int width, int height) throws Exception {
        return pngWithText(color, width, height, null);
    }

    private static byte[] pngWithText(Color color, int width, int height, String text) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, color.getRGB());
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        byte[] bytes = output.toByteArray();
        if (text == null) {
            return bytes;
        }
        byte[] chunk = textChunk(text);
        return join(bytes, chunk, 33);
    }

    private static byte[] textChunk(String text) {
        byte[] data = (text + "\0value").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] type = "tEXt".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        ByteBuffer chunk = ByteBuffer.allocate(12 + data.length);
        chunk.putInt(data.length).put(type).put(data);
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(data);
        chunk.putInt((int) crc.getValue());
        return chunk.array();
    }

    private static byte[] join(byte[] source, byte[] inserted, int offset) {
        byte[] result = new byte[source.length + inserted.length];
        System.arraycopy(source, 0, result, 0, offset);
        System.arraycopy(inserted, 0, result, offset, inserted.length);
        System.arraycopy(source, offset, result, offset + inserted.length, source.length - offset);
        return result;
    }
}

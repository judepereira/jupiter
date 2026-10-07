package com.judepereira.jupiter.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Opt-in benchmark for the CPU image-composition portion of documentation
 * screenshots.
 *
 * <p>
 * This deliberately does not start Playwright or write documentation images.
 * Keep the setup outside the timed region so image processing changes can be
 * compared independently.
 */
class BrowserScreenshotBenchmarkTest {

    private static final int DESKTOP_WIDTH = 1300 * 3;
    private static final int DESKTOP_HEIGHT = 744 * 3;
    private static final int MOBILE_WIDTH = 402 * 3;
    private static final int MOBILE_HEIGHT = 844 * 3;
    private static final int WARMUPS = 1;
    private static final int MEASUREMENTS = 3;
    private static final int DEFAULT_DESKTOP_IMAGES_PER_MEASUREMENT = 1;
    private static final String DESKTOP_IMAGES_PROPERTY = "documentation.screenshot.benchmark.desktop-images";

    @Test
    void benchmarkDocumentationImageProcessing() {
        assumeTrue(Boolean.getBoolean("documentation.screenshot.benchmark"),
                "Set -Ddocumentation.screenshot.benchmark=true to run this benchmark");

        int desktopImages = Integer.getInteger(DESKTOP_IMAGES_PROPERTY, DEFAULT_DESKTOP_IMAGES_PER_MEASUREMENT);
        if (desktopImages < 1) {
            throw new IllegalArgumentException(DESKTOP_IMAGES_PROPERTY + " must be positive");
        }

        BufferedImage desktop = viewport(DESKTOP_WIDTH, DESKTOP_HEIGHT);
        BufferedImage mobile = viewport(MOBILE_WIDTH, MOBILE_HEIGHT);
        for (int i = 0; i < WARMUPS; i++) {
            processWorkload(desktop, mobile, desktopImages);
        }

        List<Long> samples = new ArrayList<>(MEASUREMENTS);
        for (int i = 0; i < MEASUREMENTS; i++) {
            long start = System.nanoTime();
            int checksum = processWorkload(desktop, mobile, desktopImages);
            long elapsed = System.nanoTime() - start;
            assertEquals(desktopImages + 1, checksum);
            samples.add(elapsed);
            System.out.printf("documentation-screenshot benchmark sample %2d: %.3f ms%n", i + 1, elapsed / 1_000_000.0);
        }
        Collections.sort(samples);
        long median = samples.get(samples.size() / 2);
        System.out.printf("documentation-screenshot benchmark median: %.3f ms (%d desktop + 1 mobile)%n",
                median / 1_000_000.0, desktopImages);
    }

    private static int processWorkload(BufferedImage desktop, BufferedImage mobile, int desktopImages) {
        int checksum = 0;
        for (int i = 0; i < desktopImages; i++) {
            checksum += BrowserScreenshot.buildBrowserFramedImage(desktop, "http://localhost:7272").getWidth() > 0
                    ? 1
                    : 0;
        }
        checksum += BrowserScreenshot.buildBrowserFramedImage(mobile, "http://localhost:7272").getWidth() > 0 ? 1 : 0;
        return checksum;
    }

    private static BufferedImage viewport(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(247, 248, 250));
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(new Color(35, 41, 51));
            graphics.fillRect(width / 20, height / 12, width * 3 / 5, height / 10);
            graphics.setColor(new Color(75, 120, 190));
            graphics.fillRect(width / 20, height / 4, width * 4 / 5, height / 24);
        } finally {
            graphics.dispose();
        }
        return image;
    }
}

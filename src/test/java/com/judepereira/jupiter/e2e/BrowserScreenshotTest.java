package com.judepereira.jupiter.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import org.junit.jupiter.api.Test;

class BrowserScreenshotTest {

    @Test
    void preservesDimensionsAndLeavesTransparentShadowBoundary() {
        BufferedImage viewport = viewport(40, 30);

        BufferedImage framed = BrowserScreenshot.buildBrowserFramedImage(viewport, "http://localhost:7272");

        assertEquals(40 + BrowserScreenshot.PADDING_X * 2, framed.getWidth());
        assertEquals(
                BrowserScreenshot.CHROME_HEIGHT + 30 + BrowserScreenshot.PADDING_TOP + BrowserScreenshot.PADDING_BOTTOM,
                framed.getHeight());
        assertEquals(0, framed.getRGB(0, 0) >>> 24);
        assertTrue((framed.getRGB(BrowserScreenshot.PADDING_X - 1,
                BrowserScreenshot.PADDING_TOP + BrowserScreenshot.CHROME_HEIGHT / 2) >>> 24) > 0);
    }

    @Test
    void opencvShadowAlphaMatchesLegacyConvolutionOnEdgesAndCorners() {
        for (double sigma : new double[]{51, 18}) {
            BufferedImage fixture = shadowFixture(96, 80);
            BufferedImage expected = legacyBlur(fixture, sigma);
            BufferedImage actual = BrowserScreenshot.blurShadowAlpha(fixture, sigma);
            int maxDelta = 0;
            for (int y = 0; y < fixture.getHeight(); y++) {
                for (int x = 0; x < fixture.getWidth(); x++) {
                    int expectedAlpha = expected.getRGB(x, y) >>> 24;
                    int actualPixel = actual.getRGB(x, y);
                    maxDelta = Math.max(maxDelta, Math.abs(expectedAlpha - (actualPixel >>> 24)));
                    assertEquals(0, actualPixel & 0x00ffffff);
                }
            }
            assertTrue(maxDelta <= 2, "sigma=" + sigma + ", max alpha delta=" + maxDelta);
            System.out.printf("shadow regression sigma=%.0f max alpha delta=%d%n", sigma, maxDelta);
        }
    }

    @Test
    void preservesFrameContentAndChromeAtUsableViewportWidth() {
        BufferedImage viewport = viewport(300, 120);
        viewport.setRGB(12, 90, 0xff123456);
        BufferedImage framed = BrowserScreenshot.buildBrowserFramedImage(viewport, "url");
        assertEquals(0xff123456, framed.getRGB(BrowserScreenshot.PADDING_X + 12,
                BrowserScreenshot.PADDING_TOP + BrowserScreenshot.CHROME_HEIGHT + 90));
        assertEquals(0xfff5f5f7, framed.getRGB(BrowserScreenshot.PADDING_X + 250, BrowserScreenshot.PADDING_TOP + 30));
    }

    @Test
    void shadowAlphaIsSmoothAndDoesNotTintTransparentPixels() {
        BufferedImage framed = BrowserScreenshot.buildBrowserFramedImage(viewport(40, 30), "url");
        int x = BrowserScreenshot.PADDING_X - 12;
        int y = BrowserScreenshot.PADDING_TOP + BrowserScreenshot.CHROME_HEIGHT / 2;
        int near = framed.getRGB(x + 2, y) >>> 24;
        int far = framed.getRGB(x - 2, y) >>> 24;

        assertTrue(near > far);
        assertEquals(0, framed.getRGB(0, 0) & 0x00ffffff);
    }

    private static BufferedImage shadowFixture(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(0, 0, 0, 58));
            graphics.fillRect(0, 0, width / 2, height / 2);
            graphics.fillRect(width - 18, height - 14, 18, 14);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static BufferedImage legacyBlur(BufferedImage image, double sigma) {
        int radius = (int) Math.ceil(3 * sigma);
        int size = radius * 2 + 1;
        float[] values = new float[size];
        float total = 0;
        for (int i = 0; i < size; i++) {
            int distance = i - radius;
            values[i] = (float) Math.exp(-(distance * distance) / (2.0 * sigma * sigma));
            total += values[i];
        }
        for (int i = 0; i < size; i++)
            values[i] /= total;
        BufferedImage padded = new BufferedImage(image.getWidth() + radius * 2, image.getHeight() + radius * 2,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D paddingGraphics = padded.createGraphics();
        try {
            paddingGraphics.drawImage(image, radius, radius, null);
        } finally {
            paddingGraphics.dispose();
        }
        BufferedImage horizontal = new BufferedImage(padded.getWidth(), padded.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        new ConvolveOp(new Kernel(size, 1, values), ConvolveOp.EDGE_ZERO_FILL, null).filter(padded, horizontal);
        BufferedImage vertical = new BufferedImage(padded.getWidth(), padded.getHeight(), BufferedImage.TYPE_INT_ARGB);
        new ConvolveOp(new Kernel(1, size, values), ConvolveOp.EDGE_ZERO_FILL, null).filter(horizontal, vertical);
        BufferedImage result = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D resultGraphics = result.createGraphics();
        try {
            resultGraphics.drawImage(vertical, -radius, -radius, null);
        } finally {
            resultGraphics.dispose();
        }
        return result;
    }

    private static BufferedImage viewport(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(240, 241, 243));
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        return image;
    }
}

package com.judepereira.jupiter.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class BrowserScreenshotTest {

    private static final String URL = "http://localhost:7272";

    private static int alphaAt(BufferedImage image, int x, int y) {
        return new Color(image.getRGB(x, y), true).getAlpha();
    }

    @Test
    void shadowFadesSmoothlyThroughBottomPadding() {
        BufferedImage viewport = new BufferedImage(1_200, 1_800, BufferedImage.TYPE_INT_RGB);

        BufferedImage framed = BrowserScreenshot.buildBrowserFramedImage(viewport, URL);
        int x = framed.getWidth() / 2;
        int frameBottom = BrowserScreenshot.PADDING_TOP + BrowserScreenshot.CHROME_HEIGHT + viewport.getHeight();
        int cutoff = framed.getHeight() - BrowserScreenshot.SHADOW_RADIUS;
        assertTrue(alphaAt(framed, x, cutoff) > 0, "shadow must not be cut off at the old convolution edge");
        assertTrue(alphaAt(framed, x, cutoff + 1) > 0, "shadow must continue beyond the old convolution edge");
        assertTrue(alphaAt(framed, x, cutoff + 1) <= alphaAt(framed, x, cutoff));
        int nonZeroPixels = 0;
        for (int y = frameBottom; y < framed.getHeight(); y++) {
            if (alphaAt(framed, x, y) > 0) {
                nonZeroPixels++;
            }
        }
        assertTrue(nonZeroPixels > BrowserScreenshot.SHADOW_RADIUS / 2,
                "shadow should remain visible across the bottom padding");
        assertEquals(0, alphaAt(framed, x, framed.getHeight() - 1));
    }

    @Test
    void shadowRemainsContinuousAtSidePadding() {
        BufferedImage viewport = new BufferedImage(1_200, 1_800, BufferedImage.TYPE_INT_RGB);
        BufferedImage framed = BrowserScreenshot.buildBrowserFramedImage(viewport, URL);
        int y = BrowserScreenshot.PADDING_TOP + BrowserScreenshot.CHROME_HEIGHT + viewport.getHeight() / 2;

        assertTrue(alphaAt(framed, BrowserScreenshot.PADDING_X - 1, y) > 0);
        assertTrue(alphaAt(framed, framed.getWidth() - BrowserScreenshot.PADDING_X, y) > 0);
    }

    @Test
    void framesViewportWithComputedPaddingAndChrome() {
        BufferedImage viewport = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        Color content = new Color(23, 42, 66);
        for (int y = 0; y < viewport.getHeight(); y++) {
            for (int x = 0; x < viewport.getWidth(); x++) {
                viewport.setRGB(x, y, content.getRGB());
            }
        }

        BufferedImage framed = BrowserScreenshot.buildBrowserFramedImage(viewport, URL);

        assertEquals(512, framed.getWidth());
        assertEquals(622, framed.getHeight());
        assertEquals(0, new Color(framed.getRGB(0, 0), true).getAlpha());
        assertEquals(content.getRGB(), framed.getRGB(BrowserScreenshot.PADDING_X,
                BrowserScreenshot.PADDING_TOP + BrowserScreenshot.CHROME_HEIGHT));
        assertEquals(new Color(245, 245, 247).getRGB(),
                framed.getRGB(BrowserScreenshot.PADDING_X + 80, BrowserScreenshot.PADDING_TOP + 5));
        assertEquals(new Color(255, 95, 87).getRGB(),
                framed.getRGB(BrowserScreenshot.PADDING_X + 75, BrowserScreenshot.PADDING_TOP + 96));
        assertEquals(new Color(255, 189, 46).getRGB(),
                framed.getRGB(BrowserScreenshot.PADDING_X + 140, BrowserScreenshot.PADDING_TOP + 96));
        assertEquals(new Color(39, 201, 63).getRGB(),
                framed.getRGB(BrowserScreenshot.PADDING_X + 190, BrowserScreenshot.PADDING_TOP + 96));
        assertNotEquals(0, new Color(framed.getRGB(BrowserScreenshot.PADDING_X - 1,
                BrowserScreenshot.PADDING_TOP + BrowserScreenshot.CHROME_HEIGHT + 30), true).getAlpha());
        assertNotEquals(0, new Color(
                framed.getRGB(BrowserScreenshot.PADDING_X + viewport.getWidth() / 2, BrowserScreenshot.PADDING_TOP - 1),
                true).getAlpha());
    }
}

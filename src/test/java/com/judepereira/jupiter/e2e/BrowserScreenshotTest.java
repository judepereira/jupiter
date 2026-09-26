package com.judepereira.jupiter.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.awt.Color;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class BrowserScreenshotTest {

    private static final String URL = "http://localhost:7272";

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

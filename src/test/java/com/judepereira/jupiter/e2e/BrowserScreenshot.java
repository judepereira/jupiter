package com.judepereira.jupiter.e2e;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;

/**
 * Composes a browser-style frame around a captured viewport for documentation.
 */
public final class BrowserScreenshot {

    static final double FRAME_SCALE = 3.0;
    static final int CHROME_HEIGHT = scale(70);
    static final int CORNER_RADIUS = scale(26);
    static final int SHADOW_MARGIN = scale(1);
    static final double SHADOW_SIGMA = 17 * FRAME_SCALE;
    static final int SHADOW_RADIUS = (int) Math.ceil(3 * SHADOW_SIGMA);
    static final int PADDING_X = SHADOW_RADIUS + SHADOW_MARGIN;
    static final int PADDING_TOP = SHADOW_RADIUS - scale(12) + SHADOW_MARGIN;
    static final int PADDING_BOTTOM = SHADOW_RADIUS + scale(12) + SHADOW_MARGIN;

    private static final Color CHROME = new Color(245, 245, 247);
    private static final Color ADDRESS_BORDER = new Color(220, 220, 223);
    private static final Color ADDRESS_TEXT = new Color(90, 90, 90);
    private static final String FONT = Font.SANS_SERIF;

    private BrowserScreenshot() {
    }

    public static BufferedImage buildBrowserFramedImage(BufferedImage viewport, String url) {
        int frameWidth = viewport.getWidth();
        int frameHeight = CHROME_HEIGHT + viewport.getHeight();
        int outputWidth = frameWidth + PADDING_X * 2;
        int outputHeight = frameHeight + PADDING_TOP + PADDING_BOTTOM;
        BufferedImage result = new BufferedImage(outputWidth, outputHeight, BufferedImage.TYPE_INT_ARGB);

        int frameX = PADDING_X;
        int frameY = PADDING_TOP;
        drawShadow(result, frameX, frameY, frameWidth, frameHeight, SHADOW_SIGMA, scale(12), 58);
        drawShadow(result, frameX, frameY, frameWidth, frameHeight, 6 * FRAME_SCALE, scale(4), 38);

        Graphics2D graphics = result.createGraphics();
        try {
            configure(graphics);
            Shape frame = new RoundRectangle2D.Double(frameX, frameY, frameWidth, frameHeight, CORNER_RADIUS,
                    CORNER_RADIUS);
            graphics.setColor(Color.WHITE);
            graphics.fill(frame);
            graphics.clip(frame);
            graphics.setColor(CHROME);
            graphics.fillRect(frameX, frameY, frameWidth, CHROME_HEIGHT);

            int addressX = frameX + scale(100);
            int addressY = frameY + scale(15);
            int addressWidth = frameWidth - scale(120);
            Shape addressBar = new RoundRectangle2D.Double(addressX, addressY, addressWidth, scale(34), scale(14),
                    scale(14));
            graphics.setColor(Color.WHITE);
            graphics.fill(addressBar);
            graphics.setColor(ADDRESS_BORDER);
            graphics.setStroke(new BasicStroke(scale(1)));
            graphics.draw(addressBar);
            graphics.setColor(ADDRESS_TEXT);
            graphics.setFont(new Font(FONT, Font.PLAIN, scale(14)));
            graphics.drawString(url, addressX + scale(15), addressY + scale(22));

            drawTrafficLight(graphics, frameX + scale(18), frameY + scale(25), new Color(255, 95, 87));
            drawTrafficLight(graphics, frameX + scale(40), frameY + scale(25), new Color(255, 189, 46));
            drawTrafficLight(graphics, frameX + scale(62), frameY + scale(25), new Color(39, 201, 63));
            graphics.drawImage(viewport, frameX, frameY + CHROME_HEIGHT, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private static void drawTrafficLight(Graphics2D graphics, int x, int y, Color color) {
        graphics.setColor(color);
        graphics.fillOval(x, y, scale(14), scale(14));
    }

    private static void drawShadow(BufferedImage result, int x, int y, int width, int height, double sigma, int offsetY,
            int alpha) {
        BufferedImage shadow = new BufferedImage(result.getWidth(), result.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = shadow.createGraphics();
        try {
            configure(graphics);
            graphics.setColor(new Color(0, 0, 0, alpha));
            graphics.fillRoundRect(x, y + offsetY, width, height, CORNER_RADIUS, CORNER_RADIUS);
        } finally {
            graphics.dispose();
        }
        BufferedImage blurred = blur(shadow, sigma);
        Graphics2D destination = result.createGraphics();
        try {
            destination.drawImage(blurred, 0, 0, null);
        } finally {
            destination.dispose();
        }
    }

    private static BufferedImage blur(BufferedImage image, double sigma) {
        int radius = (int) Math.ceil(3 * sigma);
        int size = radius * 2 + 1;
        float[] values = new float[size];
        float total = 0;
        for (int i = 0; i < size; i++) {
            int distance = i - radius;
            values[i] = (float) Math.exp(-(distance * distance) / (2.0 * sigma * sigma));
            total += values[i];
        }
        for (int i = 0; i < size; i++) {
            values[i] /= total;
        }

        // ConvolveOp zero-fills the destination edge, so guard the source with a full
        // kernel radius.
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

        BufferedImage cropped = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D cropGraphics = cropped.createGraphics();
        try {
            cropGraphics.drawImage(vertical, -radius, -radius, null);
        } finally {
            cropGraphics.dispose();
        }
        return cropped;
    }

    private static int scale(double value) {
        return (int) Math.round(value * FRAME_SCALE);
    }

    private static void configure(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION,
                RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setComposite(AlphaComposite.SrcOver);
    }
}

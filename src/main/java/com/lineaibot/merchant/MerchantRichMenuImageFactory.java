package com.lineaibot.merchant;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

@Component
public class MerchantRichMenuImageFactory {

    static final int WIDTH = 2500;
    static final int HEIGHT = 1686;
    private static final int HALF_WIDTH = WIDTH / 2;
    private static final int HALF_HEIGHT = HEIGHT / 2;

    public byte[] create(String role) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            drawTile(
                    graphics,
                    0,
                    0,
                    new Color(32, 74, 69),
                    "OWNER".equals(role) ? "PORTAL" : "CALENDAR",
                    "OWNER".equals(role) ? "FULL ADMIN" : "MANAGE");
            drawTile(
                    graphics,
                    HALF_WIDTH,
                    0,
                    new Color(203, 111, 76),
                    "TODAY",
                    "RESERVATIONS");
            drawTile(
                    graphics,
                    0,
                    HALF_HEIGHT,
                    new Color(215, 177, 104),
                    "7 DAYS",
                    "RESERVATIONS");
            drawTile(
                    graphics,
                    HALF_WIDTH,
                    HALF_HEIGHT,
                    new Color(65, 106, 121),
                    "BOOK",
                    "CUSTOMER MODE");
            graphics.setColor(new Color(250, 247, 239));
            graphics.setStroke(new BasicStroke(10));
            graphics.drawLine(HALF_WIDTH, 0, HALF_WIDTH, HEIGHT);
            graphics.drawLine(0, HALF_HEIGHT, WIDTH, HALF_HEIGHT);
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to render staff rich menu image", exception);
        }
    }

    private void drawTile(
            Graphics2D graphics,
            int x,
            int y,
            Color background,
            String title,
            String subtitle) {
        graphics.setColor(background);
        graphics.fillRect(x, y, HALF_WIDTH, HALF_HEIGHT);
        graphics.setColor(new Color(250, 247, 239));
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 112));
        drawCentered(graphics, title, x, y + 315, HALF_WIDTH);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 48));
        drawCentered(graphics, subtitle, x, y + 415, HALF_WIDTH);
    }

    private void drawCentered(
            Graphics2D graphics, String text, int x, int baseline, int width) {
        FontMetrics metrics = graphics.getFontMetrics();
        int left = x + (width - metrics.stringWidth(text)) / 2;
        graphics.drawString(text, left, baseline);
    }
}

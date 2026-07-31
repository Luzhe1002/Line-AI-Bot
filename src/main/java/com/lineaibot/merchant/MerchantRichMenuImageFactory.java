package com.lineaibot.merchant;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

@Component
public class MerchantRichMenuImageFactory {

    static final int WIDTH = 2500;
    static final int HEIGHT = 1686;
    private static final int HALF_WIDTH = WIDTH / 2;
    private static final int HALF_HEIGHT = HEIGHT / 2;
    private static final String FONT_SAMPLE =
            "管理後台完整設定預約管理查看月曆今日預約查看行程未來七天顧客預約建立";
    private static final Font CJK_FONT = resolveCjkFont();

    public byte[] create(String role) {
        List<String> labels = labelsForRole(role);
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
                    labels.get(0),
                    labels.get(1));
            drawTile(
                    graphics,
                    HALF_WIDTH,
                    0,
                    new Color(203, 111, 76),
                    labels.get(2),
                    labels.get(3));
            drawTile(
                    graphics,
                    0,
                    HALF_HEIGHT,
                    new Color(215, 177, 104),
                    labels.get(4),
                    labels.get(5));
            drawTile(
                    graphics,
                    HALF_WIDTH,
                    HALF_HEIGHT,
                    new Color(65, 106, 121),
                    labels.get(6),
                    labels.get(7));
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
        graphics.setFont(CJK_FONT.deriveFont(Font.BOLD, 112f));
        drawCentered(graphics, title, x, y + 315, HALF_WIDTH);
        graphics.setFont(CJK_FONT.deriveFont(Font.PLAIN, 48f));
        drawCentered(graphics, subtitle, x, y + 415, HALF_WIDTH);
    }

    private void drawCentered(
            Graphics2D graphics, String text, int x, int baseline, int width) {
        FontMetrics metrics = graphics.getFontMetrics();
        int left = x + (width - metrics.stringWidth(text)) / 2;
        graphics.drawString(text, left, baseline);
    }

    static List<String> labelsForRole(String role) {
        boolean owner = "OWNER".equals(role);
        return List.of(
                owner ? "管理後台" : "預約管理",
                owner ? "完整設定" : "查看月曆",
                "今日預約",
                "查看行程",
                "未來七天",
                "預約行程",
                "顧客預約",
                "建立預約");
    }

    private static Font resolveCjkFont() {
        for (String family : List.of(
                "Noto Sans CJK TC",
                "Noto Sans TC",
                "Microsoft JhengHei",
                "PingFang TC",
                Font.SANS_SERIF)) {
            Font font = new Font(family, Font.PLAIN, 12);
            if (font.canDisplayUpTo(FONT_SAMPLE) == -1) {
                return font;
            }
        }
        for (String family : GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames()) {
            Font font = new Font(family, Font.PLAIN, 12);
            if (font.canDisplayUpTo(FONT_SAMPLE) == -1) {
                return font;
            }
        }
        throw new IllegalStateException(
                "A CJK-capable font is required to render Chinese LINE rich menu labels");
    }
}

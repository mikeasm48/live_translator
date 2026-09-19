import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;

/** Рисует иконку приложения: речевая выноска с двумя языками. */
public class MakeIcon {

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "icon.iconset");
        dir.mkdirs();
        int[] sizes = {16, 32, 64, 128, 256, 512, 1024};
        for (int size : sizes) {
            ImageIO.write(draw(size), "png", new File(dir, "icon_" + size + "x" + size + ".png"));
            if (size > 16) {
                ImageIO.write(draw(size), "png",
                        new File(dir, "icon_" + (size / 2) + "x" + (size / 2) + "@2x.png"));
            }
        }
        System.out.println("готово: " + dir.getAbsolutePath());
    }

    static BufferedImage draw(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        double s = size / 1024.0;
        // Подложка в стиле macOS: скруглённый квадрат с мягким градиентом.
        Shape plate = new RoundRectangle2D.Double(80 * s, 80 * s, 864 * s, 864 * s,
                200 * s, 200 * s);
        g.setPaint(new GradientPaint(0, 0, new Color(0x3B, 0x82, 0xF6),
                0, size, new Color(0x1E, 0x3A, 0x8A)));
        g.fill(plate);

        // Две выноски: одна говорит, другая отвечает переводом.
        g.setColor(new Color(0xFF, 0xFF, 0xFF, 235));
        g.fill(bubble(220 * s, 250 * s, 520 * s, 300 * s, 90 * s, true));
        g.setColor(new Color(0x7D, 0xD3, 0xFC, 245));
        g.fill(bubble(300 * s, 560 * s, 520 * s, 300 * s, 90 * s, false));

        g.setColor(new Color(0x1E, 0x3A, 0x8A));
        drawCentered(g, "uz", 220 * s + 260 * s, 250 * s + 195 * s, (int) (150 * s));
        g.setColor(new Color(0x0C, 0x2B, 0x63));
        drawCentered(g, "ru", 300 * s + 260 * s, 560 * s + 195 * s, (int) (150 * s));

        g.dispose();
        return image;
    }

    /** Прямоугольник со скруглением и хвостиком выноски. */
    static Shape bubble(double x, double y, double w, double h, double r, boolean tailLeft) {
        Area area = new Area(new RoundRectangle2D.Double(x, y, w, h, r, r));
        Path2D tail = new Path2D.Double();
        double ty = y + h;
        if (tailLeft) {
            tail.moveTo(x + r, ty - 10);
            tail.lineTo(x + r * 0.4, ty + r * 0.9);
            tail.lineTo(x + r * 1.8, ty - 10);
        } else {
            tail.moveTo(x + w - r, ty - 10);
            tail.lineTo(x + w - r * 0.4, ty + r * 0.9);
            tail.lineTo(x + w - r * 1.8, ty - 10);
        }
        tail.closePath();
        area.add(new Area(tail));
        return area;
    }

    static void drawCentered(Graphics2D g, String text, double cx, double cy, int fontSize) {
        g.setFont(new Font("Helvetica Neue", Font.BOLD, Math.max(fontSize, 4)));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(text, (float) (cx - fm.stringWidth(text) / 2.0),
                (float) (cy + fm.getAscent() / 2.5));
    }
}

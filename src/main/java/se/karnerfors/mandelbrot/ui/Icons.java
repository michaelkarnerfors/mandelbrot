package se.karnerfors.mandelbrot.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

import javax.swing.Icon;
import javax.swing.UIManager;

/**
 * The app's icon set, drawn as vectors on a 24 x 24 grid with a 1.8 stroke and round caps,
 * the same as the design's icon sheet. Icons take the colour of the component they are
 * painted on, so theme changes and disabled states need no extra work.
 */
public final class Icons {

    public enum Glyph {
        HOME, BACK, RENDER, CHEVRON_DOWN, CHEVRON_UP, CHEVRON_LEFT, CHEVRON_RIGHT, TARGET, SUN, MOON, CHECK, PLUS, MINUS, CLOSE, EDIT, TRASH, STAR, UPLOAD, SEARCH, OPEN, FOLDER, ALERT, COPY, SPLIT
    }

    private Icons() {
    }

    public static Icon of(Glyph glyph) {
        return of(glyph, 16);
    }

    public static Icon of(Glyph glyph, int size) {
        return new VectorIcon(glyph, size);
    }

    private static final class VectorIcon implements Icon {
        private final Glyph glyph;
        private final int size;

        VectorIcon(Glyph glyph, int size) {
            this.glyph = glyph;
            this.size = size;
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Color color;
            if (c != null && !c.isEnabled()) {
                Color disabled = UIManager.getColor("Button.disabledText");
                color = disabled != null ? disabled : Color.GRAY;
            } else {
                color = c != null ? c.getForeground() : Color.DARK_GRAY;
            }
            paint(glyph, g, x, y, size, color);
        }
    }

    /** Paints {@code glyph} with its top-left corner at (x, y), {@code size} pixels square, in {@code color}. */
    public static void paint(Glyph glyph, Graphics g, int x, int y, int size, Color color) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.translate(x, y);
            double scale = size / 24.0;
            g2.scale(scale, scale);
            g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(color);
            for (Shape shape : shapes(glyph)) {
                g2.draw(shape);
            }
        } finally {
            g2.dispose();
        }
    }

    private static Shape[] shapes(Glyph glyph) {
        switch (glyph) {
            case HOME:
                return new Shape[] { poly(false, 3, 11, 12, 3, 21, 11), poly(false, 5, 10, 5, 20, 19, 20, 19, 10) };
            case BACK:
                return new Shape[] { poly(false, 15, 18, 9, 12, 15, 6) };
            case RENDER:
                return new Shape[] { new Line2D.Double(12, 4, 12, 15), poly(false, 7, 11, 12, 16, 17, 11),
                        new Line2D.Double(4, 20, 20, 20) };
            case CHEVRON_DOWN:
                return new Shape[] { poly(false, 6, 9, 12, 15, 18, 9) };
            case CHEVRON_UP:
                return new Shape[] { poly(false, 6, 15, 12, 9, 18, 15) };
            case CHEVRON_LEFT:
                return new Shape[] { poly(false, 15, 6, 9, 12, 15, 18) };
            case CHEVRON_RIGHT:
                return new Shape[] { poly(false, 9, 6, 15, 12, 9, 18) };
            case PLUS:
                return new Shape[] { new Line2D.Double(12, 5, 12, 19), new Line2D.Double(5, 12, 19, 12) };
            case MINUS:
                return new Shape[] { new Line2D.Double(5, 12, 19, 12) };
            case CLOSE:
                return new Shape[] { new Line2D.Double(6, 6, 18, 18), new Line2D.Double(18, 6, 6, 18) };
            case EDIT:
                return new Shape[] { poly(true, 4, 20, 8, 19, 19, 8, 16, 5, 5, 16) };
            case TRASH:
                return new Shape[] { new Line2D.Double(5, 7, 19, 7), poly(false, 10, 7, 10, 4, 14, 4, 14, 7),
                        poly(false, 7, 7, 8, 20, 16, 20, 17, 7) };
            case STAR:
                return new Shape[] { poly(true, 12, 3, 14.8, 9, 21, 9.7, 16.4, 14, 17.7, 20.5, 12, 17.3, 6.3, 20.5, 7.6, 14,
                        3, 9.7, 9.2, 9) };
            case UPLOAD:
                return new Shape[] { new Line2D.Double(12, 16, 12, 4), poly(false, 7, 9, 12, 4, 17, 9),
                        new Line2D.Double(4, 20, 20, 20) };
            case OPEN:
                return new Shape[] { new Line2D.Double(5, 12, 19, 12), poly(false, 13, 6, 19, 12, 13, 18) };
            case FOLDER:
                return new Shape[] { poly(true, 3, 7, 9, 7, 11, 9.5, 21, 9.5, 21, 19, 3, 19) };
            case ALERT:
                return new Shape[] { new Ellipse2D.Double(3, 3, 18, 18), new Line2D.Double(12, 7.5, 12, 13),
                        new Line2D.Double(12, 16.5, 12, 16.6) };
            case SEARCH:
                return new Shape[] { new Ellipse2D.Double(4, 4, 12, 12), new Line2D.Double(14, 14, 20, 20) };
            case CHECK:
                return new Shape[] { poly(false, 5, 12, 10, 17, 19, 7) };
            case TARGET:
                return new Shape[] { new Ellipse2D.Double(4, 4, 16, 16), new Ellipse2D.Double(9.5, 9.5, 5, 5),
                        new Line2D.Double(12, 2, 12, 5), new Line2D.Double(12, 19, 12, 22),
                        new Line2D.Double(2, 12, 5, 12), new Line2D.Double(19, 12, 22, 12) };
            case SUN: {
                Shape[] shapes = new Shape[9];
                shapes[0] = new Ellipse2D.Double(8, 8, 8, 8);
                for (int i = 0; i < 8; i++) {
                    double angle = Math.PI / 4 * i;
                    double cos = Math.cos(angle);
                    double sin = Math.sin(angle);
                    shapes[i + 1] = new Line2D.Double(12 + 7 * cos, 12 + 7 * sin, 12 + 9.5 * cos, 12 + 9.5 * sin);
                }
                return shapes;
            }
            case MOON: {
                Area crescent = new Area(new Ellipse2D.Double(3.5, 3.5, 17, 17));
                crescent.subtract(new Area(new Ellipse2D.Double(9.5, 0.5, 14, 14)));
                return new Shape[] { crescent };
            }
            case COPY:
                return new Shape[] { new RoundRectangle2D.Double(9, 9, 11, 11, 4, 4),
                        poly(false, 5, 15, 5, 6, 7, 4, 16, 4) };
            case SPLIT:
                return new Shape[] { new RoundRectangle2D.Double(3, 4, 18, 16, 4, 4), new Line2D.Double(12, 4, 12, 20) };
            default:
                return new Shape[0];
        }
    }

    private static Path2D poly(boolean close, double... xy) {
        Path2D.Double path = new Path2D.Double();
        path.moveTo(xy[0], xy[1]);
        for (int i = 2; i + 1 < xy.length; i += 2) {
            path.lineTo(xy[i], xy[i + 1]);
        }
        if (close) {
            path.closePath();
        }
        return path;
    }
}

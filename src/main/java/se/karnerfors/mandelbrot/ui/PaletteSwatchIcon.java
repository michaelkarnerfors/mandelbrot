package se.karnerfors.mandelbrot.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import java.util.function.Supplier;

import javax.swing.Icon;

import se.karnerfors.mandelbrot.palette.Palette;
import se.karnerfors.mandelbrot.ui.Theme.Token;

/** A small gradient strip showing the palette that is currently active; it reads the palette each time it paints. */
public final class PaletteSwatchIcon implements Icon {

    private static final int WIDTH = 44;
    private static final int HEIGHT = 14;

    private final Supplier<Palette> palette;

    public PaletteSwatchIcon(Supplier<Palette> palette) {
        this.palette = palette;
    }

    @Override
    public int getIconWidth() {
        return WIDTH;
    }

    @Override
    public int getIconHeight() {
        return HEIGHT;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Palette current = palette.get();
        if (current == null) {
            return;
        }
        Color[] gradient = current.gradient();
        if (gradient.length == 0) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Shape clip = new RoundRectangle2D.Float(x, y, WIDTH, HEIGHT, 6, 6);
            g2.clip(clip);
            for (int px = 0; px < WIDTH; px++) {
                int index = Math.min(gradient.length - 1, px * gradient.length / WIDTH);
                g2.setColor(gradient[index]);
                g2.fillRect(x + px, y, 1, HEIGHT);
            }
            g2.setClip(null);
            g2.setColor(Theme.withAlpha(Theme.color(Token.TEXT), 46));
            g2.draw(new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, WIDTH - 1, HEIGHT - 1, 5, 5));
        } finally {
            g2.dispose();
        }
    }
}

package se.karnerfors.mandelbrot.render;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import se.karnerfors.mandelbrot.palette.Palette;

public final class Colorizer {

    private Colorizer() {
    }

    public static BufferedImage colorize(IterationGrid grid, Palette palette) {
        return colorizeRegion(grid, palette, new Rectangle(0, 0, grid.width(), grid.height()));
    }

    /**
     * Colorizes just a sub-rectangle of {@code grid} into a standalone image
     * sized to the region (not the whole grid). Used by the parallel renderer
     * to turn one completed tile into paintable pixels without touching the
     * rest of the grid -- cheap enough to do per tile, on a worker thread.
     */
    public static BufferedImage colorizeRegion(IterationGrid grid, Palette palette, Rectangle region) {
        BufferedImage image = new BufferedImage(region.width, region.height, BufferedImage.TYPE_INT_ARGB);

        for (int lx = 0; lx < region.width; lx++) {
            for (int ly = 0; ly < region.height; ly++) {
                int x = region.x + lx;
                int y = region.y + ly;
                Color color = grid.isEscaped(x, y)
                        ? palette.colorFor(grid.smoothIterationCountAt(x, y), grid.nuAt(x, y))
                        : Color.BLACK;
                image.setRGB(lx, ly, color.getRGB());
            }
        }

        return image;
    }
}

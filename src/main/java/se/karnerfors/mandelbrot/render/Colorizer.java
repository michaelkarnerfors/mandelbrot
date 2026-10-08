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

    /**
     * A quick recolour for live palette editing: one sample per final pixel (the centre cell of each
     * {@code factor} x {@code factor} block) instead of averaging them all. The result has the final
     * image size and is exact when {@code factor} is 1.
     */
    public static BufferedImage colorizeSampled(IterationGrid grid, Palette palette, int factor) {
        int f = Math.max(1, factor);
        int outWidth = grid.width() / f;
        int outHeight = grid.height() / f;
        BufferedImage image = new BufferedImage(outWidth, outHeight, BufferedImage.TYPE_INT_ARGB);
        for (int ox = 0; ox < outWidth; ox++) {
            for (int oy = 0; oy < outHeight; oy++) {
                int x = ox * f + f / 2;
                int y = oy * f + f / 2;
                Color color = grid.isEscaped(x, y)
                        ? palette.colorFor(grid.smoothIterationCountAt(x, y), grid.nuAt(x, y))
                        : Color.BLACK;
                image.setRGB(ox, oy, color.getRGB());
            }
        }
        return image;
    }

    /**
     * Box-filters {@code source} down by {@code factor} in each axis --
     * source.width/height must be exact multiples of factor (true whenever
     * source came from Viewport.supersampled(factor)). Averages in the same
     * gamma-approximated way as Palette's own blend() (sum of squares, then
     * sqrt) rather than naive linear averaging, to stay visually consistent
     * with the rest of the color pipeline.
     */
    public static BufferedImage downsample(BufferedImage source, int factor) {
        if (factor <= 1) {
            return source;
        }

        int outWidth = source.getWidth() / factor;
        int outHeight = source.getHeight() / factor;
        BufferedImage result = new BufferedImage(outWidth, outHeight, BufferedImage.TYPE_INT_ARGB);
        int sampleCount = factor * factor;

        for (int ox = 0; ox < outWidth; ox++) {
            for (int oy = 0; oy < outHeight; oy++) {
                double sumR2 = 0, sumG2 = 0, sumB2 = 0;
                int baseX = ox * factor;
                int baseY = oy * factor;
                for (int dx = 0; dx < factor; dx++) {
                    for (int dy = 0; dy < factor; dy++) {
                        int rgb = source.getRGB(baseX + dx, baseY + dy);
                        double r = ((rgb >> 16) & 0xFF) / 255.0;
                        double g = ((rgb >> 8) & 0xFF) / 255.0;
                        double b = (rgb & 0xFF) / 255.0;
                        sumR2 += r * r;
                        sumG2 += g * g;
                        sumB2 += b * b;
                    }
                }
                float r = (float) Math.sqrt(sumR2 / sampleCount);
                float g = (float) Math.sqrt(sumG2 / sampleCount);
                float b = (float) Math.sqrt(sumB2 / sampleCount);
                result.setRGB(ox, oy, new Color(r, g, b).getRGB());
            }
        }

        return result;
    }
}

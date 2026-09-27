package se.karnerfors.mandelbrot.render;

import java.awt.Color;
import java.awt.image.BufferedImage;

import se.karnerfors.mandelbrot.palette.Palette;

public final class Colorizer {

    private Colorizer() {
    }

    public static BufferedImage colorize(IterationGrid grid, Palette palette) {
        BufferedImage image = new BufferedImage(grid.width(), grid.height(), BufferedImage.TYPE_INT_ARGB);

        for (int x = 0; x < grid.width(); x++) {
            for (int y = 0; y < grid.height(); y++) {
                Color color = grid.isEscaped(x, y)
                        ? palette.colorFor(grid.smoothIterationCountAt(x, y), grid.nuAt(x, y))
                        : Color.BLACK;
                image.setRGB(x, y, color.getRGB());
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

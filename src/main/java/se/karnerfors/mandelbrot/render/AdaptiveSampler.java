package se.karnerfors.mandelbrot.render;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

import se.karnerfors.mandelbrot.math.Viewport;
import se.karnerfors.mandelbrot.palette.Palette;

/**
 * Adaptive super-sampling. Every pixel has one sample, its base cell. Only pixels whose colour differs noticeably
 * from a neighbour's get the full n x n grid of samples, centred on the pixel; the rest keep their base colour.
 * Smooth areas, usually most of the picture, then cost one sample per pixel instead of n x n.
 *
 * <p>All rectangles are in the base grid's own cells. The base grid may be a window onto a larger picture (an
 * export tile with its margin): {@code originX}, {@code originY} say which picture pixel its cell (0, 0) is.
 */
public final class AdaptiveSampler {

    /** Colour difference to a neighbour (largest channel, 0-255) above which a pixel is refined. */
    public static final int DEFAULT_THRESHOLD = 12;

    /** Base cells needed around a region: one ring for the neighbour test, one for widening the mask. */
    public static final int MARGIN = 2;

    private static final String THRESHOLD_PROPERTY = "mandelbrot.adaptiveThreshold";

    private AdaptiveSampler() {
    }

    /**
     * The extra samples of one region: the refined pixels (row-major indices within the region, ascending) and
     * their samples, one row of {@code factor} x {@code factor} cells per pixel.
     */
    public record Refinement(int factor, int[] pixels, IterationGrid samples) {

        public static Refinement none(int factor) {
            return new Refinement(factor, new int[0], new IterationGrid(factor * factor, 0));
        }

        public int count() {
            return pixels.length;
        }

        /** True when pixel {@code index} (row-major within the region) is refined. */
        public boolean contains(int index) {
            return find(index) >= 0;
        }

        /** Where pixel {@code index} is in {@link #pixels()}, or a negative number when it is not refined. */
        int find(int index) {
            return Arrays.binarySearch(pixels, index);
        }
    }

    /**
     * The colour difference threshold in use: {@value #DEFAULT_THRESHOLD}, or {@code -Dmandelbrot.adaptiveThreshold}.
     * 0 refines every pixel, which is uniform super-sampling.
     */
    public static int threshold() {
        String value = System.getProperty(THRESHOLD_PROPERTY);
        if (value != null) {
            try {
                return Math.max(0, Integer.parseInt(value.trim()));
            } catch (NumberFormatException ex) {
                // fall through to the default
            }
        }
        return DEFAULT_THRESHOLD;
    }

    /** Sample {@code i} of {@code n} along one axis, in pixels from the pixel's own point: centred on it. */
    static double offset(int i, int n) {
        return (i + 0.5) / n - 0.5;
    }

    /** Which pixels of {@code region} to refine (row-major, region-sized), widened by one pixel. */
    public static boolean[] mask(IterationGrid base, Palette palette, Rectangle region, int threshold) {
        return mask(base, palette, region, threshold, true);
    }

    static boolean[] mask(IterationGrid base, Palette palette, Rectangle region, int threshold, boolean widen) {
        boolean[] result = new boolean[region.width * region.height];
        if (threshold <= 0) {
            Arrays.fill(result, true);
            return result;
        }
        Rectangle bounds = new Rectangle(0, 0, base.width(), base.height());
        Rectangle edgeArea = grown(region, widen ? 1 : 0).intersection(bounds);
        Rectangle colourArea = grown(edgeArea, 1).intersection(bounds);

        int[] rgb = new int[colourArea.width * colourArea.height];
        for (int y = 0; y < colourArea.height; y++) {
            for (int x = 0; x < colourArea.width; x++) {
                rgb[y * colourArea.width + x] = colour(base, palette, colourArea.x + x, colourArea.y + y);
            }
        }
        int shades = Math.max(1, palette.shadesPerSegment());

        boolean[] edge = new boolean[edgeArea.width * edgeArea.height];
        for (int y = edgeArea.y; y < edgeArea.y + edgeArea.height; y++) {
            for (int x = edgeArea.x; x < edgeArea.x + edgeArea.width; x++) {
                int here = rgb[(y - colourArea.y) * colourArea.width + (x - colourArea.x)];
                neighbours:
                for (int ny = y - 1; ny <= y + 1; ny++) {
                    for (int nx = x - 1; nx <= x + 1; nx++) {
                        if ((nx == x && ny == y) || !colourArea.contains(nx, ny)) {
                            continue;
                        }
                        int there = rgb[(ny - colourArea.y) * colourArea.width + (nx - colourArea.x)];
                        if (differs(base, x, y, nx, ny, here, there, threshold, shades)) {
                            edge[(y - edgeArea.y) * edgeArea.width + (x - edgeArea.x)] = true;
                            break neighbours;
                        }
                    }
                }
            }
        }

        int reach = widen ? 1 : 0;
        for (int y = region.y; y < region.y + region.height; y++) {
            for (int x = region.x; x < region.x + region.width; x++) {
                boolean marked = false;
                for (int ey = y - reach; ey <= y + reach && !marked; ey++) {
                    for (int ex = x - reach; ex <= x + reach && !marked; ex++) {
                        marked = edgeArea.contains(ex, ey) && edge[(ey - edgeArea.y) * edgeArea.width + (ex - edgeArea.x)];
                    }
                }
                result[(y - region.y) * region.width + (x - region.x)] = marked;
            }
        }
        return result;
    }

    /**
     * Two neighbouring base cells differ enough to refine: one is in the set and the other is not, the iteration
     * count jumps by more than a palette segment (the palette may cycle so fast that the colours match by
     * chance), or the colours differ by more than {@code threshold} in some channel.
     */
    private static boolean differs(IterationGrid base, int x, int y, int nx, int ny, int here, int there, int threshold,
            int shades) {
        boolean escaped = base.isEscaped(x, y);
        if (escaped != base.isEscaped(nx, ny)) {
            return true;
        }
        if (!escaped) {
            return false;
        }
        if (Math.abs(base.smoothIterationCountAt(x, y) - base.smoothIterationCountAt(nx, ny)) > shades) {
            return true;
        }
        return Math.abs(((here >> 16) & 0xFF) - ((there >> 16) & 0xFF)) > threshold
                || Math.abs(((here >> 8) & 0xFF) - ((there >> 8) & 0xFF)) > threshold
                || Math.abs((here & 0xFF) - (there & 0xFF)) > threshold;
    }

    /**
     * Samples the pixels of {@code region} that {@code mask} marks, at {@code factor} x {@code factor}. Pixels
     * {@code existing} already holds at the same factor are copied rather than computed again (a top-up after a
     * palette change), and stay refined even when the mask no longer marks them. Returns null when cancelled.
     */
    public static Refinement refine(Viewport viewport, int originX, int originY, IterationGrid base, Rectangle region,
            boolean[] mask, int factor, RenderSettings settings, Refinement existing, BooleanSupplier isCancelled) {
        Refinement kept = existing != null && existing.factor() == factor ? existing : null;
        int[] picked = new int[mask.length];
        int count = 0;
        for (int i = 0; i < mask.length; i++) {
            if (mask[i] || (kept != null && kept.find(i) >= 0)) {
                picked[count++] = i;
            }
        }
        int[] pixels = Arrays.copyOf(picked, count);
        int cells = factor * factor;
        IterationGrid samples = new IterationGrid(cells, count);
        int centre = factor % 2 == 1 ? factor / 2 : -1; // with odd factors the middle sample is the base cell itself

        for (int k = 0; k < count; k++) {
            if ((k & 63) == 0 && isCancelled.getAsBoolean()) {
                return null;
            }
            int index = pixels[k];
            int known = kept != null ? kept.find(index) : -1;
            if (known >= 0) {
                for (int s = 0; s < cells; s++) {
                    samples.copyCell(kept.samples(), s, known, s, k);
                }
                continue;
            }
            int bx = region.x + index % region.width;
            int by = region.y + index / region.width;
            for (int sy = 0; sy < factor; sy++) {
                for (int sx = 0; sx < factor; sx++) {
                    int s = sy * factor + sx;
                    if (sx == centre && sy == centre) {
                        samples.copyCell(base, bx, by, s, k);
                    } else {
                        samples.set(s, k, settings.algorithm().compute(
                                viewport.toComplex(originX + bx, originY + by, offset(sx, factor), offset(sy, factor)),
                                settings));
                    }
                }
            }
        }
        return new Refinement(factor, pixels, samples);
    }

    /**
     * Colours {@code region}: refined pixels average their samples (in the same gamma-approximated way as
     * Palette's own blend: the mean of the squares, then the square root), the rest take their base cell's colour.
     */
    public static BufferedImage colorize(IterationGrid base, Rectangle region, Refinement refinement, Palette palette) {
        BufferedImage image = new BufferedImage(region.width, region.height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < region.height; y++) {
            for (int x = 0; x < region.width; x++) {
                image.setRGB(x, y, colour(base, palette, region.x + x, region.y + y));
            }
        }
        if (refinement == null) {
            return image;
        }
        IterationGrid samples = refinement.samples();
        int cells = samples.width();
        for (int k = 0; k < refinement.count(); k++) {
            double sumR2 = 0;
            double sumG2 = 0;
            double sumB2 = 0;
            for (int s = 0; s < cells; s++) {
                int rgb = colour(samples, palette, s, k);
                double r = ((rgb >> 16) & 0xFF) / 255.0;
                double g = ((rgb >> 8) & 0xFF) / 255.0;
                double b = (rgb & 0xFF) / 255.0;
                sumR2 += r * r;
                sumG2 += g * g;
                sumB2 += b * b;
            }
            int index = refinement.pixels()[k];
            Color average = new Color((float) Math.sqrt(sumR2 / cells), (float) Math.sqrt(sumG2 / cells),
                    (float) Math.sqrt(sumB2 / cells));
            image.setRGB(index % region.width, index / region.width, average.getRGB());
        }
        return image;
    }

    private static int colour(IterationGrid grid, Palette palette, int x, int y) {
        return grid.isEscaped(x, y)
                ? palette.colorFor(grid.smoothIterationCountAt(x, y), grid.nuAt(x, y)).getRGB()
                : Color.BLACK.getRGB();
    }

    private static Rectangle grown(Rectangle r, int by) {
        return new Rectangle(r.x - by, r.y - by, r.width + 2 * by, r.height + 2 * by);
    }
}

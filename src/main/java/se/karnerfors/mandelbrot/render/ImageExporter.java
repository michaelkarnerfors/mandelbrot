package se.karnerfors.mandelbrot.render;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;

import se.karnerfors.mandelbrot.math.Complex;
import se.karnerfors.mandelbrot.math.Viewport;
import se.karnerfors.mandelbrot.palette.Palette;

/**
 * Renders a {@link Viewport} straight to a full-resolution {@link BufferedImage},
 * for exporting a still image independent of (and usually much larger than) the
 * live on-screen fractal panel.
 *
 * <p>Unlike {@link Renderer#computeGridParallel}, which allocates one {@link
 * IterationGrid} for the entire viewport -- fine at window sizes, but not for a
 * 16K picture -- this class allocates only small grids per tile (one sample per
 * pixel and a margin, plus the extra samples of the pixels it super-samples),
 * colours them straight into its slice of the final image, and discards them.
 * Memory stays bounded by the final image size plus a handful of in-flight tile
 * buffers, however large the picture or the super-sampling.
 */
public final class ImageExporter {

    /** Tile edge length in pixels -- independent of the live panel's own tile size. */
    public static final int EXPORT_TILE_SIZE = 128;

    private ImageExporter() {
    }

    /** What a render produced: the picture, and how many of its pixels were super-sampled. */
    public record Result(BufferedImage image, long refinedPixels) {

        /** The share of pixels that were super-sampled, 0 to 1. */
        public double refinedShare() {
            long pixels = (long) image.getWidth() * image.getHeight();
            return pixels == 0 ? 0 : refinedPixels / (double) pixels;
        }
    }

    /** Number of tiles a render of this viewport will be split into -- for progress-bar math before starting. */
    public static int tileCount(Viewport target) {
        return Renderer.computeTiles(target.width(), target.height(), EXPORT_TILE_SIZE).size();
    }

    /**
     * Renders {@code target} at its own resolution (not the live panel's),
     * tile by tile, on {@code executor}, super-sampling adaptively (see
     * {@link AdaptiveSampler}). {@code isCancelled} is polled the
     * same way {@link Renderer#computeGridParallel} does, so an in-flight
     * export can be abandoned early. {@code onTileComplete}, when non-null,
     * fires once per tile (completed, skipped, or failed alike) on whichever
     * pool thread just finished it -- never the caller's thread -- purely so
     * a caller can track progress; it carries no data.
     *
     * <p>Blocks the calling thread until every tile has either completed or
     * been skipped as cancelled, so call this from a background thread.
     */
    public static Result renderImage(Viewport target, RenderSettings settings, Palette palette,
            ExecutorService executor, BooleanSupplier isCancelled, Runnable onTileComplete) throws InterruptedException {

        int factor = Math.max(1, settings.supersampleFactor());
        int threshold = AdaptiveSampler.threshold();
        List<Rectangle> tiles = Renderer.computeTiles(target.width(), target.height(), EXPORT_TILE_SIZE);
        Rectangle bounds = new Rectangle(0, 0, target.width(), target.height());
        BufferedImage finalImage = new BufferedImage(target.width(), target.height(), BufferedImage.TYPE_INT_ARGB);
        LongAdder refinedPixels = new LongAdder();
        CountDownLatch latch = new CountDownLatch(tiles.size());

        for (Rectangle tile : tiles) {
            executor.execute(() -> {
                try {
                    if (isCancelled.getAsBoolean()) {
                        return;
                    }
                    // One sample per pixel for the tile and a margin around it (the mask looks at neighbours);
                    // a grid sized to just this, never the whole (possibly huge) picture.
                    int margin = factor > 1 ? AdaptiveSampler.MARGIN : 0;
                    Rectangle area = new Rectangle(tile.x - margin, tile.y - margin, tile.width + 2 * margin,
                            tile.height + 2 * margin).intersection(bounds);
                    IterationGrid base = new IterationGrid(area.width, area.height);
                    for (int lx = 0; lx < area.width; lx++) {
                        for (int ly = 0; ly < area.height; ly++) {
                            Complex c = target.toComplex(area.x + lx, area.y + ly);
                            base.set(lx, ly, settings.algorithm().compute(c, settings));
                        }
                    }
                    Rectangle region = new Rectangle(tile.x - area.x, tile.y - area.y, tile.width, tile.height);
                    AdaptiveSampler.Refinement refinement = null;
                    if (factor > 1) {
                        boolean[] mask = AdaptiveSampler.mask(base, palette, region, threshold);
                        refinement = AdaptiveSampler.refine(target, area.x, area.y, base, region, mask, factor, settings,
                                null, isCancelled);
                        if (refinement == null) {
                            return; // cancelled
                        }
                        refinedPixels.add(refinement.count());
                    }
                    if (isCancelled.getAsBoolean()) {
                        return;
                    }
                    BufferedImage tileFinal = AdaptiveSampler.colorize(base, region, refinement, palette);
                    synchronized (finalImage) {
                        Graphics2D g = finalImage.createGraphics();
                        g.drawImage(tileFinal, tile.x, tile.y, null);
                        g.dispose();
                    }
                } finally {
                    latch.countDown();
                    if (onTileComplete != null) {
                        onTileComplete.run();
                    }
                }
            });
        }

        latch.await();
        return new Result(finalImage, refinedPixels.sum());
    }
}

package se.karnerfors.mandelbrot.render;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
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
 * IterationGrid} sized to the *entire* (possibly supersampled) viewport --
 * perfectly fine at ordinary window sizes, but a problem once "ordinary window
 * size" becomes an 8K canvas at 4x4 supersampling (billions of cells, tens of
 * gigabytes) -- this class allocates only one small grid per tile, computes and
 * colorizes it, downsamples it straight into its slice of the final image, and
 * discards it. Memory stays bounded by the final image size plus a handful of
 * in-flight tile buffers, however large the requested export or supersample
 * factor.
 */
public final class ImageExporter {

    /** Tile edge length, in *final* (post-downsample) pixels -- independent of the live panel's own tile size. */
    public static final int EXPORT_TILE_SIZE = 128;

    private ImageExporter() {
    }

    /** Number of tiles a render of this viewport/factor will be split into -- for progress-bar math before starting. */
    public static int tileCount(Viewport target, int supersampleFactor) {
        int factor = Math.max(1, supersampleFactor);
        Viewport oversampled = target.supersampled(factor);
        return Renderer.computeTiles(oversampled.width(), oversampled.height(), EXPORT_TILE_SIZE * factor).size();
    }

    /**
     * Renders {@code target} at its own resolution (not the live panel's),
     * tile by tile, on {@code executor}. {@code isCancelled} is polled the
     * same way {@link Renderer#computeGridParallel} does, so an in-flight
     * export can be abandoned early. {@code onTileComplete}, when non-null,
     * fires once per tile (completed, skipped, or failed alike) on whichever
     * pool thread just finished it -- never the caller's thread -- purely so
     * a caller can track progress; it carries no data.
     *
     * <p>Blocks the calling thread until every tile has either completed or
     * been skipped as cancelled, so call this from a background thread.
     */
    public static BufferedImage renderImage(Viewport target, RenderSettings settings, Palette palette,
            ExecutorService executor, BooleanSupplier isCancelled, Runnable onTileComplete) throws InterruptedException {

        int factor = Math.max(1, settings.supersampleFactor());
        int oversampledTileSize = EXPORT_TILE_SIZE * factor;
        Viewport oversampled = target.supersampled(factor);
        List<Rectangle> tiles = Renderer.computeTiles(oversampled.width(), oversampled.height(), oversampledTileSize);
        BufferedImage finalImage = new BufferedImage(target.width(), target.height(), BufferedImage.TYPE_INT_ARGB);
        CountDownLatch latch = new CountDownLatch(tiles.size());

        for (Rectangle tile : tiles) {
            executor.execute(() -> {
                try {
                    if (isCancelled.getAsBoolean()) {
                        return;
                    }
                    // A grid sized to just this tile -- never the whole (possibly huge) oversampled image.
                    IterationGrid tileGrid = new IterationGrid(tile.width, tile.height);
                    for (int lx = 0; lx < tile.width; lx++) {
                        for (int ly = 0; ly < tile.height; ly++) {
                            Complex c = oversampled.toComplex(tile.x + lx, tile.y + ly);
                            tileGrid.set(lx, ly, settings.algorithm().compute(c, settings));
                        }
                    }
                    if (isCancelled.getAsBoolean()) {
                        return;
                    }
                    BufferedImage tileOversampled = Colorizer.colorize(tileGrid, palette);
                    BufferedImage tileFinal = Colorizer.downsample(tileOversampled, factor);
                    int finalX = tile.x / factor;
                    int finalY = tile.y / factor;
                    synchronized (finalImage) {
                        Graphics2D g = finalImage.createGraphics();
                        g.drawImage(tileFinal, finalX, finalY, null);
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
        return finalImage;
    }
}

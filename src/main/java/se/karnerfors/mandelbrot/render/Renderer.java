package se.karnerfors.mandelbrot.render;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

import se.karnerfors.mandelbrot.math.Complex;
import se.karnerfors.mandelbrot.math.Viewport;

public final class Renderer {

    /** Tile edge length (in the grid's own pixels) used by computeGridParallel's callers by default. */
    public static final int DEFAULT_TILE_SIZE = 64;

    private Renderer() {
    }

    /**
     * Single-threaded reference implementation. Kept as-is (bit-exact,
     * regression-tested against the original algorithm in Task 2) as the
     * ground truth that {@link #computeGridParallel} is checked against --
     * the app itself now renders through the parallel path by default.
     */
    public static IterationGrid computeGrid(Viewport viewport, RenderSettings settings) {
        int width = viewport.width();
        int height = viewport.height();
        IterationGrid grid = new IterationGrid(width, height);

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                Complex c = viewport.toComplex(x, y);
                grid.set(x, y, EscapeTimeAlgorithm.compute(c, settings.maxIterations(), settings.escapeRadius()));
            }
        }

        return grid;
    }

    /**
     * Splits {@code viewport}'s grid into {@code tileSize}x{@code tileSize}
     * tiles (smaller at the right/bottom edges when it doesn't divide
     * evenly) and computes them on {@code executor}, in parallel. Each tile
     * writes into its own disjoint slice of the returned grid's backing
     * arrays -- safe without synchronization, by the same reasoning that
     * lets {@link IterationGrid} skip locking entirely.
     *
     * <p>{@code isCancelled} is polled twice per tile -- before starting its
     * computation and again right before notifying the callback -- so a tile
     * still sitting in the executor's queue when a newer render supersedes
     * this one is skipped almost for free, and a tile that finished just as
     * that happened doesn't bother colorizing/painting a result nobody
     * wants. Pass {@code () -> false} for a one-shot render that can never
     * be cancelled (e.g. the CLI).
     *
     * <p>{@code onTileComplete}, when non-null, is invoked (with the grid
     * and that tile's bounds) on whichever pool thread just finished it --
     * not necessarily in tile order, and never on the caller's thread. A
     * caller touching Swing state from it must hop back to the EDT itself.
     *
     * <p>Blocks the calling thread until every tile has either completed or
     * been skipped as cancelled, so call this from a background thread.
     */
    public static IterationGrid computeGridParallel(Viewport viewport, RenderSettings settings,
            ExecutorService executor, int tileSize, BooleanSupplier isCancelled,
            BiConsumer<IterationGrid, Rectangle> onTileComplete) throws InterruptedException {

        int width = viewport.width();
        int height = viewport.height();
        IterationGrid grid = new IterationGrid(width, height);
        List<Rectangle> tiles = computeTiles(width, height, tileSize);
        CountDownLatch latch = new CountDownLatch(tiles.size());

        for (Rectangle tile : tiles) {
            executor.execute(() -> {
                try {
                    if (isCancelled.getAsBoolean()) {
                        return;
                    }
                    for (int x = tile.x; x < tile.x + tile.width; x++) {
                        for (int y = tile.y; y < tile.y + tile.height; y++) {
                            Complex c = viewport.toComplex(x, y);
                            grid.set(x, y, EscapeTimeAlgorithm.compute(c, settings.maxIterations(), settings.escapeRadius()));
                        }
                    }
                    if (onTileComplete != null && !isCancelled.getAsBoolean()) {
                        onTileComplete.accept(grid, tile);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        return grid;
    }

    private static List<Rectangle> computeTiles(int width, int height, int tileSize) {
        List<Rectangle> tiles = new ArrayList<>();
        for (int y = 0; y < height; y += tileSize) {
            int h = Math.min(tileSize, height - y);
            for (int x = 0; x < width; x += tileSize) {
                int w = Math.min(tileSize, width - x);
                tiles.add(new Rectangle(x, y, w, h));
            }
        }
        return tiles;
    }
}

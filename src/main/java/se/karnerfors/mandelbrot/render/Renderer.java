package se.karnerfors.mandelbrot.render;

import se.karnerfors.mandelbrot.math.Complex;
import se.karnerfors.mandelbrot.math.Viewport;

/**
 * Computes raw iteration data for a Viewport. Single-threaded for now --
 * parallelizing this loop across tiles is Task 4.
 */
public final class Renderer {

    private Renderer() {
    }

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
}

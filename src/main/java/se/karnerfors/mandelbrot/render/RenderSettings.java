package se.karnerfors.mandelbrot.render;

import java.util.List;

import se.karnerfors.mandelbrot.math.Complex;

/**
 * @param supersampleFactor render at supersampleFactor x the pixel density per
 *                          axis, then box-filter down -- 1 disables antialiasing.
 *                          3 means a 3x3 grid: 9 samples per pixel.
 */
public record RenderSettings(int maxIterations, double escapeRadius, int supersampleFactor, RenderAlgorithm algorithm, Complex c) {

    /** The fewest and the most iterations a point is given before it counts as inside the set. */
    public static final int MIN_ITERATIONS = 10;
    public static final int MAX_ITERATIONS = 100_000;
    public static final int DEFAULT_ITERATIONS = 1000;

    /** A value read from a file or typed in, made safe: the nearest number in the allowed range. */
    public static int validMaxIterations(int value) {
        return Math.max(MIN_ITERATIONS, Math.min(MAX_ITERATIONS, value));
    }

    /** The supersample factors on offer, per axis, lightest first. */
    public static final List<Integer> SUPERSAMPLE_FACTORS = List.of(1, 2, 3, 4, 6, 8);

    /** How a factor is shown: the grid of samples behind each pixel, e.g. "3×3". */
    public static String supersampleLabel(int factor) {
        return factor + "×" + factor;
    }

    /**
     * A factor read from a file, made safe: the highest step not above it, or 1. Older versions offered 9
     * (81 samples per pixel), which becomes 8.
     */
    public static int validSupersampleFactor(int factor) {
        int valid = 1;
        for (int step : SUPERSAMPLE_FACTORS) {
            if (step <= factor) {
                valid = step;
            }
        }
        return valid;
    }

    public static RenderSettings defaults() {
        return new RenderSettings(DEFAULT_ITERATIONS, 256.0, 1, RenderAlgorithm.MANDELBROT,
                JuliaPreset.DOUADY_RABBIT.c());
    }

    /** These settings with {@code iterations} (made safe by {@link #validMaxIterations}) as the iteration limit. */
    public RenderSettings withMaxIterations(int iterations) {
        return new RenderSettings(validMaxIterations(iterations), escapeRadius, supersampleFactor, algorithm, c);
    }

    public RenderSettings withSupersampleFactor(int factor) {
        return new RenderSettings(maxIterations, escapeRadius, factor, algorithm, c);
    }

    public RenderSettings withC(Complex newC) {
        return new RenderSettings(maxIterations, escapeRadius, supersampleFactor, algorithm, newC);
    }

    public RenderSettings withAlgorithm(RenderAlgorithm newAlgorithm) {
        return new RenderSettings(maxIterations, escapeRadius, supersampleFactor, newAlgorithm, c);
    }
}

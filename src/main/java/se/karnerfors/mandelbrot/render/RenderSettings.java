package se.karnerfors.mandelbrot.render;

import java.util.List;

import se.karnerfors.mandelbrot.math.Complex;

/**
 * @param supersampleFactor render at supersampleFactor x the pixel density per
 *                          axis, then box-filter down -- 1 disables antialiasing.
 *                          3 means a 3x3 grid: 9 samples per pixel.
 */
public record RenderSettings(int maxIterations, double escapeRadius, int supersampleFactor, RenderAlgorithm algorithm, Complex c) {

    /** The supersample factors on offer, per axis, lightest first. */
    public static final List<Integer> SUPERSAMPLE_FACTORS = List.of(1, 2, 3, 4);

    /** How a factor is shown: the grid of samples behind each pixel, e.g. "3×3". */
    public static String supersampleLabel(int factor) {
        return factor + "×" + factor;
    }

    /**
     * A factor read from a file, made safe. Older versions offered 9 (81 samples per pixel): anything above the
     * highest step becomes the highest, anything else unknown becomes 1.
     */
    public static int validSupersampleFactor(int factor) {
        int highest = SUPERSAMPLE_FACTORS.getLast();
        if (factor > highest) {
            return highest;
        }
        return SUPERSAMPLE_FACTORS.contains(factor) ? factor : 1;
    }

    public static RenderSettings defaults() {
        return new RenderSettings(1000, 256.0, 1, RenderAlgorithm.MANDELBROT, JuliaPreset.DOUADY_RABBIT.c());
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

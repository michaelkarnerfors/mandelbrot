package se.karnerfors.mandelbrot.render;

import se.karnerfors.mandelbrot.math.Complex;

/**
 * @param supersampleFactor render at supersampleFactor x the pixel density per
 *                          axis, then box-filter down -- 1 disables antialiasing.
 */
public record RenderSettings(int maxIterations, double escapeRadius, int supersampleFactor, RenderAlgorithm algorithm, Complex c) {

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

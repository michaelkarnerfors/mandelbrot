package se.karnerfors.mandelbrot.render;

/**
 * @param supersampleFactor render at supersampleFactor x the pixel density per
 *                          axis, then box-filter down -- 1 disables antialiasing.
 */
public record RenderSettings(int maxIterations, double escapeRadius, int supersampleFactor) {

    public static RenderSettings defaults() {
        return new RenderSettings(1000, 256.0, 4);
    }
}

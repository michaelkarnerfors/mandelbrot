package se.karnerfors.mandelbrot.render;

public record RenderSettings(int maxIterations, double escapeRadius) {

    public static RenderSettings defaults() {
        return new RenderSettings(1000, 256.0);
    }
}

package se.karnerfors.mandelbrot.render;

import se.karnerfors.mandelbrot.math.Complex;

public enum RenderAlgorithm {

    MANDELBROT("Mandelbrot") {
        @Override
        public EscapeResult compute(Complex point, RenderSettings settings) {
            return MandelbrotEscapeTimeAlgorithm.compute(point, settings.maxIterations(), settings.escapeRadius());
        }
    },
    JULIA("Julia") {
        @Override
        public EscapeResult compute(Complex point, RenderSettings settings) {
            return JuliaEscapeTimeAlgorithm.compute(point, settings.c(), settings.maxIterations(), settings.escapeRadius());
        }
    };

    private final String displayName;

    RenderAlgorithm(String displayName) {
        this.displayName = displayName;
    }

    /** Computes the escape-time result for one pixel's complex coordinate using this fractal's algorithm. */
    public abstract EscapeResult compute(Complex point, RenderSettings settings);

    @Override
    public String toString() {
        return displayName;
    }
}

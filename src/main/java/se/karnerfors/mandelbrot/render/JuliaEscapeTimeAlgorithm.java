package se.karnerfors.mandelbrot.render;

import se.karnerfors.mandelbrot.math.Complex;

/**
 * Escape-time iteration for the Julia set of z -> z^2 + c: the pixel is the
 * starting value z0, and c is the same for every pixel.
 */
public final class JuliaEscapeTimeAlgorithm {

    private static final double LOG_2 = Math.log(2.0D);

    private JuliaEscapeTimeAlgorithm() {
    }

    public static EscapeResult compute(Complex z0, Complex c, int maxIterations, double escapeRadius) {
        int iterations = 0;
        double escapeSquared = escapeRadius * escapeRadius;
        Complex pixel = Complex.fromCartesian(z0.real, z0.imaginary);
        while (pixel.getHypotSquare() < escapeSquared && iterations++ < maxIterations) {
            pixel.square().add(c);
        }

        if (iterations < maxIterations) {
            double nu = Math.log(Math.log(pixel.getValue()) / LOG_2) / LOG_2;
            iterations++;
            double smoothIterationCount = iterations - nu;
            return EscapeResult.escaped(iterations, smoothIterationCount, nu);
        }

        return EscapeResult.inSet(iterations);
    }
}

package se.karnerfors.mandelbrot.render;

import se.karnerfors.mandelbrot.math.Complex;

/**
 * The escape-time iteration itself, lifted unchanged (bit-for-bit) out of the
 * original MandelbrotImage.render() inner loop.
 */
public final class MandelbrotEscapeTimeAlgorithm {

    private static final double LOG_2 = Math.log(2.0D);

    private MandelbrotEscapeTimeAlgorithm() {
    }

    public static EscapeResult compute(Complex c, int maxIterations, double escapeRadius) {
        int iterations = 0;
        double escapeSquared = escapeRadius * escapeRadius;
        Complex pixel = Complex.fromCartesian(0.0D, 0.0D);
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

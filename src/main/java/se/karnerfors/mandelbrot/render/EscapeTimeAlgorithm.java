package se.karnerfors.mandelbrot.render;

import se.karnerfors.mandelbrot.math.Complex;

/**
 * The escape-time iteration itself, lifted unchanged (bit-for-bit) out of the
 * original MandelbrotImage.render() inner loop.
 */
public final class EscapeTimeAlgorithm {

    private static final double LOG_2 = Math.log(2.0D);

    private EscapeTimeAlgorithm() {
    }

    public static EscapeResult compute(Complex c, int maxIterations, double escapeRadius) {
        int iterations = 0;
        // Seeded from c so the iteration runs at c's precision: a double zero would narrow a BigComplex c on the first add.
        Complex pixel = c.zeroLike();
        while (pixel.getValue() < escapeRadius && iterations++ < maxIterations) {
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

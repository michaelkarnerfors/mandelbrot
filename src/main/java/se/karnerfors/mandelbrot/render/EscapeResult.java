package se.karnerfors.mandelbrot.render;

/**
 * The raw result of running the escape-time algorithm on one point.
 *
 * @param escaped               whether the point left the escape radius before maxIterations
 * @param iterations            iteration count at exit (see EscapeTimeAlgorithm for exact semantics)
 * @param smoothIterationCount  iterations - nu; the continuous value used to index the palette
 * @param nu                    fractional correction term; 0 when not escaped (unused in that case)
 */
public record EscapeResult(boolean escaped, int iterations, double smoothIterationCount, double nu) {

    public static EscapeResult inSet(int iterations) {
        return new EscapeResult(false, iterations, iterations, 0.0);
    }

    public static EscapeResult escaped(int iterations, double smoothIterationCount, double nu) {
        return new EscapeResult(true, iterations, smoothIterationCount, nu);
    }
}

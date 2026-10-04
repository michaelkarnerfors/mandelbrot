package se.karnerfors.mandelbrot.math;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * A complex number. Mutable by design: {@link #add} and {@link #square}
 * update the receiver in place and return {@code this}, matching the
 * escape-time hot loop's {@code pixel.square().add(c)} idiom in
 * {@link se.karnerfors.mandelbrot.render.EscapeTimeAlgorithm} -- switching
 * to immutable, return-a-new-instance semantics would silently break that
 * call site (the same {@code pixel} would simply never advance).
 *
 * <p>{@link DoubleComplex} is the native-{@code double} implementation used
 * everywhere today -- every existing call path ({@link Viewport} and
 * everything built on it) only ever produces this one. {@link BigComplex}
 * is a {@link BigDecimal}-backed implementation for zoom levels beyond
 * double precision's ~15-17 significant digits; it exists as the seam a
 * future zoom-threshold crossing would widen into, but nothing currently
 * constructs one outside of tests -- {@code Viewport} does not yet promote
 * to it at any zoom level.
 */
public sealed interface Complex permits DoubleComplex, BigComplex {

    static Complex fromCartesian(double real, double imaginary) {
        return DoubleComplex.fromCartesian(real, imaginary);
    }

    static Complex fromPolar(double value, double angle) {
        return DoubleComplex.fromPolar(value, angle);
    }

    /** The explicit high-precision entry point -- not yet wired into any render path. */
    static Complex fromBigDecimal(BigDecimal real, BigDecimal imaginary, MathContext context) {
        return BigComplex.fromBigDecimal(real, imaginary, context);
    }

    /** This number's real part, widened to {@code double} if the implementation holds more precision. */
    double real();

    /** This number's imaginary part, widened to {@code double} if the implementation holds more precision. */
    double imaginary();

    /** Adds {@code other} into this complex number in place and returns {@code this}, for chaining. */
    Complex add(Complex other);

    /** Squares this complex number in place and returns {@code this}, for chaining. */
    Complex square();

    double getValue();

    double getAngle();
}

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
 * <p>{@link DoubleComplex} is the native-{@code double} implementation, used
 * whenever the zoom is shallow enough for double precision to tell adjacent
 * pixels apart. {@link BigComplex} is the {@link BigDecimal}-backed one for
 * deeper zooms; {@link Viewport} decides which to hand out (see
 * {@link Viewport#BIG_DECIMAL_ZOOM_THRESHOLD}), and the escape-time loop
 * follows whatever it is given via {@link #zeroLike()}.
 */
public sealed interface Complex permits DoubleComplex, BigComplex {

    static Complex fromCartesian(double real, double imaginary) {
        return DoubleComplex.fromCartesian(real, imaginary);
    }

    static Complex fromPolar(double value, double angle) {
        return DoubleComplex.fromPolar(value, angle);
    }

    /** The explicit high-precision entry point. */
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

    /** An independent copy with the same value, representation and precision. */
    Complex copy();

    /**
     * A new zero with this number's representation and precision -- what an
     * iteration seeded from this value has to start from, so that it keeps
     * accumulating at this number's precision instead of narrowing to double.
     */
    Complex zeroLike();

    double getValue();

    double getAngle();
}

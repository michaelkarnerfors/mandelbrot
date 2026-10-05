package se.karnerfors.mandelbrot.math;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * {@link BigDecimal}-backed implementation of {@link Complex}, for zoom
 * levels beyond double precision. {@link Viewport} hands these out once its
 * zoom reaches {@link Viewport#BIG_DECIMAL_ZOOM_THRESHOLD}.
 *
 * <p>Mutable, like {@link DoubleComplex}: {@link #add} and {@link #square}
 * update the fields in place and return {@code this}. Every operation is
 * rounded to {@link #context}, a fixed precision supplied at construction,
 * so repeated squaring during escape-time iteration can't let the digit
 * count grow unbounded -- a classic BigDecimal gotcha.
 *
 * <p>{@link #add} widens a {@link DoubleComplex} operand up to
 * {@code BigDecimal} rather than narrowing this number down to double, so
 * mixing representations (which no current code path actually does) would
 * never silently discard precision.
 */
public final class BigComplex implements Complex {

    private BigDecimal real;
    private BigDecimal imaginary;
    private final MathContext context;

    private BigComplex(BigDecimal real, BigDecimal imaginary, MathContext context) {
        this.context = context;
        this.real = real.round(context);
        this.imaginary = imaginary.round(context);
    }

    static BigComplex fromBigDecimal(BigDecimal real, BigDecimal imaginary, MathContext context) {
        return new BigComplex(real, imaginary, context);
    }

    /** This complex number's precision, as supplied at construction. */
    public MathContext context() {
        return context;
    }

    /** The full-precision real part, with none of the {@code double}-widening {@link #real()} does. */
    public BigDecimal realValue() {
        return real;
    }

    /** The full-precision imaginary part, with none of the {@code double}-widening {@link #imaginary()} does. */
    public BigDecimal imaginaryValue() {
        return imaginary;
    }

    @Override
    public double real() {
        return real.doubleValue();
    }

    @Override
    public double imaginary() {
        return imaginary.doubleValue();
    }

    @Override
    public Complex add(Complex other) {
        BigDecimal otherReal;
        BigDecimal otherImaginary;
        if (other instanceof BigComplex big) {
            otherReal = big.real;
            otherImaginary = big.imaginary;
        } else {
            otherReal = BigDecimal.valueOf(other.real());
            otherImaginary = BigDecimal.valueOf(other.imaginary());
        }
        this.real = real.add(otherReal, context);
        this.imaginary = imaginary.add(otherImaginary, context);
        return this;
    }

    @Override
    public Complex square() {
        BigDecimal newReal = real.multiply(real, context).subtract(imaginary.multiply(imaginary, context), context);
        BigDecimal newImaginary = real.multiply(imaginary, context).multiply(BigDecimal.TWO, context);
        real = newReal;
        imaginary = newImaginary;
        return this;
    }

    @Override
    public Complex copy() {
        return new BigComplex(real, imaginary, context);
    }

    @Override
    public Complex zeroLike() {
        return new BigComplex(BigDecimal.ZERO, BigDecimal.ZERO, context);
    }

    @Override
    public double getValue() {
        return Math.hypot(real.doubleValue(), imaginary.doubleValue());
    }

    @Override
    public double getAngle() {
        return Math.atan2(real.doubleValue(), imaginary.doubleValue());
    }
}

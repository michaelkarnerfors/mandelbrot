package se.karnerfors.mandelbrot.math;

/**
 * Native-{@code double} implementation of {@link Complex}, used whenever the
 * zoom is shallow enough for double precision. Mutable: {@link #add}
 * and {@link #square} update the fields in place and return {@code this},
 * reproducing the original (pre-interface) {@code Complex} class's
 * behavior exactly, field for field and operation for operation.
 */
public final class DoubleComplex implements Complex {

    private double real;
    private double imaginary;

    private DoubleComplex(double real, double imaginary) {
        this.real = real;
        this.imaginary = imaginary;
    }

    static DoubleComplex fromPolar(double value, double angle) {
        return new DoubleComplex(value * Math.cos(angle), value * Math.sin(angle));
    }

    static DoubleComplex fromCartesian(double real, double imaginary) {
        return new DoubleComplex(real, imaginary);
    }

    @Override
    public double real() {
        return real;
    }

    @Override
    public double imaginary() {
        return imaginary;
    }

    @Override
    public Complex add(Complex other) {
        this.real += other.real();
        this.imaginary += other.imaginary();
        return this;
    }

    @Override
    public Complex square() {
        double newReal = real * real - imaginary * imaginary;
        imaginary = 2 * real * imaginary;
        real = newReal;
        return this;
    }

    @Override
    public Complex copy() {
        return new DoubleComplex(real, imaginary);
    }

    @Override
    public Complex zeroLike() {
        return new DoubleComplex(0.0D, 0.0D);
    }

    @Override
    public double getValue() {
        return Math.hypot(this.real, this.imaginary);
    }

    @Override
    public double getAngle() {
        return Math.atan2(this.real, this.imaginary);
    }
}

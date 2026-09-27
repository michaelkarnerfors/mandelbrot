package se.karnerfors.mandelbrot.math;

public class Complex {

    public double real = 0.0D;
    public double imaginary = 0.0D;

    private Complex() {
    };

    public static Complex fromPolar(double value, double angle) {
        Complex result = new Complex();
        result.real = value * Math.cos(angle);
        result.imaginary = value * Math.sin(angle);
        return result;
    }

    public static Complex fromCartesian(double real, double imaginary) {
        Complex result = new Complex();
        result.real = real;
        result.imaginary = imaginary;
        return result;
    }

    public Complex add(Complex other) {
        this.real += other.real;
        this.imaginary += other.imaginary;
        return this;
    }

    public Complex square() {
        double newReal = real * real - imaginary * imaginary;
        imaginary = 2 * real * imaginary;
        real = newReal;
        return this;
    }

    public double getValue() {
        return Math.hypot(this.real, this.imaginary);
    }

    public double getAngle() {
        return Math.atan2(this.real, this.imaginary);

    }

}

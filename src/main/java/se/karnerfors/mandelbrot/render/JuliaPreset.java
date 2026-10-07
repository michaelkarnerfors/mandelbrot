package se.karnerfors.mandelbrot.render;

import se.karnerfors.mandelbrot.math.Complex;

/** Famous values of the Julia constant c. */
public enum JuliaPreset {

    DOUADY_RABBIT("Douady rabbit", -0.123, 0.745),
    SIEGEL_DISK("Siegel disk", -0.391, -0.587),
    SAN_MARCO("San Marco", -0.75, 0.0),
    DENDRITE("Dendrite", 0.0, 1.0),
    SEAHORSE("Seahorse", -0.8, 0.156);

    private final String displayName;
    private final double real;
    private final double imaginary;

    JuliaPreset(String displayName, double real, double imaginary) {
        this.displayName = displayName;
        this.real = real;
        this.imaginary = imaginary;
    }

    /** A fresh instance each time, since Complex is mutable. */
    public Complex c() {
        return Complex.fromCartesian(real, imaginary);
    }

    @Override
    public String toString() {
        return String.format("%s (%s %s %si)", displayName, real, imaginary < 0 ? "-" : "+", Math.abs(imaginary));
    }
}

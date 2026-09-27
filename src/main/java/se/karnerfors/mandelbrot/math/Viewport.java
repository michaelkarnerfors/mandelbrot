package se.karnerfors.mandelbrot.math;

/**
 * Immutable mapping between screen pixel coordinates and the complex plane.
 *
 * {@code zoom} is pixels-per-unit, matching the meaning of the old
 * {@code zoom} parameter in the original render() method.
 */
public final class Viewport {

    private final Complex center;
    private final double zoom;
    private final int width;
    private final int height;

    public Viewport(Complex center, double zoom, int width, int height) {
        // Defensive copy: Complex is mutable (square()/add() mutate in place),
        // so we must not hold a reference the caller could later mutate out from under us.
        this.center = Complex.fromCartesian(center.real, center.imaginary);
        this.zoom = zoom;
        this.width = width;
        this.height = height;
    }

    public Complex center() {
        return Complex.fromCartesian(center.real, center.imaginary);
    }

    public double zoom() {
        return zoom;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /**
     * Converts a screen pixel coordinate to the corresponding point on the complex plane.
     * Uses integer division on width/2 and height/2, exactly like the original algorithm,
     * so behavior is identical for odd dimensions too.
     */
    public Complex toComplex(int screenX, int screenY) {
        double real = center.real + (screenX - (width / 2)) / zoom;
        double imaginary = center.imaginary + (screenY - (height / 2)) / zoom;
        return Complex.fromCartesian(real, imaginary);
    }

    public Viewport withCenter(Complex newCenter) {
        return new Viewport(newCenter, zoom, width, height);
    }

    public Viewport withZoom(double newZoom) {
        return new Viewport(center, newZoom, width, height);
    }

    public Viewport withSize(int newWidth, int newHeight) {
        return new Viewport(center, zoom, newWidth, newHeight);
    }
}

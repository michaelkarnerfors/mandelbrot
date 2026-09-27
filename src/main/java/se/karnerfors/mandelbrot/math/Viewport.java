package se.karnerfors.mandelbrot.math;

/**
 * Immutable mapping between screen pixel coordinates and the complex plane.
 *
 * {@code zoom} is pixels-per-unit, matching the meaning of the old
 * {@code zoom} parameter in the original render() method.
 */
public final class Viewport {

    private static final double MIN_ZOOM = 1.0e-2;
    private static final double MAX_ZOOM = 1.0e13;

    private final Complex center;
    private final double zoom;
    private final int width;
    private final int height;

    public Viewport(Complex center, double zoom, int width, int height) {
        // Defensive copy: Complex is mutable (square()/add() mutate in place),
        // so we must not hold a reference the caller could later mutate out from under us.
        this.center = Complex.fromCartesian(center.real, center.imaginary);
        this.zoom = clampZoom(zoom);
        this.width = width;
        this.height = height;
    }

    /**
     * The classic full-set view: center (-0.5, 0), zoomed so a 3.0 x 2.0
     * complex-plane window (comfortably containing the cardioid and main bulb)
     * fits inside the given pixel dimensions regardless of aspect ratio.
     */
    public static Viewport classicFullView(int width, int height) {
        double realSpan = 3.0;
        double imaginarySpan = 2.0;
        double zoom = Math.min(width / realSpan, height / imaginarySpan);
        return new Viewport(Complex.fromCartesian(-0.5, 0.0), zoom, width, height);
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

    /**
     * Returns a new Viewport zoomed by {@code factor}, keeping the complex-plane
     * point currently under (pivotX, pivotY) fixed at that same screen position.
     */
    public Viewport zoomedAround(int pivotX, int pivotY, double factor) {
        Complex pivot = toComplex(pivotX, pivotY);
        double newZoom = clampZoom(zoom * factor);
        double newCenterReal = pivot.real - (pivotX - (width / 2)) / newZoom;
        double newCenterImaginary = pivot.imaginary - (pivotY - (height / 2)) / newZoom;
        return new Viewport(Complex.fromCartesian(newCenterReal, newCenterImaginary), newZoom, width, height);
    }

    /**
     * Returns a new Viewport translated by a screen-space pixel delta, "grab and
     * drag" style: dragging right (positive dxPixels) shifts the view to look
     * further left, so content visually moves right under the cursor.
     */
    public Viewport panned(double dxPixels, double dyPixels) {
        double newCenterReal = center.real - dxPixels / zoom;
        double newCenterImaginary = center.imaginary - dyPixels / zoom;
        return new Viewport(Complex.fromCartesian(newCenterReal, newCenterImaginary), zoom, width, height);
    }

    private static double clampZoom(double zoom) {
        if (Double.isNaN(zoom) || Double.isInfinite(zoom)) {
            return MIN_ZOOM;
        }
        return Math.max(MIN_ZOOM, Math.min(zoom, MAX_ZOOM));
    }
}

package se.karnerfors.mandelbrot.math;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class Viewport {

    private static final double MIN_ZOOM = 1.0e-2;
    private static final double MAX_ZOOM = 1.0e13;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Complex center;
    private final double zoom;
    private final int width;
    private final int height;

    public Viewport(Complex center, double zoom, int width, int height) {
        this.center = Complex.fromCartesian(center.real, center.imaginary);
        this.zoom = clampZoom(zoom);
        this.width = width;
        this.height = height;
    }

    public static Viewport classicFullView(int width, int height) {
        double realSpan = 3.0;
        double imaginarySpan = 2.0;
        double zoom = Math.min(width / realSpan, height / imaginarySpan);
        return new Viewport(Complex.fromCartesian(-0.5, 0.0), zoom, width, height);
    }

    /** Julia sets sit around the origin whatever c is, and stay within roughly |z| < 1.6. */
    public static Viewport juliaFullView(int width, int height) {
        double span = 3.2;
        double zoom = Math.min(width, height) / span;
        return new Viewport(Complex.fromCartesian(0.0, 0.0), zoom, width, height);
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

    public Complex toComplex(int screenX, int screenY) {
        double real = center.real + (screenX - (width / 2)) / zoom;
        double imaginary = center.imaginary + (screenY - (height / 2)) / zoom;
        return Complex.fromCartesian(real, imaginary);
    }

    /**
     * The point {@code dx}, {@code dy} pixels away from pixel ({@code screenX}, {@code screenY})'s own point:
     * the sub-pixel sample positions for super-sampling. With zero offsets it is exactly {@link #toComplex(int, int)}.
     */
    public Complex toComplex(int screenX, int screenY, double dx, double dy) {
        double real = center.real + (screenX - (width / 2) + dx) / zoom;
        double imaginary = center.imaginary + (screenY - (height / 2) + dy) / zoom;
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

    public Viewport zoomedAround(int pivotX, int pivotY, double factor) {
        Complex pivot = toComplex(pivotX, pivotY);
        double newZoom = clampZoom(zoom * factor);
        double newCenterReal = pivot.real - (pivotX - (width / 2)) / newZoom;
        double newCenterImaginary = pivot.imaginary - (pivotY - (height / 2)) / newZoom;
        return new Viewport(Complex.fromCartesian(newCenterReal, newCenterImaginary), newZoom, width, height);
    }

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

    /**
     * Renders this Viewport as a JSON object string, e.g.
     * {@code {"center":{"real":-0.5,"imaginary":0.0},"zoom":256.0,"width":800,"height":600}}.
     */
    @Override
    public String toString() {
        try {
            return JSON.writeValueAsString(new ViewportView(center, zoom, width, height));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize Viewport to JSON", e);
        }
    }

    /**
     * Jackson-friendly view of a Viewport's exported state.
     */
    private record ViewportView(ComplexView center, double zoom, int width, int height) {
        ViewportView(Complex center, double zoom, int width, int height) {
            this(new ComplexView(center.real, center.imaginary), zoom, width, height);
        }
    }

    private record ComplexView(double real, double imaginary) {
    }
}

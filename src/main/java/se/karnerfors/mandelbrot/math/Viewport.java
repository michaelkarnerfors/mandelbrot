package se.karnerfors.mandelbrot.math;

import java.math.BigDecimal;
import java.math.MathContext;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A window onto the complex plane: a center, a zoom (pixels per unit) and a
 * pixel size.
 *
 * <p>This is also where the arithmetic switches from {@code double} to
 * {@link BigDecimal}. Below {@link #BIG_DECIMAL_ZOOM_THRESHOLD} the pixel
 * pitch is comfortably larger than double precision, so {@link #toComplex}
 * hands out {@link DoubleComplex} values exactly as it always did. At or
 * above it, the center is held as a {@link BigComplex} (promoted exactly from
 * its double value, then kept at {@code log10(zoom) + 25} digits) and
 * {@code toComplex} hands out {@code BigComplex} values, which the escape-time
 * loop then iterates at the same precision. There is deliberately no deepest
 * zoom: it just gets slower.
 *
 * <p>Once a center has been promoted it stays a {@code BigComplex} even if the
 * zoom later drops back under the threshold (only the per-pixel values go back
 * to double), so zooming out and back in around the same point returns to
 * exactly where it started.
 */
public final class Viewport {

    /** Zoom (pixels per complex unit) at which per-pixel coordinates switch from double to BigDecimal. */
    public static final double BIG_DECIMAL_ZOOM_THRESHOLD = 1.0e13;

    /** Digits kept beyond what the pixel pitch itself needs, to absorb rounding while iterating. */
    private static final int GUARD_DIGITS = 25;

    private static final double MIN_ZOOM = 1.0e-2;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Complex center;
    private final double zoom;
    private final int width;
    private final int height;

    public Viewport(Complex center, double zoom, int width, int height) {
        this.zoom = clampZoom(zoom);
        this.center = centerFor(center, this.zoom);
        this.width = width;
        this.height = height;
    }

    /**
     * Builds a Viewport from decimal coordinates, keeping every digit of them
     * when the zoom is deep enough for those digits to matter.
     */
    public static Viewport fromDecimal(BigDecimal real, BigDecimal imaginary, double zoom, int width, int height) {
        double clamped = clampZoom(zoom);
        Complex center = clamped >= BIG_DECIMAL_ZOOM_THRESHOLD
                ? Complex.fromBigDecimal(real, imaginary, contextFor(clamped))
                : Complex.fromCartesian(real.doubleValue(), imaginary.doubleValue());
        return new Viewport(center, clamped, width, height);
    }

    public static Viewport classicFullView(int width, int height) {
        double realSpan = 3.0;
        double imaginarySpan = 2.0;
        double zoom = Math.min(width / realSpan, height / imaginarySpan);
        return new Viewport(Complex.fromCartesian(-0.5, 0.0), zoom, width, height);
    }

    public Complex center() {
        return center.copy();
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
     * The point under pixel ({@code screenX}, {@code screenY}). A
     * {@link DoubleComplex} below {@link #BIG_DECIMAL_ZOOM_THRESHOLD}, a
     * {@link BigComplex} at or above it.
     */
    public Complex toComplex(int screenX, int screenY) {
        double offsetReal = (screenX - (width / 2)) / zoom;
        double offsetImaginary = (screenY - (height / 2)) / zoom;
        if (zoom < BIG_DECIMAL_ZOOM_THRESHOLD) {
            return Complex.fromCartesian(center.real() + offsetReal, center.imaginary() + offsetImaginary);
        }
        return pointAtOffset(offsetReal, offsetImaginary);
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
        Complex pivot = pointAtOffset((pivotX - (width / 2)) / zoom, (pivotY - (height / 2)) / zoom);
        double newZoom = clampZoom(zoom * factor);
        pivot.add(Complex.fromCartesian(-(pivotX - (width / 2)) / newZoom, -(pivotY - (height / 2)) / newZoom));
        return new Viewport(pivot, newZoom, width, height);
    }

    public Viewport panned(double dxPixels, double dyPixels) {
        Complex moved = center.copy().add(Complex.fromCartesian(-dxPixels / zoom, -dyPixels / zoom));
        return new Viewport(moved, zoom, width, height);
    }

    /**
     * How far this viewport's center is from {@code other}'s, as
     * {@code {real, imaginary}} in complex-plane units. The subtraction is done
     * at full precision and only the (small) difference is narrowed to double,
     * which is what lets a live preview be positioned correctly between two
     * deep viewports whose centers are identical as doubles.
     */
    public double[] centerOffsetFrom(Viewport other) {
        boolean exact = (center instanceof BigComplex || other.center instanceof BigComplex)
                && isFinite(center) && isFinite(other.center);
        if (exact) {
            return new double[] {
                    exactReal(center).subtract(exactReal(other.center)).doubleValue(),
                    exactImaginary(center).subtract(exactImaginary(other.center)).doubleValue() };
        }
        return new double[] { center.real() - other.center.real(), center.imaginary() - other.center.imaginary() };
    }

    /**
     * Returns a Viewport covering the exact same complex-plane window, but at
     * {@code factor}x the pixel density per axis -- used to render oversized
     * for supersampling, then box-filter back down with Colorizer.downsample.
     */
    public Viewport supersampled(int factor) {
        if (factor <= 1) {
            return this;
        }
        return new Viewport(center, zoom * factor, width * factor, height * factor);
    }

    /** A copy of the center, in the center's own representation, moved by the given offset. */
    private Complex pointAtOffset(double offsetReal, double offsetImaginary) {
        return center.copy().add(Complex.fromCartesian(offsetReal, offsetImaginary));
    }

    /**
     * Chooses how a center is held for a given zoom: a BigComplex once the zoom
     * reaches the threshold (or if it already was one), with at least as many
     * digits as that zoom needs.
     */
    private static Complex centerFor(Complex center, double zoom) {
        MathContext needed = contextFor(zoom);
        if (center instanceof BigComplex big) {
            if (big.context().getPrecision() >= needed.getPrecision()) {
                return big.copy();
            }
            return Complex.fromBigDecimal(big.realValue(), big.imaginaryValue(), needed);
        }
        if (zoom >= BIG_DECIMAL_ZOOM_THRESHOLD && isFinite(center)) {
            return Complex.fromBigDecimal(new BigDecimal(center.real()), new BigDecimal(center.imaginary()), needed);
        }
        return center.copy();
    }

    /** Enough digits to resolve the pixel pitch at this zoom, plus a guard margin. */
    private static MathContext contextFor(double zoom) {
        int pitchDigits = (int) Math.ceil(Math.log10(Math.max(zoom, 1.0)));
        return new MathContext(pitchDigits + GUARD_DIGITS);
    }

    private static boolean isFinite(Complex c) {
        return Double.isFinite(c.real()) && Double.isFinite(c.imaginary());
    }

    private static BigDecimal exactReal(Complex c) {
        return c instanceof BigComplex big ? big.realValue() : new BigDecimal(c.real());
    }

    private static BigDecimal exactImaginary(Complex c) {
        return c instanceof BigComplex big ? big.imaginaryValue() : new BigDecimal(c.imaginary());
    }

    private static double clampZoom(double zoom) {
        if (Double.isNaN(zoom)) {
            return MIN_ZOOM;
        }
        return Math.max(MIN_ZOOM, Math.min(zoom, Double.MAX_VALUE));
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
            this(new ComplexView(center.real(), center.imaginary()), zoom, width, height);
        }
    }

    private record ComplexView(double real, double imaginary) {
    }
}

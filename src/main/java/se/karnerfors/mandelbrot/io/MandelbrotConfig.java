package se.karnerfors.mandelbrot.io;

import java.awt.Color;
import java.util.Arrays;
import java.util.List;

import se.karnerfors.mandelbrot.math.Complex;
import se.karnerfors.mandelbrot.math.Viewport;
import se.karnerfors.mandelbrot.palette.Palette;
import se.karnerfors.mandelbrot.render.JuliaPreset;
import se.karnerfors.mandelbrot.render.RenderAlgorithm;
import se.karnerfors.mandelbrot.render.RenderSettings;

/**
 * A serializable snapshot of "where you are and how it's colored" -- center,
 * zoom, palette, and which fractal (with its Julia c) -- independent of window size. Width/height are
 * deliberately not persisted: they're supplied by whoever reconstructs a
 * {@link Viewport} from this config (the live window's current size when
 * loading, or the export resolution when embedding into a PNG), so a saved
 * config always reopens full-size in whatever window loads it rather than
 * forcing a particular resolution.
 *
 * <p>This is the payload saved to / loaded from a {@code .json} config file
 * via {@link MandelbrotConfigIO}, and also the payload embedded as a
 * {@code tEXt} chunk in exported PNGs so a rendered image can later be
 * traced back to the exact view and palette that produced it.
 */
public record MandelbrotConfig(int version, ViewportData viewport, PaletteData palette, FractalData fractal) {

    /** Bump when the shape of this record (or its nested records) changes incompatibly. */
    public static final int CURRENT_VERSION = 2;

    /** Files and PNGs written before version 2 have no fractal section: they were all Mandelbrot. */
    public MandelbrotConfig {
        if (fractal == null) {
            fractal = FractalData.mandelbrot();
        }
    }

    /** Captures the given viewport (center/zoom only), palette and fractal choice as a config at the current version. */
    public static MandelbrotConfig capture(Viewport viewport, Palette palette, RenderSettings settings) {
        return new MandelbrotConfig(CURRENT_VERSION, ViewportData.from(viewport), PaletteData.from(palette),
                FractalData.from(settings));
    }

    /** {@code base} with this config's fractal and Julia c swapped in (iterations, supersampling etc. are kept). */
    public RenderSettings applyTo(RenderSettings base) {
        return base.withAlgorithm(fractal.algorithm()).withC(fractal.c());
    }

    /** Reconstructs a Viewport at this config's center/zoom, sized to {@code width}x{@code height}. */
    public Viewport toViewport(int width, int height) {
        return viewport.toViewport(width, height);
    }

    /** Reconstructs the Palette this config describes. */
    public Palette toPalette() {
        return palette.toPalette();
    }

    /** Which fractal is rendered and, for Julia, its constant c (kept for Mandelbrot too, so switching back and forth is lossless). */
    public record FractalData(RenderAlgorithm algorithm, double cReal, double cImaginary) {
        public FractalData {
            if (algorithm == null) {
                algorithm = RenderAlgorithm.MANDELBROT;
            }
        }

        static FractalData mandelbrot() {
            Complex c = JuliaPreset.DOUADY_RABBIT.c();
            return new FractalData(RenderAlgorithm.MANDELBROT, c.real, c.imaginary);
        }

        static FractalData from(RenderSettings settings) {
            return new FractalData(settings.algorithm(), settings.c().real, settings.c().imaginary);
        }

        Complex c() {
            return Complex.fromCartesian(cReal, cImaginary);
        }
    }

    /** Center + zoom only -- width/height are deliberately not persisted, see class docs. */
    public record ViewportData(double real, double imaginary, double zoom) {
        public static ViewportData from(Viewport viewport) {
            Complex center = viewport.center();
            return new ViewportData(center.real, center.imaginary, viewport.zoom());
        }

        public Viewport toViewport(int width, int height) {
            return new Viewport(Complex.fromCartesian(real, imaginary), zoom, width, height);
        }
    }

    public record PaletteData(List<RgbColor> stops, int shadesPerSegment) {
        public static PaletteData from(Palette palette) {
            return new PaletteData(Arrays.stream(palette.stops()).map(RgbColor::from).toList(), palette.shadesPerSegment());
        }

        public Palette toPalette() {
            Color[] colors = stops.stream().map(RgbColor::toColor).toArray(Color[]::new);
            return new Palette(colors, shadesPerSegment);
        }
    }

    public record RgbColor(int r, int g, int b) {
        public static RgbColor from(Color color) {
            return new RgbColor(color.getRed(), color.getGreen(), color.getBlue());
        }

        public Color toColor() {
            return new Color(r, g, b);
        }
    }
}

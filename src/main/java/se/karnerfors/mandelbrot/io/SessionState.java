package se.karnerfors.mandelbrot.io;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * What the app remembers from one run to the next: where you were looking, how it was coloured,
 * the supersample factor, the window, and the last export choices. Written to
 * {@code ~/mandelbrot/state.json} by {@link SessionStore}.
 *
 * <p>The view part is a plain {@link MandelbrotConfig}, the same shape bookmarks will use later, so
 * "a place in the fractal" has one representation everywhere. Everything here is validated on the
 * way in ({@link #hasUsableView()}), because the file lives in the user's home directory and may be
 * hand-edited, truncated, or written by a different version.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionState(int version, MandelbrotConfig view, int supersample, WindowData window, ExportData export) {

    /** Bump when the shape of this record changes incompatibly. */
    public static final int CURRENT_VERSION = 1;

    private static final int[] SUPERSAMPLE_FACTORS = { 1, 2, 4, 9 };

    public SessionState {
        supersample = validFactor(supersample);
        if (window != null && !window.valid()) {
            window = null;
        }
        if (export != null && !export.valid()) {
            export = null;
        }
    }

    /** True when the saved view can safely be handed to the renderer (see {@link MandelbrotConfig#isUsable()}). */
    public boolean hasUsableView() {
        return view != null && view.isUsable();
    }

    static int validFactor(int factor) {
        for (int allowed : SUPERSAMPLE_FACTORS) {
            if (allowed == factor) {
                return factor;
            }
        }
        return 1;
    }

    /** The main window's normal (not maximised) bounds, and whether it was maximised. */
    public record WindowData(int x, int y, int width, int height, boolean maximized) {
        boolean valid() {
            return width >= 200 && height >= 200 && width <= 20000 && height <= 20000;
        }
    }

    /**
     * The Render… dialog's remembered choices. Position (Re/Im/Zoom) is deliberately not kept: the dialog
     * always starts from the view you are looking at.
     */
    public record ExportData(int width, int height, int supersample, String outputFile) {
        public ExportData {
            supersample = validFactor(supersample);
        }

        boolean valid() {
            return width >= 16 && height >= 16 && width <= 32768 && height <= 32768;
        }
    }
}

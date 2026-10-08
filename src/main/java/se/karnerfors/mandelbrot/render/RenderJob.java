package se.karnerfors.mandelbrot.render;

import java.awt.image.BufferedImage;
import java.io.File;

import se.karnerfors.mandelbrot.io.MandelbrotConfig;

/**
 * One picture waiting to be drawn to a file, or being drawn, or finished. Everything that decides what the
 * picture looks like is copied in when the job is added, so moving around afterwards changes nothing.
 *
 * <p>Fields that change while the job runs are only touched by {@link RenderQueue}; read them on the UI thread.
 */
public final class RenderJob {

    public enum State { WAITING, RENDERING, DONE, FAILED }

    private final int id;
    private final MandelbrotConfig view;
    private final String paletteLabel;
    private final double framingScale;
    private final int width;
    private final int height;
    private final int supersample;
    private final File file;
    private final BufferedImage thumbnail;

    private volatile State state = State.WAITING;
    private volatile double progress;
    private volatile String message = "";
    private volatile long startedNanos;
    private volatile long elapsedMillis;
    private volatile boolean cancelRequested;
    private volatile int retryFactor;

    /**
     * @param view         where, which fractal and which palette. Its zoom is the zoom shown in the status bar
     *                     (window scale); this is what gets embedded in the PNG
     * @param framingScale how many times larger than the window the picture is, so the same area is framed
     * @param thumbnail    a small picture of the view when it was added, or null
     */
    RenderJob(int id, MandelbrotConfig view, String paletteLabel, double framingScale, int width, int height,
            int supersample, File file, BufferedImage thumbnail) {
        this.id = id;
        this.view = view;
        this.paletteLabel = paletteLabel;
        this.framingScale = framingScale;
        this.width = width;
        this.height = height;
        this.supersample = supersample;
        this.file = file;
        this.thumbnail = thumbnail;
    }

    public int id() {
        return id;
    }

    public MandelbrotConfig view() {
        return view;
    }

    public String paletteLabel() {
        return paletteLabel;
    }

    public double framingScale() {
        return framingScale;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int supersample() {
        return supersample;
    }

    public File file() {
        return file;
    }

    public BufferedImage thumbnail() {
        return thumbnail;
    }

    public State state() {
        return state;
    }

    /** 0 to 1 while rendering. */
    public double progress() {
        return progress;
    }

    /** Why it failed, or how long it took when done. */
    public String message() {
        return message;
    }

    public long elapsedMillis() {
        return state == State.RENDERING ? (System.nanoTime() - startedNanos) / 1_000_000 : elapsedMillis;
    }

    /** Milliseconds left at the current pace, or -1 until there is enough progress to say. */
    public long etaMillis() {
        if (state != State.RENDERING || progress < 0.03) {
            return -1;
        }
        long elapsed = elapsedMillis();
        return (long) (elapsed / progress * (1 - progress));
    }

    /** For a failed job: a lighter supersample factor worth trying, or 0 when there is none. */
    public int retryFactor() {
        return retryFactor;
    }

    /** True while the job still has to be drawn or is being drawn. */
    public boolean isActive() {
        return state == State.WAITING || state == State.RENDERING;
    }

    boolean cancelRequested() {
        return cancelRequested;
    }

    void requestCancel() {
        cancelRequested = true;
    }

    void start() {
        startedNanos = System.nanoTime();
        progress = 0;
        state = State.RENDERING;
    }

    void progress(double value) {
        progress = Math.max(progress, Math.min(1.0, value));
    }

    void finish() {
        elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000;
        progress = 1.0;
        state = State.DONE;
    }

    void fail(String reason, int retryFactor) {
        elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000;
        message = reason;
        this.retryFactor = retryFactor;
        state = State.FAILED;
    }

    /** The viewport at the picture's own resolution: the same area as the view, drawn on a bigger (or smaller) canvas. */
    public se.karnerfors.mandelbrot.math.Viewport targetViewport() {
        MandelbrotConfig.ViewportData v = view.viewport();
        return new se.karnerfors.mandelbrot.math.Viewport(
                se.karnerfors.mandelbrot.math.Complex.fromCartesian(v.real(), v.imaginary()),
                v.zoom() * framingScale, width, height);
    }
}

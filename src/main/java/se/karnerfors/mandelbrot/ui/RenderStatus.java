package se.karnerfors.mandelbrot.ui;

/**
 * A snapshot of {@link FractalPanel}'s current render activity, pushed to
 * whoever calls {@code setRenderStatusListener}: whether a render is running
 * right now, how far it has gotten (fraction of tiles completed, 0..1, only
 * meaningful while rendering), and how long the most recently completed
 * render took, in milliseconds.
 */
public record RenderStatus(boolean rendering, double progress, long lastRenderMillis) {
}

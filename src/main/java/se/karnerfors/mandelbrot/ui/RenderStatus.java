package se.karnerfors.mandelbrot.ui;

/**
 * A snapshot of {@link FractalPanel}'s render activity, pushed to whoever calls
 * {@code setRenderStatusListener}.
 *
 * <p>Every navigation first draws a quick 1x preview and, once that is on screen and you are not
 * moving around, refines the same view at the chosen super-sampling. The stage says where we are
 * in that sequence.
 *
 * @param stage         what the renderer is doing right now
 * @param settleFactor  the supersample factor the view settles at (1 means the preview is the final image)
 * @param progress      fraction of tiles done in the current pass, 0..1, meaningful while rendering
 * @param millis        how long the pass that just finished took, or -1 when not applicable
 * @param refinedShare  once settled above 1x1: the share of pixels that were super-sampled, 0..1; otherwise -1
 */
public record RenderStatus(Stage stage, int settleFactor, double progress, long millis, double refinedShare) {

    public enum Stage {
        /** Nothing has been rendered yet. */
        IDLE,
        /** Drawing the quick 1x pass (the only pass when the settle factor is 1). */
        PREVIEW,
        /** The preview is on screen; drawing the final quality over it. */
        REFINING,
        /** The view is drawn at the settle factor. */
        SETTLED,
        /** The final quality could not be drawn (out of memory); the preview stays. */
        FAILED
    }

    public static RenderStatus idle() {
        return new RenderStatus(Stage.IDLE, 1, 0.0, -1, -1);
    }

    public boolean rendering() {
        return stage == Stage.PREVIEW || stage == Stage.REFINING;
    }
}

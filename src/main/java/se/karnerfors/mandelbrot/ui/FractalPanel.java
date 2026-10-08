package se.karnerfors.mandelbrot.ui;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.IllegalComponentStateException;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;

import se.karnerfors.mandelbrot.math.Complex;
import se.karnerfors.mandelbrot.math.Viewport;
import se.karnerfors.mandelbrot.io.MandelbrotConfig;
import se.karnerfors.mandelbrot.palette.Palette;
import se.karnerfors.mandelbrot.render.Colorizer;
import se.karnerfors.mandelbrot.render.IterationGrid;
import se.karnerfors.mandelbrot.render.RenderAlgorithm;
import se.karnerfors.mandelbrot.render.RenderSettings;
import se.karnerfors.mandelbrot.render.Renderer;

/**
 * The live fractal display. Mouse mapping: left-drag draws an aspect-locked
 * rubber-band box to zoom to a region, right-drag pans, the wheel zooms at
 * the cursor, a left click centres on the clicked point (and moves the pointer
 * there) and a right click
 * asks for a coordinate to centre on. Every interaction updates {@link #currentViewport} immediately
 * and repaints using an affine transform of the last completed render, so
 * the view always feels instant; a background render then catches up.
 *
 * <p>Rendering itself is parallel and progressive: the target view is split
 * into small tiles, computed across a fixed pool of {@code
 * Runtime.availableProcessors()} worker threads, and each tile is painted in
 * as soon as it lands rather than waiting for the whole image -- so tiles
 * from different threads visibly pop in, in whatever order they finish. A
 * render-generation counter guards both the whole-render promotion and each
 * individual tile, so a view change mid-render discards stale results (and
 * lets tiles still queued behind others skip their work entirely) instead of
 * racing the new one.
 */
public final class FractalPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final int MIN_DRAG_PX = 4;
    private static final int WHEEL_DEBOUNCE_MS = 120;
    private static final int RESIZE_DEBOUNCE_MS = 150;
    private static final double WHEEL_ZOOM_STEP = 1.3;

    /** Tile edge length in *final* (display) pixels; multiplied by the supersample factor for the actual grid tiling. */
    private static final int TILE_SIZE_FINAL_PIXELS = 24;

    private enum Mode { NONE, SELECT, PAN }

    private Viewport currentViewport;

    private Viewport committedViewport;
    private BufferedImage renderedImage;

    /** The render currently in flight: its target viewport and the image being filled in tile by tile, or null when idle. */
    private Viewport inProgressViewport;
    private BufferedImage progressiveImage;

    private IterationGrid lastGrid;
    private int lastSupersampleFactor = 1;
    private long lastSettledMillis = -1;

    /** A 1x preview is on screen and the refinement at the settle factor has not been able to start yet. */
    private boolean pendingRefine;

    private Palette palette = Palette.classicRedWhiteBlack();
    private RenderSettings renderSettings = RenderSettings.defaults();

    private volatile long renderGeneration = 0;
    private final Deque<Viewport> history = new ArrayDeque<>();

    private final ExecutorService executor = newRenderExecutor();
    private int totalTiles;
    private int completedTiles;
    private long renderStartNanos;

    private Mode mode = Mode.NONE;
    private int dragStartX, dragStartY;
    private Rectangle dragRect;
    private double panLiveDx, panLiveDy;

    private Robot cursorRobot;

    private final Timer wheelRenderTimer;
    private final Timer resizeRenderTimer;

    private Consumer<Viewport> viewportListener = _ -> { };
    private Consumer<RenderStatus> renderStatusListener = _ -> { };
    private Consumer<Complex> cursorMoveListener = _ -> { };

    public FractalPanel(int initialWidth, int initialHeight) {
        setPreferredSize(new java.awt.Dimension(initialWidth, initialHeight));
        setBackground(Color.DARK_GRAY);
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));

        this.currentViewport = Viewport.classicFullView(initialWidth, initialHeight);

        this.wheelRenderTimer = new Timer(WHEEL_DEBOUNCE_MS, _ -> submitRender());
        this.wheelRenderTimer.setRepeats(false);
        this.resizeRenderTimer = new Timer(RESIZE_DEBOUNCE_MS, _ -> submitRender());
        this.resizeRenderTimer.setRepeats(false);

        MouseAdapter dragHandler = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                dragStartX = e.getX();
                dragStartY = e.getY();
                if (SwingUtilities.isLeftMouseButton(e)) {
                    mode = Mode.SELECT;
                    dragRect = new Rectangle(dragStartX, dragStartY, 0, 0);
                } else if (SwingUtilities.isRightMouseButton(e)) {
                    mode = Mode.PAN;
                    panLiveDx = 0;
                    panLiveDy = 0;
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                } else {
                    mode = Mode.NONE;
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (mode == Mode.SELECT) {
                    dragRect = aspectLockedRect(dragStartX, dragStartY, e.getX(), e.getY());
                    repaint();
                } else if (mode == Mode.PAN) {
                    panLiveDx = e.getX() - dragStartX;
                    panLiveDy = e.getY() - dragStartY;
                    repaint();
                }
                notifyCursorMoved(e.getX(), e.getY());
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                boolean click = false;
                boolean leftButton = mode == Mode.SELECT;
                if (mode == Mode.SELECT) {
                    if (dragRect != null && dragRect.width >= MIN_DRAG_PX && dragRect.height >= MIN_DRAG_PX) {
                        commitZoomToRect(dragRect);
                    } else {
                        click = true;
                    }
                    dragRect = null;
                } else if (mode == Mode.PAN) {
                    if (Math.abs(panLiveDx) >= MIN_DRAG_PX || Math.abs(panLiveDy) >= MIN_DRAG_PX) {
                        currentViewport = currentViewport.panned(panLiveDx, panLiveDy);
                        notifyViewportChanged();
                        submitRender();
                    } else {
                        click = true;
                    }
                    panLiveDx = 0;
                    panLiveDy = 0;
                    setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
                }
                mode = Mode.NONE;
                repaint();
                if (click) {
                    handleClick(dragStartX, dragStartY, leftButton);
                }
                tryStartRefine();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                cursorMoveListener.accept(null);
            }
        };
        addMouseListener(dragHandler);
        addMouseMotionListener(dragHandler);

        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                notifyCursorMoved(e.getX(), e.getY());
            }
        });

        addMouseWheelListener(e -> {
            double factor = Math.pow(WHEEL_ZOOM_STEP, -e.getPreciseWheelRotation());
            currentViewport = currentViewport.zoomedAround(e.getX(), e.getY(), factor);
            notifyViewportChanged();
            repaint();
            wheelRenderTimer.restart();
        });

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (getWidth() <= 0 || getHeight() <= 0) {
                    return;
                }
                currentViewport = currentViewport.withSize(getWidth(), getHeight());
                notifyViewportChanged();
                repaint();
                resizeRenderTimer.restart();
            }
        });
    }

    private static ExecutorService newRenderExecutor() {
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "fractal-render-worker");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(threads, factory);
    }

    // --- public API -----------------------------------------------------

    public Viewport getCurrentViewport() {
        return currentViewport;
    }

    public boolean hasHistory() {
        return !history.isEmpty();
    }

    public Palette getPalette() {
        return palette;
    }

    public RenderSettings getRenderSettings() {
        return renderSettings;
    }

    /** Number of worker threads used to render tiles in parallel. */
    public int getWorkerThreadCount() {
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    public void goHome() {
        history.push(currentViewport);
        currentViewport = renderSettings.algorithm() == RenderAlgorithm.JULIA
                ? Viewport.juliaFullView(getWidth(), getHeight())
                : Viewport.classicFullView(getWidth(), getHeight());
        notifyViewportChanged();
        repaint();
        submitRender();
    }

    public void goBack() {
        if (history.isEmpty()) {
            return;
        }
        currentViewport = history.pop();
        notifyViewportChanged();
        repaint();
        submitRender();
    }

    /** Recolors the current render from cached iteration data -- no recompute. */
    public void setPalette(Palette newPalette) {
        setPalette(newPalette, null);
    }

    /**
     * Same as {@link #setPalette(Palette)}, but colorizing and downsampling
     * the (possibly heavily supersampled) full-size cached grid runs on a
     * background thread rather than the EDT -- doing that synchronously was
     * visibly freezing the UI on anything but a small/low-AA render. Guarded
     * by the same render-generation counter as a full render, so a recolor
     * that finishes after a newer viewport render has already landed is
     * dropped instead of clobbering it with stale colors.
     *
     * <p>{@code onComplete}, if non-null, runs on the EDT once the recolor
     * has either landed or been dropped as stale -- so a caller like the
     * palette editor knows when it's safe to re-enable its own controls.
     */
    public void setPalette(Palette newPalette, Runnable onComplete) {
        this.palette = newPalette;
        if (lastGrid == null) {
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }

        final IterationGrid gridSnapshot = lastGrid;
        final int factorSnapshot = lastSupersampleFactor;
        final long myGeneration = renderGeneration;

        SwingWorker<BufferedImage, Void> worker = new SwingWorker<>() {
            @Override
            protected BufferedImage doInBackground() {
                BufferedImage colorized = Colorizer.colorize(gridSnapshot, newPalette);
                return Colorizer.downsample(colorized, factorSnapshot);
            }

            @Override
            protected void done() {
                if (myGeneration == renderGeneration) {
                    try {
                        renderedImage = get();
                        repaint();
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        };
        worker.execute();
    }

    /** Centres the view on {@code point}, keeping the zoom; Back returns to the previous view. */
    public void centerOn(Complex point) {
        history.push(currentViewport);
        currentViewport = currentViewport.withCenter(point);
        notifyViewportChanged();
        repaint();
        submitRender();
    }

    /**
     * A click without a drag: left centres on the clicked point, right opens
     * the coordinate dialog pre-filled with the clicked point.
     */
    private void handleClick(int screenX, int screenY, boolean leftButton) {
        Complex clicked = currentViewport.toComplex(screenX, screenY);
        if (leftButton) {
            centerOn(clicked);
            moveCursorToCenter();
        } else {
            promptForCenter(clicked);
        }
    }

    /** Warps the mouse pointer to the middle of the panel, i.e. onto the point the view was just centred on. Best effort. */
    private void moveCursorToCenter() {
        try {
            if (cursorRobot == null) {
                cursorRobot = new Robot();
            }
            Point origin = getLocationOnScreen();
            cursorRobot.mouseMove(origin.x + getWidth() / 2, origin.y + getHeight() / 2);
        } catch (AWTException | SecurityException | IllegalComponentStateException ex) {
            // No permission to move the pointer (headless, some Wayland/macOS setups): leave it where it is.
        }
    }

    private void promptForCenter(Complex initial) {
        GoToDialog.show(SwingUtilities.getWindowAncestor(this), initial, currentViewport.zoom(), this::goTo);
    }

    /** Opens the Go to dialog pre-filled with the current centre and zoom. */
    public void promptGoTo() {
        GoToDialog.show(SwingUtilities.getWindowAncestor(this), currentViewport.center(), currentViewport.zoom(),
                this::goTo);
    }

    /** Jumps to {@code center} at {@code zoom}; Back returns to the previous view. */
    public void goTo(Complex center, double zoom) {
        history.push(currentViewport);
        currentViewport = new Viewport(center, zoom, getWidth(), getHeight());
        notifyViewportChanged();
        repaint();
        submitRender();
    }

    /**
     * Changing only the settle factor does not redo the preview: if the view on screen is already drawn,
     * it is simply refined (or left alone if it is already at that quality). Anything else re-renders.
     */
    public void setRenderSettings(RenderSettings newSettings) {
        RenderSettings old = this.renderSettings;
        this.renderSettings = newSettings;
        int settle = settleFactor();
        boolean onlySettleChanged = old.withSupersampleFactor(newSettings.supersampleFactor()).equals(newSettings);
        if (onlySettleChanged && settle > 1 && lastGrid != null && committedViewport == currentViewport) {
            pendingRefine = false;
            if (lastSupersampleFactor == settle && inProgressViewport == null) {
                reportStatus(RenderStatus.Stage.SETTLED, settle, 1.0, lastSettledMillis);
            } else {
                startPass(Pass.REFINE);
            }
            return;
        }
        submitRender();
    }

    /** Swaps the settings and jumps to the home view for them, with a single render. */
    public void setRenderSettingsAndGoHome(RenderSettings newSettings) {
        this.renderSettings = newSettings;
        goHome();
    }

    /**
     * Applies a loaded {@link MandelbrotConfig}: jumps to its center/zoom
     * (sized to this panel's *current* width/height -- window size is
     * deliberately never part of a saved config) and switches to its
     * palette, then triggers a fresh render. The previous viewport is
     * pushed onto history so Back still works afterwards.
     */
    public void applyConfig(MandelbrotConfig config) {
        history.push(currentViewport);
        currentViewport = config.toViewport(getWidth(), getHeight());
        this.palette = config.toPalette();
        this.renderSettings = config.applyTo(renderSettings);
        notifyViewportChanged();
        repaint();
        submitRender();
    }

    /**
     * Restores the view remembered from the previous run: same position, palette, fractal and
     * supersample factor, but no history entry and no explicit render -- the first layout pass of the
     * window renders it. Only meant to be called before the window is shown.
     */
    public void restoreSession(MandelbrotConfig view, int supersampleFactor) {
        java.awt.Dimension size = getPreferredSize();
        int width = getWidth() > 0 ? getWidth() : size.width;
        int height = getHeight() > 0 ? getHeight() : size.height;
        // Build everything first so a bad palette can't leave the panel half restored.
        Viewport restoredViewport = view.toViewport(width, height);
        Palette restoredPalette = view.toPalette();
        RenderSettings restoredSettings = view.applyTo(renderSettings).withSupersampleFactor(supersampleFactor);
        currentViewport = restoredViewport;
        palette = restoredPalette;
        renderSettings = restoredSettings;
        notifyViewportChanged();
        repaint();
    }

    public void setViewportListener(Consumer<Viewport> listener) {
        this.viewportListener = listener != null ? listener : _ -> { };
    }

    public void setRenderStatusListener(Consumer<RenderStatus> listener) {
        this.renderStatusListener = listener != null ? listener : _ -> { };
    }

    public void setCursorMoveListener(Consumer<Complex> listener) {
        this.cursorMoveListener = listener != null ? listener : _ -> { };
    }

    // --- interaction helpers ---------------------------------------------

    private Rectangle aspectLockedRect(int startX, int startY, int currentX, int currentY) {
        double aspect = getHeight() == 0 ? 1.0 : (double) getWidth() / (double) getHeight();
        int rawW = currentX - startX;
        int rawH = currentY - startY;
        int signW = rawW < 0 ? -1 : 1;
        int signH = rawH < 0 ? -1 : 1;

        double magW = Math.abs(rawW);
        double magH = Math.abs(rawH);
        double magHFromW = magW / aspect;

        double finalW;
        double finalH;
        if (magHFromW >= magH) {
            finalW = magW * signW;
            finalH = magHFromW * signH;
        } else {
            double magWFromH = magH * aspect;
            finalH = magH * signH;
            finalW = magWFromH * signW;
        }

        int x = startX + Math.min(0, (int) Math.round(finalW));
        int y = startY + Math.min(0, (int) Math.round(finalH));
        int w = Math.abs((int) Math.round(finalW));
        int h = Math.abs((int) Math.round(finalH));
        return new Rectangle(x, y, w, h);
    }

    private void commitZoomToRect(Rectangle rect) {
        int centerX = rect.x + rect.width / 2;
        int centerY = rect.y + rect.height / 2;
        Complex newCenter = currentViewport.toComplex(centerX, centerY);
        double scaleFactor = (double) getWidth() / (double) rect.width;
        double newZoom = currentViewport.zoom() * scaleFactor;

        history.push(currentViewport);
        currentViewport = new Viewport(newCenter, newZoom, getWidth(), getHeight());
        notifyViewportChanged();
        submitRender();
    }

    private void notifyCursorMoved(int screenX, int screenY) {
        cursorMoveListener.accept(currentViewport.toComplex(screenX, screenY));
    }

    private void notifyViewportChanged() {
        viewportListener.accept(currentViewport);
    }

    // --- rendering --------------------------------------------------------

    /** The two kinds of render pass: the quick 1x draw after navigating, and the refinement at the settle factor. */
    private enum Pass { PREVIEW, REFINE }

    /**
     * Called after every navigation (pan, zoom, jump, resize, new fractal...). Draws the quick 1x
     * preview; when that lands and you are not moving around, {@link #tryStartRefine()} follows up with
     * the settle factor. Anything still running for the old view is dropped.
     */
    private void submitRender() {
        pendingRefine = false;
        startPass(Pass.PREVIEW);
    }

    private int settleFactor() {
        return Math.max(1, renderSettings.supersampleFactor());
    }

    /** True when the view being shown is {@code viewport} and nothing is about to change it. */
    private boolean isViewSteady(Viewport viewport) {
        return viewport != null && viewport == currentViewport && mode == Mode.NONE
                && panLiveDx == 0 && panLiveDy == 0
                && !wheelRenderTimer.isRunning() && !resizeRenderTimer.isRunning();
    }

    /** Starts the refinement if a preview is waiting for one and the view has come to rest. */
    private void tryStartRefine() {
        if (pendingRefine && isViewSteady(committedViewport)) {
            pendingRefine = false;
            startPass(Pass.REFINE);
        }
    }

    private void startPass(Pass pass) {
        final Viewport target = currentViewport;
        final Palette activePalette = palette;
        final RenderSettings settings = renderSettings;
        final int settle = settleFactor();
        final int factor = pass == Pass.PREVIEW ? 1 : settle;
        final RenderStatus.Stage stage = pass == Pass.PREVIEW ? RenderStatus.Stage.PREVIEW : RenderStatus.Stage.REFINING;
        final int oversampledTileSize = TILE_SIZE_FINAL_PIXELS * factor;
        final long myGeneration = ++renderGeneration;

        final BufferedImage myProgressiveImage = new BufferedImage(target.width(), target.height(), BufferedImage.TYPE_INT_ARGB);
        this.inProgressViewport = target;
        this.progressiveImage = myProgressiveImage;
        this.totalTiles = tileCount(target.width(), target.height(), TILE_SIZE_FINAL_PIXELS);
        this.completedTiles = 0;
        this.renderStartNanos = System.nanoTime();
        reportStatus(stage, settle, 0.0, -1);

        SwingWorker<IterationGrid, Void> worker = new SwingWorker<>() {
            @Override
            protected IterationGrid doInBackground() throws Exception {
                if (myGeneration != renderGeneration) {
                    return null; // superseded before it started: don't even allocate the grid
                }
                Viewport oversampled = target.supersampled(factor);
                return Renderer.computeGridParallel(oversampled, settings, executor, oversampledTileSize,
                        () -> myGeneration != renderGeneration,
                        (grid, tile) -> onTileComplete(myGeneration, myProgressiveImage, activePalette, factor, stage,
                                settle, grid, tile));
            }

            @Override
            protected void done() {
                if (myGeneration != renderGeneration) {
                    return; // superseded by a newer interaction -- discard
                }
                long elapsedMillis = (System.nanoTime() - renderStartNanos) / 1_000_000;
                try {
                    IterationGrid grid = get();
                    lastGrid = grid;
                    lastSupersampleFactor = factor;
                    renderedImage = myProgressiveImage;
                    committedViewport = target;
                    inProgressViewport = null;
                    progressiveImage = null;
                } catch (ExecutionException ex) {
                    inProgressViewport = null;
                    progressiveImage = null;
                    if (ex.getCause() instanceof OutOfMemoryError) {
                        // Keep whatever is on screen (the preview) and say why we could not do better.
                        reportStatus(RenderStatus.Stage.FAILED, settle, 0.0, -1);
                    } else {
                        ex.printStackTrace();
                        reportStatus(RenderStatus.Stage.IDLE, settle, 0.0, -1);
                    }
                    repaint();
                    return;
                } catch (InterruptedException | CancellationException ex) {
                    return;
                }

                if (pass == Pass.PREVIEW && settle > 1) {
                    // The preview is up. Refine right away unless the user is already moving again.
                    pendingRefine = true;
                    reportStatus(RenderStatus.Stage.REFINING, settle, 0.0, -1);
                    repaint();
                    tryStartRefine();
                } else {
                    lastSettledMillis = elapsedMillis;
                    reportStatus(RenderStatus.Stage.SETTLED, factor, 1.0, elapsedMillis);
                    repaint();
                    if (palette != activePalette) {
                        setPalette(palette); // the palette changed while this was drawing: recolour the fresh grid
                    }
                }
            }
        };
        worker.execute();
    }

    /**
     * Runs on a render-worker thread once one tile's grid cells are filled.
     * Colorizing and downsampling a single small tile is cheap and pure, so
     * it happens right here; only the resulting handful of pixels get
     * composited into {@code myProgressiveImage} (this specific render's own
     * buffer, captured as a local by the caller -- never the field, which a
     * newer render may have already replaced by the time a late tile from
     * this one lands), via invokeLater, keeping every Swing-state mutation
     * on the EDT.
     */
    private void onTileComplete(long myGeneration, BufferedImage myProgressiveImage, Palette activePalette, int factor,
            RenderStatus.Stage stage, int settle, IterationGrid grid, Rectangle oversampledTile) {
        BufferedImage tileOversampled = Colorizer.colorizeRegion(grid, activePalette, oversampledTile);
        BufferedImage tileFinal = Colorizer.downsample(tileOversampled, factor);
        int finalX = oversampledTile.x / factor;
        int finalY = oversampledTile.y / factor;

        SwingUtilities.invokeLater(() -> {
            if (myGeneration != renderGeneration) {
                return; // superseded; drop this tile's result
            }
            Graphics2D g = myProgressiveImage.createGraphics();
            g.setComposite(java.awt.AlphaComposite.Src);
            g.drawImage(tileFinal, finalX, finalY, null);
            g.dispose();
            completedTiles++;
            reportStatus(stage, settle, totalTiles == 0 ? 1.0 : (double) completedTiles / totalTiles, -1);
            repaint(finalX, finalY, tileFinal.getWidth(), tileFinal.getHeight());
        });
    }

    private void reportStatus(RenderStatus.Stage stage, int settle, double progress, long millis) {
        renderStatusListener.accept(new RenderStatus(stage, settle, progress, millis));
    }

    private static int tileCount(int width, int height, int tileSize) {
        int cols = (width + tileSize - 1) / tileSize;
        int rows = (height + tileSize - 1) / tileSize;
        return cols * rows;
    }

    /** The viewport we should visually represent right now, including any in-progress pan. */
    private Viewport effectiveLiveViewport() {
        if (panLiveDx != 0 || panLiveDy != 0) {
            return currentViewport.panned(panLiveDx, panLiveDy);
        }
        return currentViewport;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        Viewport live = effectiveLiveViewport();
        drawLayer((Graphics2D) g, renderedImage, committedViewport, live);
        drawLayer((Graphics2D) g, progressiveImage, inProgressViewport, live);

        if (dragRect != null) {
            Graphics2D overlay = (Graphics2D) g.create();
            overlay.setColor(new Color(255, 255, 255, 60));
            overlay.fill(dragRect);
            overlay.setColor(Color.WHITE);
            overlay.draw(dragRect);
            overlay.dispose();
        }
    }

    /** Draws one image layer, transformed from the viewport it represents onto the viewport we're currently showing. */
    private void drawLayer(Graphics2D g, BufferedImage image, Viewport imageViewport, Viewport live) {
        if (image == null || imageViewport == null) {
            return;
        }
        double s = live.zoom() / imageViewport.zoom();
        double w = getWidth();
        double h = getHeight();
        double tx = w / 2.0 * (1 - s) + (imageViewport.center().real - live.center().real) * live.zoom();
        double ty = h / 2.0 * (1 - s) + (imageViewport.center().imaginary - live.center().imaginary) * live.zoom();

        Graphics2D layer = (Graphics2D) g.create();
        layer.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        layer.translate(tx, ty);
        layer.scale(s, s);
        layer.drawImage(image, 0, 0, null);
        layer.dispose();
    }
}

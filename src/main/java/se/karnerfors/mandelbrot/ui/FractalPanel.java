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
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;

import se.karnerfors.mandelbrot.math.Complex;
import se.karnerfors.mandelbrot.math.Viewport;
import se.karnerfors.mandelbrot.io.MandelbrotConfig;
import se.karnerfors.mandelbrot.palette.Palette;
import se.karnerfors.mandelbrot.render.AdaptiveSampler;
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

    /** Tile edge length in pixels, for both the preview and the refinement. */
    private static final int TILE_SIZE = 24;

    private enum Mode { NONE, SELECT, PAN, TRACK }

    private Viewport currentViewport;

    private Viewport committedViewport;
    private BufferedImage renderedImage;

    /** The render currently in flight: its target viewport and the image being filled in tile by tile, or null when idle. */
    private Viewport inProgressViewport;
    private BufferedImage progressiveImage;

    /**
     * The committed view's samples: one per pixel (the preview's), plus the extra samples of the pixels the
     * refinement super-sampled, one {@link AdaptiveSampler.Refinement} per tile of {@link #lastTiles}. Without a
     * refinement {@code lastRefinements} is null and the factor is 1.
     */
    private IterationGrid lastGrid;
    private List<Rectangle> lastTiles;
    private AdaptiveSampler.Refinement[] lastRefinements;
    private int lastSupersampleFactor = 1;
    /** The share of pixels the committed view's refinement super-sampled, or -1 without a refinement. */
    private double lastRefinedShare = -1;
    private long lastSettledMillis = -1;

    /** Bumped on every palette change so a slow recolour that finishes late cannot overwrite a newer one. */
    private long paletteSeq;
    private boolean liveRecolourRunning;
    private boolean liveRecolourWanted;

    /** A 1x preview is on screen and the refinement at the settle factor has not been able to start yet. */
    private boolean pendingRefine;

    private Palette palette = Palette.classicRedWhiteBlack();
    private RenderSettings renderSettings = RenderSettings.defaults();

    private volatile long renderGeneration = 0;

    /** Which pass is drawing right now (UI thread only), so a refinement can be stopped the moment you move. */
    private Pass runningPass;
    private final Deque<Viewport> history = new ArrayDeque<>();

    private final ExecutorService executor = newRenderExecutor();
    /** Progress of the pass in flight: tiles for the preview, samples to compute for the refinement. */
    private long totalWork;
    private long completedWork;
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
    private Consumer<Palette> paletteListener = _ -> { };

    /** What a left press and drag do instead of zooming, when this panel is used to pick a point. */
    public interface PointTracker {
        /** The button went down or moved while down, at this point. */
        void moved(Complex point);

        /** The button went up. */
        void ended();

        /** A second press in quick succession (no move or end follows it). */
        void doubleClicked();
    }

    private PointTracker pointTracker;

    /** While true, the refinement after a quick preview waits (it starts when this is set false again). */
    private boolean refinementHeld;

    /** A crosshair drawn at this point (the Julia constant, on the Mandelbrot picker), or null. */
    private Complex marker;

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
                stopRefinement();
                dragStartX = e.getX();
                dragStartY = e.getY();
                if (SwingUtilities.isLeftMouseButton(e) && pointTracker != null && e.getClickCount() >= 2) {
                    mode = Mode.NONE;
                    pointTracker.doubleClicked();
                } else if (SwingUtilities.isLeftMouseButton(e) && pointTracker != null) {
                    mode = Mode.TRACK;
                    pointTracker.moved(currentViewport.toComplex(e.getX(), e.getY()));
                } else if (SwingUtilities.isLeftMouseButton(e)) {
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
                if (mode == Mode.TRACK) {
                    pointTracker.moved(currentViewport.toComplex(e.getX(), e.getY()));
                } else if (mode == Mode.SELECT) {
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
                if (mode == Mode.TRACK) {
                    pointTracker.ended();
                } else if (mode == Mode.SELECT) {
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
            stopRefinement();
            double factor = Viewport.limitZoomOut(currentViewport.zoom(),
                    Math.pow(WHEEL_ZOOM_STEP, -e.getPreciseWheelRotation()));
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
                stopRefinement();
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

    /** What the panel shows right now, as an image of its own size (for bookmark thumbnails). Call on the UI thread. */
    public BufferedImage snapshot() {
        BufferedImage image = new BufferedImage(Math.max(1, getWidth()), Math.max(1, getHeight()),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            paint(g);
        } finally {
            g.dispose();
        }
        return image;
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
        // The panel may have changed size since (the split opening or closing), so keep the place, not the size.
        currentViewport = history.pop().withSize(getWidth(), getHeight());
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
        paletteListener.accept(newPalette);
        recolour(onComplete);
    }

    /**
     * Recolours the committed view with the current palette from its stored samples, in the background. A
     * refined view is then topped up: the new palette may want pixels refined that the old one did not (faster
     * cycling, more contrast), and only those are sampled.
     */
    private void recolour(Runnable onComplete) {
        final Palette newPalette = palette;
        this.liveRecolourWanted = false;
        final long mySeq = ++paletteSeq;
        if (lastGrid == null) {
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }

        final IterationGrid gridSnapshot = lastGrid;
        final List<Rectangle> tilesSnapshot = lastTiles;
        final AdaptiveSampler.Refinement[] refinementsSnapshot = lastRefinements;
        final long myGeneration = renderGeneration;

        SwingWorker<BufferedImage, Void> worker = new SwingWorker<>() {
            @Override
            protected BufferedImage doInBackground() {
                return colorizeAll(gridSnapshot, tilesSnapshot, refinementsSnapshot, newPalette);
            }

            @Override
            protected void done() {
                boolean landed = false;
                if (myGeneration == renderGeneration && mySeq == paletteSeq) {
                    try {
                        renderedImage = get();
                        landed = true;
                        repaint();
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
                if (onComplete != null) {
                    onComplete.run();
                }
                if (landed && runningPass == null && lastRefinements != null && lastSupersampleFactor == settleFactor()) {
                    pendingRefine = true;
                    tryStartRefine();
                }
            }
        };
        worker.execute();
    }

    /**
     * Shows {@code newPalette} right away while it is being edited: the drawn view is recoloured from the
     * preview's samples, one per pixel (quick, but without the super-sampling average).
     * New renders already use this palette. Follow up with {@link #setPalette(Palette, Runnable)} to get
     * full quality, or with it again using the old palette to revert.
     */
    public void setPaletteLive(Palette newPalette) {
        this.palette = newPalette;
        paletteListener.accept(newPalette);
        this.liveRecolourWanted = true;
        paletteSeq++;
        startLiveRecolour();
    }

    private void startLiveRecolour() {
        if (liveRecolourRunning || !liveRecolourWanted || lastGrid == null) {
            return;
        }
        liveRecolourRunning = true;
        final IterationGrid grid = lastGrid;
        final Palette live = palette;
        final long generation = renderGeneration;
        final long seq = paletteSeq;
        new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() {
                return Colorizer.colorize(grid, live);
            }

            @Override
            protected void done() {
                liveRecolourRunning = false;
                if (generation == renderGeneration && seq == paletteSeq) {
                    try {
                        renderedImage = get();
                        liveRecolourWanted = false;
                        repaint();
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
                if (seq != paletteSeq) {
                    startLiveRecolour(); // an edit arrived meanwhile: go again with the newest palette
                }
            }
        }.execute();
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
     * Changing only the super-sampling does not redo the preview: if the view on screen is already drawn,
     * it is simply refined (back at 1x1 it just drops the refinement, or it is left alone if it is already at
     * that quality). Anything else re-renders.
     */
    public void setRenderSettings(RenderSettings newSettings) {
        RenderSettings old = this.renderSettings;
        this.renderSettings = newSettings;
        int settle = settleFactor();
        boolean onlySettleChanged = old.withSupersampleFactor(newSettings.supersampleFactor()).equals(newSettings);
        if (onlySettleChanged && lastGrid != null && committedViewport == currentViewport) {
            pendingRefine = false;
            if (lastSupersampleFactor == settle && inProgressViewport == null) {
                reportStatus(RenderStatus.Stage.SETTLED, settle, 1.0, lastSettledMillis);
            } else if (settle == 1) {
                stopRefinement();
                pendingRefine = false;
                lastRefinements = null;
                lastSupersampleFactor = 1;
                lastRefinedShare = -1;
                recolour(null);
                reportStatus(RenderStatus.Stage.SETTLED, 1, 1.0, -1);
            } else {
                startPass(Pass.REFINE);
            }
            return;
        }
        submitRender();
    }

    /** Swaps the settings without drawing anything: the caller follows up with a jump ({@link #goHome()}, {@link #goTo}). */
    public void swapRenderSettings(RenderSettings newSettings) {
        this.renderSettings = newSettings;
    }

    /** Swaps the settings and jumps to {@code center} at {@code zoom}, with a single render. */
    public void setRenderSettingsAndGoTo(RenderSettings newSettings, Complex center, double zoom) {
        this.renderSettings = newSettings;
        goTo(center, zoom);
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
        paletteListener.accept(palette);
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

    /** Told whenever the palette changes, so a second panel can follow it. */
    public void setPaletteListener(Consumer<Palette> listener) {
        this.paletteListener = listener != null ? listener : _ -> { };
    }

    /** Makes the left button pick points (press and drag) instead of zooming; the wheel and right button still work. */
    public void setPointTracker(PointTracker tracker) {
        this.pointTracker = tracker;
    }

    /** Holds back the refinement while something else is driving this panel quickly (a drag elsewhere). */
    public void setRefinementHeld(boolean held) {
        if (refinementHeld == held) {
            return;
        }
        refinementHeld = held;
        if (!held) {
            tryStartRefine();
        }
    }

    /** Shows a crosshair at {@code point}; null removes it. */
    public void setMarker(Complex point) {
        this.marker = point == null ? null : Complex.fromCartesian(point.real, point.imaginary);
        repaint();
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

    /**
     * Drops a refinement that is still drawing, at once, so dragging or zooming does not compete with it for
     * the processors. The 1x preview stays on screen and the refinement starts over when the view is at rest
     * again ({@link #tryStartRefine()}). A preview pass is left alone: it is what you are looking at.
     */
    private void stopRefinement() {
        if (runningPass != Pass.REFINE) {
            return;
        }
        runningPass = null;
        renderGeneration++;
        inProgressViewport = null;
        progressiveImage = null;
        pendingRefine = true;
        reportStatus(RenderStatus.Stage.REFINING, settleFactor(), 0.0, -1);
        repaint();
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

    /** Starts the refinement if a preview (or a recoloured view, for its top-up) is waiting for one and the view has come to rest. */
    private void tryStartRefine() {
        if (pendingRefine && !refinementHeld && isViewSteady(committedViewport)) {
            pendingRefine = false;
            startPass(Pass.REFINE);
        }
    }

    /** What a pass leaves behind: the view's samples, and the refinement per tile (null for the preview). */
    private record PassResult(IterationGrid grid, AdaptiveSampler.Refinement[] refinements) {
    }

    private void startPass(Pass pass) {
        final Viewport target = currentViewport;
        // The refinement builds on the preview's samples of this very view; without them, draw the preview first.
        if (pass == Pass.REFINE && (lastGrid == null || committedViewport != target)) {
            submitRender();
            return;
        }
        final Palette activePalette = palette;
        final RenderSettings settings = renderSettings;
        final int settle = settleFactor();
        final RenderStatus.Stage stage = pass == Pass.PREVIEW ? RenderStatus.Stage.PREVIEW : RenderStatus.Stage.REFINING;
        final List<Rectangle> tiles = Renderer.computeTiles(target.width(), target.height(), TILE_SIZE);
        final IterationGrid base = pass == Pass.REFINE ? lastGrid : null;
        // What an earlier refinement at this factor already sampled is kept (a top-up after a palette change).
        final AdaptiveSampler.Refinement[] existing =
                pass == Pass.REFINE && lastSupersampleFactor == settle ? lastRefinements : null;
        final long myGeneration = ++renderGeneration;
        runningPass = pass;

        final BufferedImage myProgressiveImage = new BufferedImage(target.width(), target.height(), BufferedImage.TYPE_INT_ARGB);
        this.inProgressViewport = target;
        this.progressiveImage = myProgressiveImage;
        this.totalWork = pass == Pass.PREVIEW ? tiles.size() : 0;
        this.completedWork = 0;
        this.renderStartNanos = System.nanoTime();
        reportStatus(stage, settle, 0.0, -1);

        SwingWorker<PassResult, Void> worker = new SwingWorker<>() {
            @Override
            protected PassResult doInBackground() throws Exception {
                if (myGeneration != renderGeneration) {
                    return null; // superseded before it started: don't even allocate the grid
                }
                if (pass == Pass.REFINE) {
                    AdaptiveSampler.Refinement[] refinements = refineTiles(myGeneration, target, base, tiles, existing,
                            settle, settings, activePalette, myProgressiveImage, stage, settle);
                    return refinements == null ? null : new PassResult(base, refinements);
                }
                IterationGrid grid = Renderer.computeGridParallel(target, settings, executor, TILE_SIZE,
                        () -> myGeneration != renderGeneration,
                        (g, tile) -> paintTile(myGeneration, myProgressiveImage, Colorizer.colorizeRegion(g, activePalette, tile),
                                tile, 1, stage, settle));
                return new PassResult(grid, null);
            }

            @Override
            protected void done() {
                if (myGeneration != renderGeneration) {
                    return; // superseded by a newer interaction -- discard
                }
                runningPass = null;
                long elapsedMillis = (System.nanoTime() - renderStartNanos) / 1_000_000;
                try {
                    PassResult result = get();
                    if (result == null) {
                        return;
                    }
                    lastGrid = result.grid();
                    lastTiles = tiles;
                    lastRefinements = result.refinements();
                    lastSupersampleFactor = result.refinements() == null ? 1 : settle;
                    lastRefinedShare = refinedShare(result.refinements(), target);
                    // A top-up that found nothing new to sample painted nothing: what is on screen is already right.
                    boolean unchanged = existing != null && result.refinements() == existing;
                    if (!unchanged) {
                        renderedImage = myProgressiveImage;
                    }
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
                    // A top-up adds to the time the view took to draw rather than replacing it.
                    lastSettledMillis = existing != null && lastSettledMillis >= 0 ? lastSettledMillis + elapsedMillis
                            : elapsedMillis;
                    reportStatus(RenderStatus.Stage.SETTLED, lastSupersampleFactor, 1.0, lastSettledMillis);
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
     * The refinement pass, on a background thread: finds the pixels each tile should super-sample (see
     * {@link AdaptiveSampler}), then samples and paints the tiles on the render pool. Progress counts the samples
     * still to compute, so tiles with nothing to refine do not make it jump. Returns null when superseded.
     */
    private AdaptiveSampler.Refinement[] refineTiles(long myGeneration, Viewport target, IterationGrid base,
            List<Rectangle> tiles, AdaptiveSampler.Refinement[] existing, int factor, RenderSettings settings,
            Palette activePalette, BufferedImage myProgressiveImage, RenderStatus.Stage stage, int settle)
            throws InterruptedException {
        BooleanSupplier isCancelled = () -> myGeneration != renderGeneration;
        int threshold = AdaptiveSampler.threshold();
        int count = tiles.size();
        boolean[][] masks = new boolean[count][];
        long[] work = new long[count];
        forEachTile(count, myGeneration, i -> {
            masks[i] = AdaptiveSampler.mask(base, activePalette, tiles.get(i), threshold);
            long fresh = 0;
            for (int k = 0; k < masks[i].length; k++) {
                if (masks[i][k] && (existing == null || !existing[i].contains(k))) {
                    fresh++;
                }
            }
            work[i] = 1 + fresh;
        });
        if (isCancelled.getAsBoolean()) {
            return null;
        }
        long total = 0;
        for (long w : work) {
            total += w;
        }
        if (existing != null && total == count) {
            return existing; // a top-up with nothing new to sample: leave the view as it is
        }
        long totalSamples = total;
        // Queued before any tile's paint, so the progress total is in place when the first tile lands.
        SwingUtilities.invokeLater(() -> {
            if (myGeneration == renderGeneration) {
                totalWork = totalSamples;
            }
        });

        AdaptiveSampler.Refinement[] result = new AdaptiveSampler.Refinement[count];
        forEachTile(count, myGeneration, i -> {
            Rectangle tile = tiles.get(i);
            AdaptiveSampler.Refinement refinement = AdaptiveSampler.refine(target, 0, 0, base, tile, masks[i], factor,
                    settings, existing == null ? null : existing[i], isCancelled);
            if (refinement != null) {
                result[i] = refinement;
                paintTile(myGeneration, myProgressiveImage, AdaptiveSampler.colorize(base, tile, refinement, activePalette),
                        tile, work[i], stage, settle);
            }
        });
        return isCancelled.getAsBoolean() ? null : result;
    }

    /**
     * Runs {@code task} for every tile index on the render pool and waits for them all; tiles still queued when
     * the render is superseded are skipped. A failure in a tile (out of memory, say) is rethrown here.
     */
    private void forEachTile(int count, long myGeneration, IntConsumer task) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(count);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int i = 0; i < count; i++) {
            int index = i;
            executor.execute(() -> {
                try {
                    if (myGeneration == renderGeneration && failure.get() == null) {
                        task.accept(index);
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        Throwable t = failure.get();
        if (t instanceof Error e) {
            throw e;
        }
        if (t instanceof RuntimeException e) {
            throw e;
        }
    }

    /**
     * Composites one finished tile into {@code myProgressiveImage} (this specific render's own buffer, captured
     * by the caller -- never the field, which a newer render may already have replaced by the time a late tile
     * lands), via invokeLater, keeping every Swing-state mutation on the EDT. {@code work} is the tile's share of
     * the pass's progress.
     */
    private void paintTile(long myGeneration, BufferedImage myProgressiveImage, BufferedImage tileImage, Rectangle tile,
            long work, RenderStatus.Stage stage, int settle) {
        SwingUtilities.invokeLater(() -> {
            if (myGeneration != renderGeneration) {
                return; // superseded; drop this tile's result
            }
            Graphics2D g = myProgressiveImage.createGraphics();
            g.setComposite(java.awt.AlphaComposite.Src);
            g.drawImage(tileImage, tile.x, tile.y, null);
            g.dispose();
            completedWork += work;
            reportStatus(stage, settle, totalWork == 0 ? 0.0 : Math.min(1.0, (double) completedWork / totalWork), -1);
            repaint(tile.x, tile.y, tile.width, tile.height);
        });
    }

    /** The share of the view's pixels that {@code refinements} super-sample, or -1 when there are none. */
    private static double refinedShare(AdaptiveSampler.Refinement[] refinements, Viewport view) {
        if (refinements == null) {
            return -1;
        }
        long refined = 0;
        for (AdaptiveSampler.Refinement refinement : refinements) {
            refined += refinement.count();
        }
        long pixels = (long) view.width() * view.height();
        return pixels == 0 ? 0 : refined / (double) pixels;
    }

    /** The whole view in {@code palette}: refined pixels average their samples, the rest use the preview's. */
    private static BufferedImage colorizeAll(IterationGrid grid, List<Rectangle> tiles,
            AdaptiveSampler.Refinement[] refinements, Palette palette) {
        if (refinements == null) {
            return Colorizer.colorize(grid, palette);
        }
        BufferedImage image = new BufferedImage(grid.width(), grid.height(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        for (int i = 0; i < tiles.size(); i++) {
            Rectangle tile = tiles.get(i);
            g.drawImage(AdaptiveSampler.colorize(grid, tile, refinements[i], palette), tile.x, tile.y, null);
        }
        g.dispose();
        return image;
    }

    private void reportStatus(RenderStatus.Stage stage, int settle, double progress, long millis) {
        double share = stage == RenderStatus.Stage.SETTLED ? lastRefinedShare : -1;
        renderStatusListener.accept(new RenderStatus(stage, settle, progress, millis, share));
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

        if (marker != null) {
            paintMarker((Graphics2D) g);
        }

        if (dragRect != null) {
            Graphics2D overlay = (Graphics2D) g.create();
            overlay.setColor(new Color(255, 255, 255, 60));
            overlay.fill(dragRect);
            overlay.setColor(Color.WHITE);
            overlay.draw(dragRect);
            overlay.dispose();
        }
    }

    private void paintMarker(Graphics2D g) {
        Viewport live = effectiveLiveViewport();
        double x = getWidth() / 2.0 + (marker.real - live.center().real) * live.zoom();
        double y = getHeight() / 2.0 + (marker.imaginary - live.center().imaginary) * live.zoom();
        if (x < -20 || y < -20 || x > getWidth() + 20 || y > getHeight() + 20) {
            return;
        }
        Graphics2D m = (Graphics2D) g.create();
        try {
            m.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            java.awt.geom.Ellipse2D ring = new java.awt.geom.Ellipse2D.Double(x - 9, y - 9, 18, 18);
            java.awt.geom.Path2D.Double ticks = new java.awt.geom.Path2D.Double();
            ticks.moveTo(x, y - 18);
            ticks.lineTo(x, y - 9);
            ticks.moveTo(x, y + 9);
            ticks.lineTo(x, y + 18);
            ticks.moveTo(x - 18, y);
            ticks.lineTo(x - 9, y);
            ticks.moveTo(x + 9, y);
            ticks.lineTo(x + 18, y);
            m.setStroke(new java.awt.BasicStroke(4f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
            m.setColor(new Color(0, 0, 0, 140));
            m.draw(ring);
            m.draw(ticks);
            m.setStroke(new java.awt.BasicStroke(2f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
            m.setColor(Color.WHITE);
            m.draw(ring);
            m.draw(ticks);
        } finally {
            m.dispose();
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

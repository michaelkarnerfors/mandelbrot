package se.karnerfors.mandelbrot.ui;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;
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
import se.karnerfors.mandelbrot.render.RenderSettings;
import se.karnerfors.mandelbrot.render.Renderer;

/**
 * The live fractal display. Mouse mapping: left-drag draws an aspect-locked
 * rubber-band box to zoom to a region, right-drag pans, the wheel zooms at
 * the cursor. Every interaction updates {@link #currentViewport} immediately
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
                if (mode == Mode.SELECT) {
                    if (dragRect != null && dragRect.width >= MIN_DRAG_PX && dragRect.height >= MIN_DRAG_PX) {
                        commitZoomToRect(dragRect);
                    }
                    dragRect = null;
                } else if (mode == Mode.PAN) {
                    if (panLiveDx != 0 || panLiveDy != 0) {
                        currentViewport = currentViewport.panned(panLiveDx, panLiveDy);
                        panLiveDx = 0;
                        panLiveDy = 0;
                        notifyViewportChanged();
                        submitRender();
                    }
                    setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
                }
                mode = Mode.NONE;
                repaint();
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
        currentViewport = Viewport.classicFullView(getWidth(), getHeight());
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

    public void setRenderSettings(RenderSettings newSettings) {
        this.renderSettings = newSettings;
        submitRender();
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
        notifyViewportChanged();
        repaint();
        submitRender();
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

    private void submitRender() {
        final Viewport target = currentViewport;
        final Palette activePalette = palette;
        final RenderSettings settings = renderSettings;
        final int factor = Math.max(1, settings.supersampleFactor());
        final int oversampledTileSize = TILE_SIZE_FINAL_PIXELS * factor;
        final long myGeneration = ++renderGeneration;

        final BufferedImage myProgressiveImage = new BufferedImage(target.width(), target.height(), BufferedImage.TYPE_INT_ARGB);
        this.inProgressViewport = target;
        this.progressiveImage = myProgressiveImage;
        this.totalTiles = tileCount(target.width(), target.height(), TILE_SIZE_FINAL_PIXELS);
        this.completedTiles = 0;
        this.renderStartNanos = System.nanoTime();
        reportStatus(true, 0.0, -1);

        SwingWorker<IterationGrid, Void> worker = new SwingWorker<>() {
            @Override
            protected IterationGrid doInBackground() throws Exception {
                Viewport oversampled = target.supersampled(factor);
                return Renderer.computeGridParallel(oversampled, settings, executor, oversampledTileSize,
                        () -> myGeneration != renderGeneration,
                        (grid, tile) -> onTileComplete(myGeneration, myProgressiveImage, activePalette, factor, grid, tile));
            }

            @Override
            protected void done() {
                if (myGeneration != renderGeneration) {
                    return; // superseded by a newer interaction -- discard
                }
                long elapsedMillis = (System.nanoTime() - renderStartNanos) / 1_000_000;
                try {
                    lastGrid = get();
                    lastSupersampleFactor = factor;
                    renderedImage = myProgressiveImage;
                    committedViewport = target;
                    inProgressViewport = null;
                    progressiveImage = null;
                } catch (Exception ex) {
                    ex.printStackTrace();
                } finally {
                    reportStatus(false, 1.0, elapsedMillis);
                    repaint();
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
            IterationGrid grid, Rectangle oversampledTile) {
        BufferedImage tileOversampled = Colorizer.colorizeRegion(grid, activePalette, oversampledTile);
        BufferedImage tileFinal = Colorizer.downsample(tileOversampled, factor);
        int finalX = oversampledTile.x / factor;
        int finalY = oversampledTile.y / factor;

        SwingUtilities.invokeLater(() -> {
            if (myGeneration != renderGeneration) {
                return; // superseded; drop this tile's result
            }
            Graphics2D g = myProgressiveImage.createGraphics();
            g.drawImage(tileFinal, finalX, finalY, null);
            g.dispose();
            completedTiles++;
            reportStatus(true, totalTiles == 0 ? 1.0 : (double) completedTiles / totalTiles, -1);
            repaint(finalX, finalY, tileFinal.getWidth(), tileFinal.getHeight());
        });
    }

    private void reportStatus(boolean rendering, double progress, long lastRenderMillis) {
        renderStatusListener.accept(new RenderStatus(rendering, progress, lastRenderMillis));
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
        double tx = w / 2.0 * (1 - s) + (imageViewport.center().real() - live.center().real()) * live.zoom();
        double ty = h / 2.0 * (1 - s) + (imageViewport.center().imaginary() - live.center().imaginary()) * live.zoom();

        Graphics2D layer = (Graphics2D) g.create();
        layer.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        layer.translate(tx, ty);
        layer.scale(s, s);
        layer.drawImage(image, 0, 0, null);
        layer.dispose();
    }
}

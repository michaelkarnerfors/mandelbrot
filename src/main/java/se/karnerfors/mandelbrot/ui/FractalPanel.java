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
import java.util.function.Consumer;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;

import se.karnerfors.mandelbrot.math.Complex;
import se.karnerfors.mandelbrot.math.Viewport;
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
 * the view always feels instant; a background render then catches up and
 * replaces it with accurate pixels once done, guarded by a generation
 * counter so a superseded render is simply discarded.
 */
public final class FractalPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final int MIN_DRAG_PX = 4;
    private static final int WHEEL_DEBOUNCE_MS = 120;
    private static final int RESIZE_DEBOUNCE_MS = 150;
    private static final double WHEEL_ZOOM_STEP = 1.3;

    private enum Mode { NONE, SELECT, PAN }

    private Viewport currentViewport;
    private Viewport committedViewport;
    private BufferedImage renderedImage;
    private IterationGrid lastGrid;

    private Palette palette = Palette.classicRedWhiteBlack();
    private RenderSettings renderSettings = RenderSettings.defaults();

    private volatile long renderGeneration = 0;
    private final Deque<Viewport> history = new ArrayDeque<>();

    private Mode mode = Mode.NONE;
    private int dragStartX, dragStartY;
    private Rectangle dragRect;
    private double panLiveDx, panLiveDy;

    private final Timer wheelRenderTimer;
    private final Timer resizeRenderTimer;

    private Consumer<Viewport> viewportListener = v -> { };
    private Consumer<Boolean> renderStateListener = r -> { };
    private Consumer<Complex> cursorMoveListener = c -> { };

    public FractalPanel(int initialWidth, int initialHeight) {
        setPreferredSize(new java.awt.Dimension(initialWidth, initialHeight));
        setBackground(Color.DARK_GRAY);
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));

        this.currentViewport = Viewport.classicFullView(initialWidth, initialHeight);

        this.wheelRenderTimer = new Timer(WHEEL_DEBOUNCE_MS, e -> submitRender());
        this.wheelRenderTimer.setRepeats(false);
        this.resizeRenderTimer = new Timer(RESIZE_DEBOUNCE_MS, e -> submitRender());
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

    // --- public API -----------------------------------------------------

    public Viewport getCurrentViewport() {
        return currentViewport;
    }

    public boolean hasHistory() {
        return !history.isEmpty();
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

    /** Recolors the current render instantly from cached iteration data -- no recompute. */
    public void setPalette(Palette newPalette) {
        this.palette = newPalette;
        if (lastGrid != null) {
            renderedImage = Colorizer.colorize(lastGrid, palette);
            repaint();
        }
    }

    public void setRenderSettings(RenderSettings newSettings) {
        this.renderSettings = newSettings;
        submitRender();
    }

    public void setViewportListener(Consumer<Viewport> listener) {
        this.viewportListener = listener != null ? listener : v -> { };
    }

    public void setRenderStateListener(Consumer<Boolean> listener) {
        this.renderStateListener = listener != null ? listener : r -> { };
    }

    public void setCursorMoveListener(Consumer<Complex> listener) {
        this.cursorMoveListener = listener != null ? listener : c -> { };
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
        final long myGeneration = ++renderGeneration;

        renderStateListener.accept(true);

        SwingWorker<BufferedImage, Void> worker = new SwingWorker<>() {
            private IterationGrid grid;

            @Override
            protected BufferedImage doInBackground() {
                grid = Renderer.computeGrid(target, settings);
                return Colorizer.colorize(grid, activePalette);
            }

            @Override
            protected void done() {
                if (myGeneration != renderGeneration) {
                    return; // superseded by a newer interaction -- discard
                }
                try {
                    renderedImage = get();
                    lastGrid = grid;
                    committedViewport = target;
                } catch (Exception ex) {
                    ex.printStackTrace();
                } finally {
                    renderStateListener.accept(false);
                    repaint();
                }
            }
        };
        worker.execute();
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

        if (renderedImage != null && committedViewport != null) {
            Viewport live = effectiveLiveViewport();
            double s = live.zoom() / committedViewport.zoom();
            double w = getWidth();
            double h = getHeight();
            double tx = w / 2.0 * (1 - s) + (committedViewport.center().real - live.center().real) * live.zoom();
            double ty = h / 2.0 * (1 - s) + (committedViewport.center().imaginary - live.center().imaginary) * live.zoom();

            Graphics2D imageGraphics = (Graphics2D) g.create();
            imageGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            imageGraphics.translate(tx, ty);
            imageGraphics.scale(s, s);
            imageGraphics.drawImage(renderedImage, 0, 0, null);
            imageGraphics.dispose();
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
}

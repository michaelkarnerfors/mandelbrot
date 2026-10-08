package se.karnerfors.mandelbrot.app;

import java.awt.BorderLayout;
import java.awt.datatransfer.StringSelection;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

import se.karnerfors.mandelbrot.math.Complex;
import se.karnerfors.mandelbrot.render.RenderSettings;
import se.karnerfors.mandelbrot.ui.FractalPanel;
import se.karnerfors.mandelbrot.ui.JuliaPane;
import se.karnerfors.mandelbrot.ui.MandelbrotPickerPane;

/**
 * The Julia explorer: the Mandelbrot set beside the Julia set, where pointing at the Mandelbrot set chooses c.
 * The main canvas is always the Julia canvas, so everything that already works on it (bookmarks, the render
 * queue, the session) keeps working; this adds a second, 1x Mandelbrot canvas next to it.
 *
 * <p>The picker follows the pointer ("Following") until you click, which locks c; clicking again moves
 * c and keeps it locked.
 */
final class JuliaSplit {

    /** What the explorer needs from the application. */
    interface Host {
        /** c has been chosen on the picker. */
        void cChosen(Complex c);

        /** The pointer is over the picker at this point (null when it left). */
        void pickerCursor(Complex point);

        /** The split view was switched on or off, by the explorer. */
        void activeChanged(boolean active);

        /** The constant the Julia canvas is using. */
        Complex currentC();
    }

    private static final int FOLLOW_INTERVAL_MS = 30;

    private enum Layout { SINGLE, SPLIT }

    private final JFrame owner;
    private final JPanel stage;
    private final FractalPanel julia;
    private final FractalPanel picker;
    private final JuliaPane juliaPane;
    private final MandelbrotPickerPane pickerPane;
    private final Host host;
    private final Timer followTimer;

    private Layout layout = Layout.SINGLE;
    private boolean locked = true;
    private boolean dragging;
    private Complex pending;
    private JSplitPane split;
    private Complex rememberedCentre;
    private double rememberedZoom;
    private Double lastMandelbrotZoom;
    private boolean pickerPlaced;

    JuliaSplit(JFrame owner, JPanel stage, FractalPanel julia, Host host) {
        this.owner = owner;
        this.stage = stage;
        this.julia = julia;
        this.host = host;
        this.picker = new FractalPanel(500, 500);
        // The picker is only for choosing: one sample per pixel keeps it quick, and it shares the palette.
        picker.setRenderSettings(RenderSettings.defaults().withSupersampleFactor(1));
        picker.setPalette(julia.getPalette());
        picker.setViewportListener(view -> {
            if (layout != Layout.SINGLE) {
                lastMandelbrotZoom = view.zoom();
            }
        });
        julia.setPaletteListener(picker::setPalette);
        picker.setPointTracker(new FractalPanel.PointTracker() {
            @Override
            public void moved(Complex point) {
                dragged(point);
            }

            @Override
            public void ended() {
                dragEnded();
            }

            @Override
            public void doubleClicked() {
                if (locked) {
                    setLocked(false);
                }
            }
        });
        picker.setCursorMoveListener(point -> {
            host.pickerCursor(point);
            if (point != null && !locked && !dragging && layout != Layout.SINGLE) {
                pending = point;
                if (!followTimer().isRunning()) {
                    followTimer().start();
                }
            }
        });
        this.followTimer = new Timer(FOLLOW_INTERVAL_MS, _ -> applyPending());
        this.followTimer.setRepeats(false);

        pickerPane = new MandelbrotPickerPane(picker);
        juliaPane = new JuliaPane();
        juliaPane.copy.addActionListener(_ -> copyC());
    }

    private Timer followTimer() {
        return followTimer;
    }

    /** The Mandelbrot view you were looking at when you switched to Julia: the picker opens there. */
    void rememberMandelbrotView(Complex centre, double zoom) {
        rememberedCentre = Complex.fromCartesian(centre.real, centre.imaginary);
        rememberedZoom = zoom;
        lastMandelbrotZoom = zoom;
    }

    /** The zoom last used on the Mandelbrot side (the view you left, or the picker since), or null if unknown. */
    Double lastMandelbrotZoom() {
        return lastMandelbrotZoom;
    }

    /** Restores what the previous run remembered. */
    void setLastMandelbrotZoom(Double zoom) {
        lastMandelbrotZoom = zoom;
    }

    boolean isActive() {
        return layout != Layout.SINGLE;
    }

    /** Shows or hides the Mandelbrot picker. Starts locked at the c in use, so nothing moves until you ask. */
    void setActive(boolean on) {
        if (on == isActive()) {
            return;
        }
        if (on) {
            locked = true;
            showSplit();
            SwingUtilities.invokeLater(() -> {
                split.setDividerLocation(0.5);
                placePicker();
                julia.goHome();
            });
        } else {
            followTimer.stop();
            pending = null;
            stage.removeAll();
            stage.add(julia, BorderLayout.CENTER);
            layout = Layout.SINGLE;
            stage.revalidate();
            stage.repaint();
            SwingUtilities.invokeLater(julia::requestFocusInWindow);
        }
        refresh();
        host.activeChanged(on);
    }

    /** Locks c where it is (also what picking a preset or opening a bookmark does). */
    void lock() {
        if (isActive()) {
            setLocked(true);
        }
    }

    /** Re-reads c from the application: the crosshair, the value in the Julia pane, the lock state. */
    void refresh() {
        Complex c = host.currentC();
        juliaPane.setC(c.real, c.imaginary);
        picker.setMarker(isActive() ? c : null);
        juliaPane.setState(locked);
    }

    private void setLocked(boolean value) {
        locked = value;
        if (value) {
            followTimer.stop();
            pending = null;
        }
        refresh();
    }

    /**
     * Pressing or dragging on the picker puts c under the pointer and locks it. The Julia canvas redraws at 1x
     * at most every few milliseconds, and its refinement waits until the button is released.
     */
    private void dragged(Complex c) {
        boolean first = !dragging;
        locked = true;
        if (!dragging) {
            dragging = true;
            julia.setRefinementHeld(true);
        }
        if (first && pending == null && !followTimer.isRunning()) {
            host.cChosen(c); // the press itself shows its result at once
            refresh();
            return;
        }
        pending = c;
        if (!followTimer.isRunning()) {
            followTimer.start();
        }
    }

    private void dragEnded() {
        followTimer.stop();
        Complex c = pending;
        pending = null;
        if (c != null) {
            host.cChosen(c);
        }
        dragging = false;
        julia.setRefinementHeld(false);
        refresh();
    }

    private void applyPending() {
        Complex c = pending;
        pending = null;
        if (c != null && (dragging || !locked) && isActive()) {
            host.cChosen(c);
            refresh();
        }
    }

    private void copyC() {
        Complex c = host.currentC();
        String text = String.format(java.util.Locale.ROOT, "%s %s %si", Double.toString(c.real),
                c.imaginary < 0 ? "-" : "+", Double.toString(Math.abs(c.imaginary)));
        try {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        } catch (IllegalStateException | java.awt.HeadlessException ex) {
            // The clipboard is busy or missing; nothing sensible to do.
        }
    }

    // --- layouts --------------------------------------------------------------

    /**
     * Where the Mandelbrot pane looks when the split opens: where the Mandelbrot view was when you switched to
     * Julia, otherwise where the picker was last left; the whole set if c is not in sight there.
     */
    private void placePicker() {
        if (rememberedCentre != null) {
            picker.goTo(rememberedCentre, rememberedZoom);
            rememberedCentre = null;
            pickerPlaced = true;
            return;
        }
        Complex c = host.currentC();
        var view = picker.getCurrentViewport();
        double x = view.width() / 2.0 + (c.real - view.center().real) * view.zoom();
        double y = view.height() / 2.0 + (c.imaginary - view.center().imaginary) * view.zoom();
        if (!pickerPlaced || x < 0 || y < 0 || x > view.width() || y > view.height()) {
            picker.goHome();
        }
        pickerPlaced = true;
    }

    private void showSplit() {
        stage.removeAll();
        juliaPane.attach(julia);
        split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, pickerPane, juliaPane);
        split.setResizeWeight(0.5);
        split.setDividerSize(6);
        split.setContinuousLayout(true);
        split.setBorder(new EmptyBorder(0, 0, 0, 0));
        stage.add(split, BorderLayout.CENTER);
        layout = Layout.SPLIT;
        stage.revalidate();
        stage.repaint();
    }
}

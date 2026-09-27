package se.karnerfors.mandelbrot.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.border.EmptyBorder;

import se.karnerfors.mandelbrot.palette.Palette;

/**
 * A palette editor: add/remove/reorder color stops and adjust the cycle
 * length (how many shades are interpolated between each pair of stops
 * before the gradient repeats). Edits update the preview strip instantly
 * (cheap -- it's just a gradient sample), but the actual fractal only
 * recolors on "Apply" or "Done" -- recoloring a full, possibly heavily
 * supersampled render is not cheap, so doing it on every micro-edit is what
 * was freezing the UI. "Cancel" discards any edits (reverting the fractal
 * too, if "Apply" was used earlier in this session) back to whatever
 * palette was active when this editor was opened.
 */
public final class PaletteEditorPanel extends JPanel {

    private static final long serialVersionUID = 1L;
    private static final int MIN_STOPS = 2;
    private static final int MAX_SHADES_PER_SEGMENT = 200;
    private static final int SWATCH_WIDTH = 48;
    private static final int SWATCH_HEIGHT = 24;
    private static final int BUTTON_SIZE = 28;

    private final Palette originalPalette;
    private final List<Color> stops = new ArrayList<>();
    private int shadesPerSegment;
    private boolean appliedDuringSession = false;

    /** Pushes a palette to the live fractal; the Runnable, once called, signals the recolor has landed (or been dropped). */
    private final BiConsumer<Palette, Runnable> applyPalette;
    private final Runnable onClose;

    private final GradientPreview preview = new GradientPreview();
    private final JPanel stopsListPanel = new JPanel();

    public PaletteEditorPanel(Palette initialPalette, BiConsumer<Palette, Runnable> applyPalette, Runnable onClose) {
        this.originalPalette = initialPalette;
        this.applyPalette = applyPalette;
        this.onClose = onClose;
        Collections.addAll(stops, initialPalette.stops());
        this.shadesPerSegment = initialPalette.shadesPerSegment();

        setLayout(new BorderLayout(8, 8));
        setBorder(new EmptyBorder(8, 8, 8, 8));

        preview.setPreferredSize(new Dimension(320, 32));
        add(preview, BorderLayout.NORTH);

        stopsListPanel.setLayout(new BoxLayout(stopsListPanel, BoxLayout.Y_AXIS));
        add(stopsListPanel, BorderLayout.CENTER);

        JPanel southPanel = new JPanel();
        southPanel.setLayout(new BoxLayout(southPanel, BoxLayout.Y_AXIS));

        JPanel cycleRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        cycleRow.add(new JLabel("Shades per segment:"));
        JSpinner cycleLengthSpinner = new JSpinner(new SpinnerNumberModel(shadesPerSegment, 1, MAX_SHADES_PER_SEGMENT, 1));
        cycleLengthSpinner.addChangeListener(e -> {
            shadesPerSegment = (Integer) cycleLengthSpinner.getValue();
            updatePreview();
        });
        cycleRow.add(cycleLengthSpinner);
        southPanel.add(cycleRow);

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton cancelButton = new JButton("Cancel");
        JButton applyButton = new JButton("Apply");
        JButton doneButton = new JButton("Done");
        cancelButton.addActionListener(e -> cancel());
        applyButton.addActionListener(e -> apply(false));
        doneButton.addActionListener(e -> apply(true));
        buttonRow.add(cancelButton);
        buttonRow.add(applyButton);
        buttonRow.add(doneButton);
        southPanel.add(buttonRow);

        add(southPanel, BorderLayout.SOUTH);

        rebuildStopsList();
        preview.setPalette(initialPalette);
    }

    private void apply(boolean thenClose) {
        Palette palette = currentPalette();
        appliedDuringSession = true;
        if (thenClose) {
            // Closing anyway -- no need to disable-and-wait, the recolor finishes in the background regardless.
            applyPalette.accept(palette, null);
            onClose.run();
        } else {
            setEnabledRecursive(this, false);
            applyPalette.accept(palette, () -> setEnabledRecursive(this, true));
        }
    }

    private void cancel() {
        if (appliedDuringSession) {
            applyPalette.accept(originalPalette, null);
        }
        onClose.run();
    }

    private void addStop() {
        Color last = stops.get(stops.size() - 1);
        Color first = stops.get(0);
        Color midpoint = new Color((last.getRed() + first.getRed()) / 2,
                (last.getGreen() + first.getGreen()) / 2,
                (last.getBlue() + first.getBlue()) / 2);
        stops.add(midpoint);
        rebuildStopsList();
        updatePreview();
    }

    private void rebuildStopsList() {
        stopsListPanel.removeAll();
        for (int i = 0; i < stops.size(); i++) {
            stopsListPanel.add(buildStopRow(i));
        }
        stopsListPanel.add(buildAddRow());
        stopsListPanel.revalidate();
        stopsListPanel.repaint();

        java.awt.Window window = javax.swing.SwingUtilities.getWindowAncestor(this);
        if (window != null) {
            window.pack();
        }
    }

    private JPanel buildStopRow(int index) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));

        JButton swatch = new JButton();
        swatch.setPreferredSize(new Dimension(SWATCH_WIDTH, SWATCH_HEIGHT));
        swatch.setBackground(stops.get(index));
        swatch.setOpaque(true);
        swatch.setBorderPainted(false);
        swatch.addActionListener(e -> {
            Color chosen = JColorChooser.showDialog(this, "Choose stop color", stops.get(index));
            if (chosen != null) {
                stops.set(index, chosen);
                swatch.setBackground(chosen);
                updatePreview();
            }
        });
        row.add(swatch);

        JButton up = smallButton("↑");
        up.setEnabled(index > 0);
        up.addActionListener(e -> {
            Collections.swap(stops, index, index - 1);
            rebuildStopsList();
            updatePreview();
        });
        row.add(up);

        JButton down = smallButton("↓");
        down.setEnabled(index < stops.size() - 1);
        down.addActionListener(e -> {
            Collections.swap(stops, index, index + 1);
            rebuildStopsList();
            updatePreview();
        });
        row.add(down);

        JButton minus = smallButton("-");
        minus.setEnabled(stops.size() > MIN_STOPS);
        minus.addActionListener(e -> {
            stops.remove(index);
            rebuildStopsList();
            updatePreview();
        });
        row.add(minus);

        return row;
    }

    private JPanel buildAddRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        row.add(Box.createRigidArea(new Dimension(SWATCH_WIDTH, SWATCH_HEIGHT)));
        row.add(Box.createRigidArea(new Dimension(BUTTON_SIZE, BUTTON_SIZE)));
        row.add(Box.createRigidArea(new Dimension(BUTTON_SIZE, BUTTON_SIZE)));
        JButton plus = smallButton("+");
        plus.addActionListener(e -> addStop());
        row.add(plus);
        return row;
    }

    private JButton smallButton(String text) {
        JButton button = new JButton(text);
        button.setPreferredSize(new Dimension(BUTTON_SIZE, BUTTON_SIZE));
        button.setMargin(new Insets(0, 0, 0, 0));
        return button;
    }

    private Palette currentPalette() {
        return new Palette(stops.toArray(new Color[0]), shadesPerSegment);
    }

    private void updatePreview() {
        preview.setPalette(currentPalette());
    }

    private static void setEnabledRecursive(Component component, boolean enabled) {
        component.setEnabled(enabled);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                setEnabledRecursive(child, enabled);
            }
        }
    }

    /** Paints one full cycle of a palette's precomputed gradient as a horizontal strip. */
    private static final class GradientPreview extends JPanel {
        private static final long serialVersionUID = 1L;
        private Color[] gradient = { Color.GRAY };

        void setPalette(Palette palette) {
            this.gradient = palette.gradient();
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            int width = getWidth();
            int height = getHeight();
            if (width <= 0 || gradient.length == 0) {
                return;
            }
            for (int x = 0; x < width; x++) {
                int index = (int) ((long) x * gradient.length / width);
                g.setColor(gradient[Math.min(index, gradient.length - 1)]);
                g.drawLine(x, 0, x, height);
            }
        }
    }
}

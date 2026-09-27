package se.karnerfors.mandelbrot.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

import se.karnerfors.mandelbrot.palette.Palette;

/**
 * A live palette editor: add/remove/reorder color stops, adjust the cycle
 * length (how many shades are interpolated between each pair of stops before
 * the gradient repeats), and see both a preview strip and -- via {@code
 * onChange} -- the actual fractal update instantly on every edit. There is
 * no "Apply" button; every change is live.
 */
public final class PaletteEditorPanel extends JPanel {

    private static final long serialVersionUID = 1L;
    private static final int MIN_STOPS = 2;
    private static final int MAX_SHADES_PER_SEGMENT = 200;
    private static final int SWATCH_WIDTH = 48;
    private static final int SWATCH_HEIGHT = 24;

    private final List<Color> stops = new ArrayList<>();
    private int shadesPerSegment;
    private final Consumer<Palette> onChange;

    private final GradientPreview preview = new GradientPreview();
    private final JPanel stopsListPanel = new JPanel();
    private final JSpinner cycleLengthSpinner;

    public PaletteEditorPanel(Palette initialPalette, Consumer<Palette> onChange) {
        this.onChange = onChange;
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
        cycleLengthSpinner = new JSpinner(new SpinnerNumberModel(shadesPerSegment, 1, MAX_SHADES_PER_SEGMENT, 1));
        cycleLengthSpinner.addChangeListener(e -> {
            shadesPerSegment = (Integer) cycleLengthSpinner.getValue();
            firePaletteChanged();
        });
        cycleRow.add(cycleLengthSpinner);
        southPanel.add(cycleRow);

        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton addButton = new JButton("Add stop");
        addButton.addActionListener(e -> addStop());
        actionRow.add(addButton);

        JButton resetButton = new JButton("Reset to classic");
        resetButton.addActionListener(e -> resetToClassic());
        actionRow.add(resetButton);
        southPanel.add(actionRow);

        add(southPanel, BorderLayout.SOUTH);

        rebuildStopsList();
        preview.setPalette(initialPalette);
    }

    private void addStop() {
        Color last = stops.get(stops.size() - 1);
        Color first = stops.get(0);
        Color midpoint = new Color((last.getRed() + first.getRed()) / 2,
                (last.getGreen() + first.getGreen()) / 2,
                (last.getBlue() + first.getBlue()) / 2);
        stops.add(midpoint);
        rebuildStopsList();
        firePaletteChanged();
    }

    private void resetToClassic() {
        Palette classic = Palette.classicRedWhiteBlack();
        stops.clear();
        Collections.addAll(stops, classic.stops());
        shadesPerSegment = classic.shadesPerSegment();
        cycleLengthSpinner.setValue(shadesPerSegment);
        rebuildStopsList();
        firePaletteChanged();
    }

    private void rebuildStopsList() {
        stopsListPanel.removeAll();
        for (int i = 0; i < stops.size(); i++) {
            stopsListPanel.add(buildRow(i));
        }
        stopsListPanel.revalidate();
        stopsListPanel.repaint();

        Window window = SwingUtilities.getWindowAncestor(this);
        if (window != null) {
            window.pack();
        }
    }

    private JPanel buildRow(int index) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row.add(new JLabel("Stop " + (index + 1) + ":"));

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
                firePaletteChanged();
            }
        });
        row.add(swatch);

        JButton up = new JButton("↑");
        up.setEnabled(index > 0);
        up.addActionListener(e -> {
            Collections.swap(stops, index, index - 1);
            rebuildStopsList();
            firePaletteChanged();
        });
        row.add(up);

        JButton down = new JButton("↓");
        down.setEnabled(index < stops.size() - 1);
        down.addActionListener(e -> {
            Collections.swap(stops, index, index + 1);
            rebuildStopsList();
            firePaletteChanged();
        });
        row.add(down);

        JButton remove = new JButton("Remove");
        remove.setEnabled(stops.size() > MIN_STOPS);
        remove.addActionListener(e -> {
            stops.remove(index);
            rebuildStopsList();
            firePaletteChanged();
        });
        row.add(remove);

        return row;
    }

    private void firePaletteChanged() {
        Palette palette = new Palette(stops.toArray(new Color[0]), shadesPerSegment);
        preview.setPalette(palette);
        onChange.accept(palette);
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

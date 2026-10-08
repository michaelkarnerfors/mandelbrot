package se.karnerfors.mandelbrot.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;

import se.karnerfors.mandelbrot.io.PaletteLibrary;
import se.karnerfors.mandelbrot.palette.Palette;
import se.karnerfors.mandelbrot.ui.Theme.Token;

/**
 * The palette editor, shown in a non-modal window next to the fractal. Every edit recolours the view
 * straight away (one sample per pixel, so it keeps up with you), but nothing is kept until
 * <b>Apply</b>: closing the window, or <b>Revert</b>, puts back the palette that was last applied
 * (or the one that was active when the editor opened). <b>Save as preset…</b> stores the palette in
 * the palette library: type a name and press <b>Create</b> (or <b>Overwrite</b> when one of your palettes
 * already has that name; built-in names are refused). Saving also applies the palette.
 */
public final class PaletteEditorPanel extends JPanel {

    /** What the editor needs from the application. */
    public interface Host {
        /** Shows {@code palette} on the fractal right now, without keeping it. */
        void preview(Palette palette);

        /** Keeps {@code palette}: full-quality recolour. */
        void apply(Palette palette);

        /** Goes back to {@code palette} (the last applied one) at full quality. */
        void revert(Palette palette);

        /** What saving under {@code name} would do: create, overwrite one of yours, or nothing (built-in or invalid). */
        PaletteLibrary.NameStatus nameStatus(String name);

        /** Stores {@code palette} under {@code name} (creating or overwriting) and applies it; returns the name stored. */
        Optional<String> saveAs(String name, Palette palette);
    }

    private static final long serialVersionUID = 1L;
    private static final int MIN_STOPS = 2;
    private static final int MAX_STOPS = 32;
    private static final int MAX_SHADES = 200;
    private static final int ROW_HEIGHT = 34;
    private static final int VISIBLE_ROWS = 6;

    private final transient Host host;
    private final List<Color> stops = new ArrayList<>();
    private int shades;
    private transient Palette baseline;

    private final StripPreview strip = new StripPreview();
    private final JPanel rows = new JPanel();
    private final JScrollPane rowsScroll;
    private final ThemedLabel status = new ThemedLabel("", Token.MUTED, () -> Theme.sansMedium(12f));
    private final ThemedLabel shadesValue = new ThemedLabel("", Token.TEXT, () -> Theme.monoMedium(13f));
    private final AppButton apply = new AppButton("Apply", null, AppButton.Style.PRIMARY, false);
    private final AppButton revert = new AppButton("Revert", null, AppButton.Style.OUTLINE, true);
    private final AppButton addStop = new AppButton("Add stop", Icons.Glyph.PLUS, AppButton.Style.OUTLINE, true);
    private final AppButton savePreset = new AppButton("Create", null, AppButton.Style.OUTLINE, true);
    private final JTextField nameField = new JTextField(20);

    /** @param initialName the name of your palette being edited, or null when starting from a built-in or unsaved one */
    public PaletteEditorPanel(Palette initial, String initialName, Host host) {
        this.host = host;
        this.baseline = initial;
        Collections.addAll(stops, initial.stops());
        this.shades = initial.shadesPerSegment();

        setLayout(new BorderLayout(0, 0));
        setBorder(new EmptyBorder(12, 16, 0, 16));
        setBackground(Theme.color(Token.SURFACE));

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        strip.setAlignmentX(Component.LEFT_ALIGNMENT);
        status.setAlignmentX(Component.LEFT_ALIGNMENT);
        status.setBorder(new EmptyBorder(6, 0, 8, 0));
        top.add(strip);
        top.add(status);
        add(top, BorderLayout.NORTH);

        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setOpaque(false);
        rowsScroll = new JScrollPane(rows, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        rowsScroll.setBorder(null);
        rowsScroll.setOpaque(false);
        rowsScroll.getViewport().setOpaque(false);
        rowsScroll.getVerticalScrollBar().setUnitIncrement(ROW_HEIGHT);
        add(rowsScroll, BorderLayout.CENTER);

        JPanel south = new JPanel();
        south.setOpaque(false);
        south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
        south.add(Box.createVerticalStrut(8));
        south.add(shadesRow());
        south.add(Box.createVerticalStrut(10));
        south.add(divider());
        south.add(Box.createVerticalStrut(10));
        south.add(nameRow());
        south.add(Box.createVerticalStrut(10));
        south.add(buttonRow());
        add(south, BorderLayout.SOUTH);

        addStop.addActionListener(_ -> addStop());
        revert.addActionListener(_ -> revertEdits());
        apply.addActionListener(_ -> applyEdits());
        savePreset.addActionListener(_ -> saveUnderName());
        nameField.setText(initialName == null ? "" : initialName);
        nameField.putClientProperty("JTextField.placeholderText", "Name this palette to keep it");
        nameField.getAccessibleContext().setAccessibleName("Palette name");
        nameField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                updateSaveButton();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                updateSaveButton();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                updateSaveButton();
            }
        });
        nameField.addActionListener(_ -> saveUnderName());

        rebuildRows();
        refresh(false);
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        return new Dimension(348, d.height);
    }

    /** True when the edited palette differs from the last applied one. */
    public boolean isDirty() {
        return !same(currentPalette(), baseline);
    }

    /** The palette to restore if the editor is closed without applying. */
    public Palette baseline() {
        return baseline;
    }

    // --- layout pieces ---------------------------------------------------

    private JComponent shadesRow() {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(addStop, BorderLayout.WEST);

        JPanel stepper = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        stepper.setOpaque(false);
        stepper.add(new ThemedLabel("Shades per segment", Token.MUTED, () -> Theme.sans(12f)));
        AppButton fewer = AppButton.iconButton(Icons.Glyph.MINUS, "Fewer shades", AppButton.Style.OUTLINE, true);
        AppButton more = AppButton.iconButton(Icons.Glyph.PLUS, "More shades", AppButton.Style.OUTLINE, true);
        fewer.addActionListener(_ -> setShades(shades - (shades > 16 ? 4 : 1)));
        more.addActionListener(_ -> setShades(shades + (shades >= 16 ? 4 : 1)));
        shadesValue.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        shadesValue.setPreferredSize(new Dimension(30, 26));
        stepper.add(fewer);
        stepper.add(shadesValue);
        stepper.add(more);
        row.add(stepper, BorderLayout.EAST);
        return row;
    }

    private static JComponent divider() {
        JComponent line = new JComponent() {
            private static final long serialVersionUID = 1L;

            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Theme.color(Token.BORDER_SOFT));
                g.fillRect(0, 0, getWidth(), 1);
            }
        };
        line.setPreferredSize(new Dimension(316, 1));
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        line.setAlignmentX(Component.LEFT_ALIGNMENT);
        return line;
    }

    private JComponent nameRow() {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(new ThemedLabel("Name", Token.MUTED, () -> Theme.sans(12f)), BorderLayout.WEST);
        row.add(nameField, BorderLayout.CENTER);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        return row;
    }

    private JComponent buttonRow() {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setBorder(new EmptyBorder(0, 0, 12, 0));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.setOpaque(false);
        left.add(revert);
        left.add(savePreset);
        row.add(left, BorderLayout.WEST);
        row.add(apply, BorderLayout.EAST);
        return row;
    }

    private void rebuildRows() {
        rows.removeAll();
        for (int i = 0; i < stops.size(); i++) {
            rows.add(new StopRow(i));
        }
        rows.revalidate();
        rows.repaint();
        int visible = Math.min(stops.size(), VISIBLE_ROWS);
        rowsScroll.setPreferredSize(new Dimension(316, visible * ROW_HEIGHT + 2));
        rowsScroll.revalidate();
        java.awt.Window window = javax.swing.SwingUtilities.getWindowAncestor(this);
        if (window != null) {
            window.pack();
        }
    }

    // --- edits -----------------------------------------------------------

    private Palette currentPalette() {
        return new Palette(stops.toArray(new Color[0]), shades);
    }

    private void addStop() {
        if (stops.size() >= MAX_STOPS) {
            return;
        }
        Color last = stops.get(stops.size() - 1);
        Color first = stops.get(0);
        stops.add(new Color((last.getRed() + first.getRed()) / 2, (last.getGreen() + first.getGreen()) / 2,
                (last.getBlue() + first.getBlue()) / 2));
        rebuildRows();
        refresh(true);
        javax.swing.SwingUtilities.invokeLater(() -> rowsScroll.getVerticalScrollBar()
                .setValue(rowsScroll.getVerticalScrollBar().getMaximum()));
    }

    private void setShades(int value) {
        int clamped = Math.max(1, Math.min(MAX_SHADES, value));
        if (clamped != shades) {
            shades = clamped;
            refresh(true);
        }
    }

    private void applyEdits() {
        Palette palette = currentPalette();
        baseline = palette;
        host.apply(palette);
        refresh(false);
    }

    /** Create / Overwrite / disabled, depending on what the typed name would do. */
    private void updateSaveButton() {
        PaletteLibrary.NameStatus status = host.nameStatus(nameField.getText());
        switch (status) {
            case FREE -> {
                savePreset.setLabel("Create");
                savePreset.setEnabled(true);
                savePreset.setToolTipText("Keep this palette under this name and apply it");
            }
            case EXISTING -> {
                savePreset.setLabel("Overwrite");
                savePreset.setEnabled(true);
                savePreset.setToolTipText("Replace your palette with this name and apply this one");
            }
            case BUILT_IN -> {
                savePreset.setLabel("Create");
                savePreset.setEnabled(false);
                savePreset.setToolTipText("That name belongs to a built-in palette");
            }
            default -> {
                savePreset.setLabel("Create");
                savePreset.setEnabled(false);
                savePreset.setToolTipText("Type a name (up to " + PaletteLibrary.MAX_NAME_LENGTH + " characters) first");
            }
        }
    }

    private void saveUnderName() {
        if (!savePreset.isEnabled()) {
            return;
        }
        Palette palette = currentPalette();
        host.saveAs(nameField.getText(), palette).ifPresent(stored -> {
            baseline = palette;
            nameField.setText(stored);
            refresh(false);
        });
    }

    private void revertEdits() {
        stops.clear();
        Collections.addAll(stops, baseline.stops());
        shades = baseline.shadesPerSegment();
        rebuildRows();
        host.revert(baseline);
        refresh(false);
    }

    /** Updates the strip, status and buttons after an edit; {@code preview} also pushes the palette to the fractal. */
    private void refresh(boolean preview) {
        Palette palette = currentPalette();
        strip.set(palette, stops.size());
        shadesValue.setText(Integer.toString(shades));
        boolean dirty = !same(palette, baseline);
        apply.setEnabled(dirty);
        revert.setEnabled(dirty);
        addStop.setEnabled(stops.size() < MAX_STOPS);
        updateSaveButton();
        status.setText(dirty ? "Unsaved · Apply keeps it, closing reverts" : "No changes");
        if (preview) {
            host.preview(palette);
        }
        repaint();
    }

    private static boolean same(Palette a, Palette b) {
        return a.shadesPerSegment() == b.shadesPerSegment() && Arrays.equals(a.stops(), b.stops());
    }

    static Optional<Color> parseHex(String text) {
        String t = text.trim();
        if (t.startsWith("#")) {
            t = t.substring(1);
        }
        if (t.length() == 3) {
            t = "" + t.charAt(0) + t.charAt(0) + t.charAt(1) + t.charAt(1) + t.charAt(2) + t.charAt(2);
        }
        if (t.length() != 6) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Color(Integer.parseInt(t, 16)));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    static String hex(Color c) {
        return String.format(Locale.ROOT, "#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    // --- stop row --------------------------------------------------------

    private final class StopRow extends JPanel {
        private static final long serialVersionUID = 1L;

        StopRow(int index) {
            setLayout(new FlowLayout(FlowLayout.LEFT, 8, 0));
            setOpaque(false);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_HEIGHT));
            setPreferredSize(new Dimension(316, ROW_HEIGHT));

            ColourButton swatch = new ColourButton(stops.get(index));
            swatch.setToolTipText("Edit colour " + (index + 1));
            swatch.getAccessibleContext().setAccessibleName("Edit colour " + (index + 1));
            add(swatch);

            JTextField field = new JTextField(hex(stops.get(index)), 7);
            field.setFont(Theme.mono(13f));
            field.getAccessibleContext().setAccessibleName("Hex colour " + (index + 1));
            Runnable commit = () -> {
                Optional<Color> parsed = parseHex(field.getText());
                if (parsed.isPresent() && !parsed.get().equals(stops.get(index))) {
                    stops.set(index, parsed.get());
                    swatch.set(parsed.get());
                    refresh(true);
                }
                field.setText(hex(stops.get(index)));
            };
            field.addActionListener(_ -> commit.run());
            field.addFocusListener(new FocusAdapter() {
                @Override
                public void focusLost(FocusEvent e) {
                    commit.run();
                }
            });
            add(field);

            swatch.addActionListener(_ -> {
                Color chosen = JColorChooser.showDialog(this, "Colour " + (index + 1), stops.get(index));
                if (chosen != null) {
                    stops.set(index, chosen);
                    swatch.set(chosen);
                    field.setText(hex(chosen));
                    refresh(true);
                }
            });

            AppButton up = AppButton.iconButton(Icons.Glyph.CHEVRON_UP, "Move up", AppButton.Style.GHOST, true);
            up.setEnabled(index > 0);
            up.addActionListener(_ -> moveStop(index, -1));
            AppButton down = AppButton.iconButton(Icons.Glyph.CHEVRON_DOWN, "Move down", AppButton.Style.GHOST, true);
            down.setEnabled(index < stops.size() - 1);
            down.addActionListener(_ -> moveStop(index, 1));
            AppButton remove = AppButton.iconButton(Icons.Glyph.MINUS, "Remove stop " + (index + 1), AppButton.Style.GHOST, true);
            remove.setEnabled(stops.size() > MIN_STOPS);
            remove.addActionListener(_ -> {
                stops.remove(index);
                rebuildRows();
                refresh(true);
            });
            add(up);
            add(down);
            add(remove);
        }

        private void moveStop(int index, int delta) {
            Collections.swap(stops, index, index + delta);
            rebuildRows();
            refresh(true);
        }
    }

    /** A colour swatch that acts as a button. */
    private static final class ColourButton extends JButton {
        private static final long serialVersionUID = 1L;
        private Color colour;

        ColourButton(Color colour) {
            this.colour = colour;
            setPreferredSize(new Dimension(28, 22));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
        }

        void set(Color value) {
            colour = value;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(colour);
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 8, 8));
                g2.setColor(isFocusOwner() ? Theme.color(Token.ACCENT) : Theme.color(Token.BORDER));
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1, getHeight() - 1, 7, 7));
            } finally {
                g2.dispose();
            }
        }
    }

    /** The gradient for one cycle, with a marker under each stop. */
    private static final class StripPreview extends JComponent {
        private static final long serialVersionUID = 1L;
        private static final int BAR = 32;
        private static final int MARKERS = 16;

        private transient Color[] gradient = { Color.GRAY };
        private transient Color[] stopColours = {};

        StripPreview() {
            setPreferredSize(new Dimension(316, BAR + MARKERS));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, BAR + MARKERS));
        }

        void set(Palette palette, int count) {
            gradient = palette.gradient();
            stopColours = palette.stops();
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth();
                g2.clip(new RoundRectangle2D.Float(0, 0, w, BAR, 10, 10));
                for (int x = 0; x < w; x++) {
                    g2.setColor(gradient[Math.min(gradient.length - 1, (int) ((long) x * gradient.length / w))]);
                    g2.fillRect(x, 0, 1, BAR);
                }
                g2.setClip(null);
                g2.setColor(Theme.withAlpha(Theme.color(Token.TEXT), 46));
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, BAR - 1, 9, 9));
                int n = stopColours.length;
                for (int i = 0; i < n; i++) {
                    int cx = (int) Math.round(6 + (w - 12) * (i / (double) n));
                    g2.setColor(stopColours[i]);
                    g2.fill(new RoundRectangle2D.Float(cx - 6, BAR + 3, 12, 12, 4, 4));
                    g2.setColor(Theme.color(Token.BORDER));
                    g2.draw(new RoundRectangle2D.Float(cx - 6 + 0.5f, BAR + 3 + 0.5f, 11, 11, 4, 4));
                }
            } finally {
                g2.dispose();
            }
        }
    }
}

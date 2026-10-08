package se.karnerfors.mandelbrot.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import se.karnerfors.mandelbrot.io.MandelbrotConfig;
import se.karnerfors.mandelbrot.io.SessionState.ExportData;
import se.karnerfors.mandelbrot.palette.Palette;
import se.karnerfors.mandelbrot.render.ImageExporter;
import se.karnerfors.mandelbrot.render.RenderAlgorithm;
import se.karnerfors.mandelbrot.render.RenderQueue;
import se.karnerfors.mandelbrot.ui.Theme.Token;

/**
 * "Add to render queue": picks the size, quality, place and file name for a picture, then hands it to the
 * queue. It does not block the window, so you can keep navigating while it is open; the view, palette and
 * settings are taken when it opens.
 *
 * <p>The picture frames the same area as the window does, drawn on a bigger canvas. Re, Im and Zoom are in the
 * window's own terms (the zoom shown in the status bar), so "Sync to current view" and a hand-typed place
 * mean the same thing as everywhere else in the app.
 */
public final class RenderDialog {

    /** What the dialog needs from the application. */
    public interface Host {
        /** The view on screen when the dialog was opened. */
        MandelbrotConfig view();

        Palette palette();

        String paletteLabel();

        /** The window's canvas size in pixels. */
        java.awt.Dimension windowSize();

        /** What the canvas looks like, for the picture at the top and the queue's thumbnail. */
        BufferedImage snapshot();

        /** The choices made last time, or null. */
        ExportData remembered();

        /** Seconds one sample took to draw when the view last settled, if known; for the time estimate. */
        OptionalDouble secondsPerSample();

        /** The quality the window settles at, offered as the starting point the first time. */
        int settleFactor();

        /** The folder pictures go to unless the user chooses another. */
        File defaultFolder();

        /** True if a job in the queue already writes to this file. */
        boolean queued(File file);
    }

    /** A picture to add to the queue. */
    public record Request(MandelbrotConfig view, String paletteLabel, double framingScale, int width, int height,
            int supersample, File file, BufferedImage thumbnail) {
    }

    private enum Aspect {
        WINDOW("Window"), R43("4:3"), R169("16:9"), R219("21:9");

        private final String label;

        Aspect(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private record Res(String label, int width, int height) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final int WIDTH = 480;
    private static final int MAX_SIDE = 16384;
    private static final Integer[] FACTORS = { 1, 2, 4, 9 };

    private RenderDialog() {
    }

    /** Opens the dialog under {@code invoker}, right-aligned with it. */
    public static JDialog show(java.awt.Window owner, Component invoker, Host host,
            java.util.function.Consumer<Request> onAdd) {
        Form form = new Form(owner, host, onAdd);
        form.dialog.pack();
        form.dialog.setResizable(false);
        if (invoker != null && invoker.isShowing()) {
            java.awt.Point p = invoker.getLocationOnScreen();
            int x = p.x + invoker.getWidth() - form.dialog.getWidth();
            int y = p.y + invoker.getHeight() + 6;
            java.awt.Rectangle screen = invoker.getGraphicsConfiguration().getBounds();
            x = Math.max(screen.x, Math.min(x, screen.x + screen.width - form.dialog.getWidth()));
            y = Math.max(screen.y, Math.min(y, screen.y + screen.height - form.dialog.getHeight()));
            form.dialog.setLocation(x, y);
        } else {
            form.dialog.setLocationRelativeTo(owner);
        }
        form.dialog.setVisible(true);
        javax.swing.SwingUtilities.invokeLater(form.addButton::requestFocusInWindow);
        return form.dialog;
    }

    private static final class Form {
        private final JDialog dialog;
        private final Host host;
        private final java.util.function.Consumer<Request> onAdd;
        private BufferedImage snapshot;
        private int windowW;
        private int windowH;
        private MandelbrotConfig view;
        private String paletteLabel;
        private Picture picture;
        private Strip paletteStrip;
        private final ThemedLabel paletteName = new ThemedLabel("", Token.MUTED, () -> Theme.sans(11.5f));

        private final SegmentedControl<Aspect> aspectControl;
        private final JComboBox<Res> resolutionCombo = new JComboBox<>();
        private final SegmentedControl<Integer> factorControl;
        private final ThemedLabel factorNote = new ThemedLabel("", Token.MUTED, () -> Theme.sans(11.5f));
        private final JTextField reField = new JTextField();
        private final JTextField imField = new JTextField();
        private final JTextField zoomField = new JTextField();
        private final JTextField folderField = new JTextField();
        private final JTextField nameField = new JTextField();
        private final ThemedLabel suggested = new ThemedLabel("Suggested", Token.MUTED, () -> Theme.sansMedium(11f));
        private final ThemedLabel estimate = new ThemedLabel(" ", Token.MUTED, () -> Theme.mono(11.5f));
        private final AppButton addButton = new AppButton("Add to queue", Icons.Glyph.PLUS, AppButton.Style.PRIMARY, false);
        private boolean nameEdited;
        private boolean updating;
        private AppButton syncButton;
        private javax.swing.Timer syncPoll;

        Form(java.awt.Window owner, Host host, java.util.function.Consumer<Request> onAdd) {
            this.host = host;
            this.onAdd = onAdd;
            this.dialog = new JDialog(owner, "Add to render queue", java.awt.Dialog.ModalityType.MODELESS);
            dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
            takeCurrentView();

            ExportData saved = host.remembered();
            Aspect initialAspect = Aspect.WINDOW;
            if (saved != null) {
                initialAspect = aspectOf(saved.width(), saved.height());
            }
            aspectControl = new SegmentedControl<>(List.of(Aspect.values()), initialAspect, Aspect::toString);
            int initialFactor = saved != null ? saved.supersample() : host.settleFactor();
            factorControl = new SegmentedControl<>(List.of(FACTORS), initialFactor, f -> f + "x");

            JPanel content = new JPanel();
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            content.setBackground(Theme.color(Token.SURFACE));
            content.setBorder(new EmptyBorder(14, 16, 0, 16));

            content.add(header());
            content.add(gap(12));
            content.add(snapshotRow());
            content.add(gap(14));
            content.add(resolutionSection());
            content.add(gap(14));
            content.add(factorSection());
            content.add(gap(14));
            content.add(locationSection());
            content.add(gap(14));
            content.add(saveSection());
            content.add(gap(14));

            JPanel root = new JPanel(new BorderLayout());
            root.setBackground(Theme.color(Token.SURFACE));
            root.add(content, BorderLayout.CENTER);
            root.add(footer(), BorderLayout.SOUTH);
            root.setPreferredSize(new Dimension(WIDTH, root.getPreferredSize().height));
            dialog.setContentPane(root);
            dialog.getRootPane().setDefaultButton(addButton);
            dialog.getRootPane().registerKeyboardAction(_ -> dialog.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                    JComponent.WHEN_IN_FOCUSED_WINDOW);

            // initial values
            fillLocation();
            fillResolutions(initialAspect, saved);
            File folder = host.defaultFolder();
            if (saved != null && saved.outputFile() != null && !saved.outputFile().isBlank()) {
                File parent = new File(saved.outputFile()).getParentFile();
                if (parent != null) {
                    folder = parent;
                }
            }
            folderField.setText(displayFolder(folder));
            folderField.putClientProperty("folder", folder);
            wire();
            suggestName();
            refreshEstimate();

            // "Sync to current view" is only live when the window has moved on or the place was typed over.
            DocumentListener locationEdits = new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    refreshSync();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    refreshSync();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    refreshSync();
                }
            };
            for (JTextField f : new JTextField[] { reField, imField, zoomField }) {
                f.getDocument().addDocumentListener(locationEdits);
            }
            syncPoll = new javax.swing.Timer(400, _ -> refreshSync());
            syncPoll.start();
            dialog.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosed(java.awt.event.WindowEvent e) {
                    syncPoll.stop();
                }
            });
        }

        /** True when the location fields or the main window differ from what this dialog last took. */
        private boolean isDirty() {
            MandelbrotConfig.ViewportData v = view.viewport();
            if (!reField.getText().trim().equals(Double.toString(v.real()))
                    || !imField.getText().trim().equals(Double.toString(v.imaginary()))
                    || !zoomField.getText().trim().equals(Double.toString(v.zoom()))) {
                return true;
            }
            Dimension size = host.windowSize();
            return !host.view().equals(view) || size.width != windowW || size.height != windowH
                    || !host.paletteLabel().equals(paletteLabel);
        }

        private void refreshSync() {
            if (!updating && syncButton != null) {
                syncButton.setEnabled(isDirty());
            }
        }

        // --- sections -------------------------------------------------------------------------------

        private JComponent header() {
            JPanel row = new JPanel();
            row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
            row.setOpaque(false);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.add(new ThemedLabel("Add to render queue", Token.TEXT, () -> Theme.sansSemiBold(15f)));
            row.add(Box.createHorizontalStrut(8));
            row.add(new Pill("You can keep navigating"));
            row.add(Box.createHorizontalGlue());
            for (Component c : row.getComponents()) {
                ((JComponent) c).setAlignmentY(Component.CENTER_ALIGNMENT);
            }
            return row;
        }

        private JComponent snapshotRow() {
            JPanel row = new JPanel(new BorderLayout(12, 0));
            row.setOpaque(false);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 84));
            picture = new Picture(snapshot, 132, 84);
            row.add(picture, BorderLayout.WEST);

            JPanel text = new JPanel();
            text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
            text.setOpaque(false);
            ThemedLabel title = new ThemedLabel("Snapshot of the current view", Token.TEXT, () -> Theme.sansMedium(13f));
            ThemedLabel note = new ThemedLabel("<html>View, palette and settings are frozen when you add.</html>", Token.MUTED,
                    () -> Theme.sans(11.5f));
            JPanel palette = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            palette.setOpaque(false);
            paletteStrip = new Strip(host.palette().gradient(), 36, 10);
            paletteName.setText(paletteLabel);
            palette.add(paletteStrip);
            palette.add(paletteName);
            for (JComponent c : new JComponent[] { title, note, palette }) {
                c.setAlignmentX(Component.LEFT_ALIGNMENT);
                text.add(c);
            }
            palette.setBorder(new EmptyBorder(2, -6, 0, 0));
            JPanel centre = new JPanel(new java.awt.GridBagLayout());
            centre.setOpaque(false);
            java.awt.GridBagConstraints gc = new java.awt.GridBagConstraints();
            gc.anchor = java.awt.GridBagConstraints.LINE_START;
            gc.weightx = 1;
            centre.add(text, gc);
            row.add(centre, BorderLayout.CENTER);
            return row;
        }

        private JComponent resolutionSection() {
            JPanel box = section("Resolution");
            aspectControl.setAlignmentX(Component.LEFT_ALIGNMENT);
            box.add(aspectControl);
            box.add(gap(6));
            resolutionCombo.setAlignmentX(Component.LEFT_ALIGNMENT);
            resolutionCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
            box.add(resolutionCombo);
            return box;
        }

        private JComponent factorSection() {
            JPanel box = section("Settle at");
            JPanel row = new JPanel();
            row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
            row.setOpaque(false);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.add(factorControl);
            row.add(Box.createHorizontalStrut(10));
            row.add(factorNote);
            factorControl.setAlignmentY(Component.CENTER_ALIGNMENT);
            factorNote.setAlignmentY(Component.CENTER_ALIGNMENT);
            box.add(row);
            factorNote.setText(host.remembered() != null ? "Remembered from last render" : "Samples per pixel along each axis");
            return box;
        }

        private JComponent locationSection() {
            JPanel box = section(null);
            JPanel head = new JPanel();
            head.setLayout(new BoxLayout(head, BoxLayout.X_AXIS));
            head.setOpaque(false);
            head.setAlignmentX(Component.LEFT_ALIGNMENT);
            head.add(label("Location"));
            head.add(Box.createHorizontalGlue());
            syncButton = new AppButton("Sync to current view", null, AppButton.Style.GHOST, true);
            syncButton.setToolTipText("Take the view, palette and picture from the main window as they are now");
            syncButton.addActionListener(_ -> resetToCurrentView());
            syncButton.setEnabled(false);
            head.add(syncButton);
            box.add(head);
            box.add(gap(2));

            JPanel fields = new JPanel(new java.awt.GridLayout(2, 3, 8, 2));
            fields.setOpaque(false);
            fields.setAlignmentX(Component.LEFT_ALIGNMENT);
            for (JTextField f : new JTextField[] { reField, imField, zoomField }) {
                f.setFont(Theme.mono(12f));
                fields.add(f);
            }
            reField.getAccessibleContext().setAccessibleName("Re");
            imField.getAccessibleContext().setAccessibleName("Im");
            zoomField.getAccessibleContext().setAccessibleName("Zoom");
            for (String caption : new String[] { "Re", "Im", "Zoom" }) {
                fields.add(new ThemedLabel(caption, Token.MUTED, () -> Theme.sans(11.5f)));
            }
            box.add(fields);
            return box;
        }

        private JComponent saveSection() {
            JPanel box = section("Save to");
            JPanel folderRow = new JPanel(new BorderLayout(8, 0));
            folderRow.setOpaque(false);
            folderRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            folderField.setFont(Theme.mono(12f));
            folderField.setEditable(false);
            folderField.getAccessibleContext().setAccessibleName("Folder");
            AppButton browse = new AppButton("Browse…", null, AppButton.Style.OUTLINE, false);
            browse.addActionListener(_ -> chooseFolder());
            folderRow.add(folderField, BorderLayout.CENTER);
            folderRow.add(browse, BorderLayout.EAST);
            box.add(folderRow);
            box.add(gap(6));

            JPanel nameRow = new JPanel(new BorderLayout(8, 0));
            nameRow.setOpaque(false);
            nameRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            nameField.setFont(Theme.mono(12f));
            nameField.getAccessibleContext().setAccessibleName("File name");
            nameRow.add(nameField, BorderLayout.CENTER);
            nameRow.add(suggested, BorderLayout.EAST);
            box.add(nameRow);
            return box;
        }

        private JComponent footer() {
            JPanel footer = new JPanel();
            footer.setLayout(new BoxLayout(footer, BoxLayout.X_AXIS));
            footer.setBackground(Theme.color(Token.SURFACE));
            footer.setBorder(new javax.swing.border.CompoundBorder(
                    new javax.swing.border.MatteBorder(1, 0, 0, 0, Theme.color(Token.BORDER_SOFT)),
                    new EmptyBorder(12, 16, 14, 16)));
            footer.add(estimate);
            footer.add(Box.createHorizontalGlue());
            AppButton cancel = new AppButton("Cancel", null, AppButton.Style.OUTLINE, false);
            cancel.addActionListener(_ -> dialog.dispose());
            addButton.addActionListener(_ -> add());
            footer.add(cancel);
            footer.add(Box.createHorizontalStrut(8));
            footer.add(addButton);
            for (Component c : footer.getComponents()) {
                ((JComponent) c).setAlignmentY(Component.CENTER_ALIGNMENT);
            }
            return footer;
        }

        private static JPanel section(String title) {
            JPanel box = new JPanel();
            box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
            box.setOpaque(false);
            box.setAlignmentX(Component.LEFT_ALIGNMENT);
            if (title != null) {
                JComponent l = label(title);
                l.setAlignmentX(Component.LEFT_ALIGNMENT);
                box.add(l);
                box.add(gap(5));
            }
            return box;
        }

        private static JComponent label(String text) {
            return new ThemedLabel(text, Token.TEXT, () -> Theme.sansMedium(12.5f));
        }

        private static Component gap(int h) {
            Component c = Box.createVerticalStrut(h);
            ((JComponent) c).setAlignmentX(Component.LEFT_ALIGNMENT);
            return c;
        }

        // --- behaviour ------------------------------------------------------------------------------

        private void wire() {
            aspectControl.setChangeListener(aspect -> {
                fillResolutions(aspect, null);
                suggestName();
                refreshEstimate();
            });
            resolutionCombo.addActionListener(_ -> {
                if (!updating) {
                    suggestName();
                    refreshEstimate();
                }
            });
            factorControl.setChangeListener(_ -> {
                suggestName();
                refreshEstimate();
            });
            nameField.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    edited();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    edited();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    edited();
                }

                private void edited() {
                    if (!updating) {
                        nameEdited = true;
                        suggested.setVisible(false);
                    }
                }
            });
        }

        /** Reads the main window's view, palette, size and picture as they are right now. */
        private void takeCurrentView() {
            view = host.view();
            snapshot = host.snapshot();
            windowW = Math.max(1, host.windowSize().width);
            windowH = Math.max(1, host.windowSize().height);
            paletteLabel = host.paletteLabel();
        }

        /** "Reset to current view": the window may have moved on since the dialog opened, so refresh everything. */
        private void resetToCurrentView() {
            takeCurrentView();
            picture.setImage(snapshot);
            paletteStrip.setGradient(host.palette().gradient());
            paletteName.setText(paletteLabel);
            fillLocation();
            // The window-size choices depend on the window's size, and the file name on the fractal.
            Aspect aspect = aspectControl.getSelected();
            if (aspect == Aspect.WINDOW) {
                Res before = resolution();
                fillResolutions(aspect, null);
                if (before != null) {
                    for (int i = 0; i < resolutionCombo.getItemCount(); i++) {
                        String l = before.label();
                        String m = resolutionCombo.getItemAt(i).label();
                        if (l.substring(l.indexOf("   ") + 3).equals(m.substring(m.indexOf("   ") + 3))) {
                            resolutionCombo.setSelectedIndex(i);
                        }
                    }
                }
            }
            nameEdited = false;
            suggestName();
            refreshEstimate();
            refreshSync();
        }

        private void fillLocation() {
            MandelbrotConfig.ViewportData v = view.viewport();
            reField.setText(Double.toString(v.real()));
            imField.setText(Double.toString(v.imaginary()));
            zoomField.setText(Double.toString(v.zoom()));
        }

        private void fillResolutions(Aspect aspect, ExportData saved) {
            updating = true;
            try {
                resolutionCombo.removeAllItems();
                List<Res> list = new ArrayList<>();
                int preferred = 0;
                switch (aspect) {
                    case WINDOW -> {
                        list.add(new Res(windowW + " × " + windowH + "   window size", windowW, windowH));
                        list.add(new Res(windowW * 2 + " × " + windowH * 2 + "   window ×2", windowW * 2, windowH * 2));
                        list.add(new Res(windowW * 3 + " × " + windowH * 3 + "   window ×3", windowW * 3, windowH * 3));
                        list.add(new Res(windowW * 4 + " × " + windowH * 4 + "   window ×4", windowW * 4, windowH * 4));
                        preferred = 1;
                    }
                    case R43 -> {
                        list.add(new Res("1024 × 768", 1024, 768));
                        list.add(new Res("1600 × 1200", 1600, 1200));
                        list.add(new Res("2048 × 1536", 2048, 1536));
                        list.add(new Res("4096 × 3072", 4096, 3072));
                        preferred = 2;
                    }
                    case R169 -> {
                        list.add(new Res("1280 × 720   HD", 1280, 720));
                        list.add(new Res("1920 × 1080   Full HD", 1920, 1080));
                        list.add(new Res("2560 × 1440   QHD", 2560, 1440));
                        list.add(new Res("3840 × 2160   4K", 3840, 2160));
                        list.add(new Res("7680 × 4320   8K", 7680, 4320));
                        preferred = 3;
                    }
                    case R219 -> {
                        list.add(new Res("2560 × 1080   UW FHD", 2560, 1080));
                        list.add(new Res("3440 × 1440   UW QHD", 3440, 1440));
                        list.add(new Res("5120 × 2160   UW 5K", 5120, 2160));
                        list.add(new Res("7680 × 3240   UW 8K", 7680, 3240));
                        preferred = 1;
                    }
                }
                for (Res r : list) {
                    resolutionCombo.addItem(r);
                }
                if (saved != null) {
                    for (int i = 0; i < list.size(); i++) {
                        if (list.get(i).width() == saved.width() && list.get(i).height() == saved.height()) {
                            preferred = i;
                        }
                    }
                }
                resolutionCombo.setSelectedIndex(preferred);
            } finally {
                updating = false;
            }
        }

        private static Aspect aspectOf(int w, int h) {
            double ratio = w / (double) h;
            if (Math.abs(ratio - 4.0 / 3) < 0.01) {
                return Aspect.R43;
            }
            if (Math.abs(ratio - 16.0 / 9) < 0.01) {
                return Aspect.R169;
            }
            if (Math.abs(ratio - 21.0 / 9) < 0.06) {
                return Aspect.R219;
            }
            return Aspect.WINDOW;
        }

        private Res resolution() {
            return (Res) resolutionCombo.getSelectedItem();
        }

        private int factor() {
            return factorControl.getSelected();
        }

        private File folder() {
            Object stored = folderField.getClientProperty("folder");
            return stored instanceof File f ? f : host.defaultFolder();
        }

        private void chooseFolder() {
            JFileChooser chooser = new JFileChooser(folder());
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("Save pictures to");
            if (chooser.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) {
                File chosen = chooser.getSelectedFile();
                folderField.putClientProperty("folder", chosen);
                folderField.setText(displayFolder(chosen));
                suggestName();
            }
        }

        /** Proposes a file name from the fractal, size and quality, unless the user has typed their own. */
        private void suggestName() {
            if (nameEdited || resolution() == null) {
                return;
            }
            String kind = view.fractal().algorithm() == RenderAlgorithm.JULIA ? "julia" : "mandelbrot";
            String base = kind + "-" + resolution().width() + "x" + resolution().height() + "-" + factor() + "x";
            String name = base + ".png";
            int n = 2;
            while (new File(folder(), name).exists() || host.queued(new File(folder(), name))) {
                name = base + "-" + n++ + ".png";
            }
            updating = true;
            try {
                nameField.setText(name);
                suggested.setVisible(true);
            } finally {
                updating = false;
            }
        }

        private void refreshEstimate() {
            Res r = resolution();
            if (r == null) {
                return;
            }
            long pixels = (long) r.width() * r.height();
            StringBuilder text = new StringBuilder();
            OptionalDouble perSample = host.secondsPerSample();
            if (perSample.isPresent()) {
                double seconds = perSample.getAsDouble() * pixels * factor() * (double) factor();
                text.append("≈ ").append(RenderQueue.formatDuration((long) (seconds * 1000))).append(" · ");
            }
            text.append("about ").append(formatBytes((long) (pixels * 1.3))).append(" file");
            long heap = Runtime.getRuntime().maxMemory();
            long need = memoryNeeded(r.width(), r.height(), factor());
            boolean heavy = need > heap * 0.35;
            if (heavy) {
                text.append(" · heavy, about ").append(formatBytes(need)).append(" of memory");
            }
            estimate.setText(text.toString());
            estimate.setToolTipText(heavy ? String.format(Locale.ROOT,
                    "Java may use up to %s. If memory runs out, the job fails and offers a lighter retry.",
                    formatBytes(heap)) : null);
        }

        private void add() {
            Res r = resolution();
            Double re = number(reField, "Re");
            Double im = re == null ? null : number(imField, "Im");
            Double zoom = im == null ? null : number(zoomField, "Zoom");
            if (zoom == null) {
                return;
            }
            if (zoom <= 0) {
                warn("Zoom must be greater than zero.", "Invalid value");
                return;
            }
            if (r.width() > MAX_SIDE || r.height() > MAX_SIDE) {
                warn("Pictures can be at most " + MAX_SIDE + " pixels on a side.", "Too large");
                return;
            }
            String name = nameField.getText().trim();
            if (name.isEmpty() || name.matches(".*[\\\\/:*?\"<>|].*")) {
                warn("Give the file a name without any of \\ / : * ? \" < > |", "File name");
                return;
            }
            if (!name.toLowerCase(Locale.ROOT).endsWith(".png")) {
                name += ".png";
            }
            File file = new File(folder(), name);
            if (host.queued(file)) {
                warn(name + " is already in the queue. Choose another file name.", "Already queued");
                return;
            }
            if (file.exists() && JOptionPane.showConfirmDialog(dialog, name + " already exists. Replace it?",
                    "Replace file?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
                return;
            }
            MandelbrotConfig frozen = new MandelbrotConfig(MandelbrotConfig.CURRENT_VERSION,
                    new MandelbrotConfig.ViewportData(re, im, zoom), view.palette(), view.fractal());
            double scale = Math.min(r.width() / (double) windowW, r.height() / (double) windowH);
            BufferedImage thumb = se.karnerfors.mandelbrot.io.Thumbnails.cover(snapshot, 128, 84);
            Request request = new Request(frozen, host.paletteLabel(), scale, r.width(), r.height(), factor(), file, thumb);
            dialog.dispose();
            onAdd.accept(request);
        }

        private Double number(JTextField field, String label) {
            try {
                double value = Double.parseDouble(field.getText().trim());
                if (Double.isNaN(value) || Double.isInfinite(value)) {
                    throw new NumberFormatException();
                }
                return value;
            } catch (NumberFormatException ex) {
                warn(label + " must be a number.", "Invalid value");
                field.requestFocusInWindow();
                return null;
            }
        }

        private void warn(String message, String title) {
            JOptionPane.showMessageDialog(dialog, message, title, JOptionPane.WARNING_MESSAGE);
        }
    }

    /** Rough peak memory of drawing a picture: the final image (and the PNG encoder's copy) plus the tiles in flight. */
    static long memoryNeeded(int width, int height, int factor) {
        long image = (long) width * height * 4L * 2L;
        long tile = (long) ImageExporter.EXPORT_TILE_SIZE * factor;
        long perTile = tile * tile * 12L;
        return image + perTile * Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    static String formatBytes(long bytes) {
        double mb = bytes / (1024.0 * 1024.0);
        if (mb < 1000) {
            return String.format(Locale.ROOT, "%.0f MB", Math.max(1, mb));
        }
        return String.format(Locale.ROOT, "%.1f GB", mb / 1024.0);
    }

    static String displayFolder(File folder) {
        String path = folder.getAbsolutePath();
        String home = System.getProperty("user.home");
        return home != null && path.startsWith(home) ? "~" + path.substring(home.length()).replace('\\', '/') : path;
    }

    // --- small drawing components -------------------------------------------------------------------

    /** A rounded outline label such as "You can keep navigating". */
    private static final class Pill extends JComponent {
        private static final long serialVersionUID = 1L;
        private final String text;

        Pill(String text) {
            this.text = text;
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(getFontMetrics(Theme.sansMedium(11f)).stringWidth(text) + 16, 20);
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(Theme.color(Token.SURFACE_2));
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), getHeight(), getHeight()));
                g2.setFont(Theme.sansMedium(11f));
                g2.setColor(Theme.color(Token.MUTED));
                java.awt.FontMetrics fm = g2.getFontMetrics();
                g2.drawString(text, 8, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            } finally {
                g2.dispose();
            }
        }
    }

    /** A picture cropped to fill a rounded rectangle. */
    private static final class Picture extends JComponent {
        private static final long serialVersionUID = 1L;
        private transient BufferedImage image;
        private final int w;
        private final int h;

        Picture(BufferedImage image, int w, int h) {
            this.w = w;
            this.h = h;
            setPreferredSize(new Dimension(w, h));
            setImage(image);
        }

        void setImage(BufferedImage source) {
            this.image = source == null ? null : se.karnerfors.mandelbrot.io.Thumbnails.cover(source, w * 2, h * 2);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                RoundRectangle2D shape = new RoundRectangle2D.Float(0, 0, w, h, 10, 10);
                java.awt.Shape old = g2.getClip();
                g2.clip(shape);
                if (image != null) {
                    g2.drawImage(image, 0, 0, w, h, null);
                } else {
                    g2.setColor(Theme.color(Token.SURFACE_2));
                    g2.fillRect(0, 0, w, h);
                }
                g2.setClip(old);
                g2.setColor(Theme.withAlpha(Theme.color(Token.TEXT), 46));
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 10, 10));
            } finally {
                g2.dispose();
            }
        }
    }

    /** A thin strip showing a palette's colours. */
    static final class Strip extends JComponent {
        private static final long serialVersionUID = 1L;
        private transient Color[] gradient;
        private final int w;
        private final int h;

        void setGradient(Color[] colors) {
            this.gradient = colors;
            repaint();
        }

        Strip(Color[] gradient, int w, int h) {
            this.gradient = gradient;
            this.w = w;
            this.h = h;
            setPreferredSize(new Dimension(w, h));
            setMinimumSize(new Dimension(w, h));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.clip(new RoundRectangle2D.Float(0, 0, w, h, h, h));
                for (int x = 0; x < w; x++) {
                    g2.setColor(gradient[Math.min(gradient.length - 1, x * gradient.length / w)]);
                    g2.fillRect(x, 0, 1, h);
                }
            } finally {
                g2.dispose();
            }
        }
    }
}

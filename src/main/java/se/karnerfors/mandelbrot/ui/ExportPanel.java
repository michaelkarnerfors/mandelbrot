package se.karnerfors.mandelbrot.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;

import se.karnerfors.mandelbrot.io.MandelbrotConfig;
import se.karnerfors.mandelbrot.io.MandelbrotConfigIO;
import se.karnerfors.mandelbrot.io.SessionState.ExportData;
import se.karnerfors.mandelbrot.math.Complex;
import se.karnerfors.mandelbrot.math.Viewport;
import se.karnerfors.mandelbrot.palette.Palette;
import se.karnerfors.mandelbrot.render.ImageExporter;
import se.karnerfors.mandelbrot.render.RenderSettings;

/**
 * Renders the fractal to a PNG file at a resolution and position independent
 * of the live on-screen panel -- a "print to a bigger canvas" export, not a
 * screenshot. Resolution and supersample factor are picked here; Re/Im/Zoom
 * are prefilled from wherever the panel currently is, but editable, so this
 * doubles as a way to export a specific location typed in by hand. The
 * render itself runs on a background thread through {@link ImageExporter},
 * which tiles the work so memory stays bounded even at 8K with heavy
 * supersampling; the dialog stays responsive throughout, with a progress bar
 * and a Cancel that actually stops the in-flight render.
 */
public final class ExportPanel extends JPanel {

    private static final long serialVersionUID = 1L;
    private static final int[] SUPERSAMPLE_FACTORS = { 1, 2, 4, 9 };

    /** width x height, grouped by aspect ratio, widest common range down to 1024px wide, up to 8K. */
    private static final Resolution[] PRESET_RESOLUTIONS = {
            new Resolution("1024 × 768 (4:3)", 1024, 768),
            new Resolution("1280 × 960 (4:3)", 1280, 960),
            new Resolution("1600 × 1200 (4:3)", 1600, 1200),
            new Resolution("2048 × 1536 (4:3)", 2048, 1536),
            new Resolution("4096 × 3072 (4:3)", 4096, 3072),
            new Resolution("1280 × 720 (16:9, HD)", 1280, 720),
            new Resolution("1920 × 1080 (16:9, Full HD)", 1920, 1080),
            new Resolution("2560 × 1440 (16:9, QHD)", 2560, 1440),
            new Resolution("3840 × 2160 (16:9, 4K UHD)", 3840, 2160),
            new Resolution("7680 × 4320 (16:9, 8K UHD)", 7680, 4320),
            new Resolution("2560 × 1080 (21:9, UWFHD)", 2560, 1080),
            new Resolution("3440 × 1440 (21:9, UWQHD)", 3440, 1440),
            new Resolution("5120 × 2160 (21:9, UW5K)", 5120, 2160),
            new Resolution("7680 × 3240 (21:9, UW8K)", 7680, 3240),
    };

    private final RenderSettings baseSettings;
    private final Palette palette;
    private final Runnable onClose;
    private final Consumer<ExportData> onChoicesUsed;

    private final JComboBox<Resolution> resolutionCombo = new JComboBox<>();
    private final JRadioButton[] supersampleRadios = new JRadioButton[SUPERSAMPLE_FACTORS.length];
    private final JTextField reField = new JTextField(18);
    private final JTextField imField = new JTextField(18);
    private final JTextField zoomField = new JTextField(18);
    private final JTextField fileField = new JTextField(22);
    private final JButton browseButton = new JButton("Browse…");
    private final JButton renderButton = new JButton("Render");
    private final JButton cancelButton = new JButton("Cancel");
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JLabel statusLabel = new JLabel(" ");

    private volatile boolean cancelRequested = false;
    private volatile boolean rendering = false;

    /**
     * @param saved         the resolution, supersample factor and output file used last time, or null the first time
     * @param onChoicesUsed told what was chosen each time a render is started, so the app can remember it
     */
    public ExportPanel(int currentWidth, int currentHeight, Viewport currentViewport, RenderSettings currentSettings,
            Palette palette, ExportData saved, Consumer<ExportData> onChoicesUsed, Runnable onClose) {
        this.baseSettings = currentSettings;
        this.palette = palette;
        this.onClose = onClose;
        this.onChoicesUsed = onChoicesUsed;

        setLayout(new BorderLayout(0, 8));
        setBorder(new EmptyBorder(8, 8, 8, 8));

        resolutionCombo.addItem(new Resolution("Current (" + currentWidth + " × " + currentHeight + ")",
                currentWidth, currentHeight));
        for (Resolution resolution : PRESET_RESOLUTIONS) {
            resolutionCombo.addItem(resolution);
        }

        if (saved != null) {
            for (int i = 0; i < resolutionCombo.getItemCount(); i++) {
                Resolution candidate = resolutionCombo.getItemAt(i);
                if (candidate.width() == saved.width() && candidate.height() == saved.height()) {
                    resolutionCombo.setSelectedIndex(i);
                    break;
                }
            }
        }

        JPanel supersamplePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        ButtonGroup supersampleGroup = new ButtonGroup();
        int currentFactor = saved != null ? saved.supersample() : currentSettings.supersampleFactor();
        for (int i = 0; i < SUPERSAMPLE_FACTORS.length; i++) {
            int factor = SUPERSAMPLE_FACTORS[i];
            JRadioButton radio = new JRadioButton(factor + "x", factor == currentFactor);
            supersampleGroup.add(radio);
            supersamplePanel.add(radio);
            supersampleRadios[i] = radio;
        }

        Complex center = currentViewport.center();
        reField.setText(Double.toString(center.real));
        imField.setText(Double.toString(center.imaginary));
        zoomField.setText(Double.toString(currentViewport.zoom()));

        fileField.setEditable(false);
        if (saved != null && saved.outputFile() != null && !saved.outputFile().isBlank()) {
            fileField.setText(saved.outputFile());
        }
        browseButton.addActionListener(_ -> chooseFile());
        JPanel filePanel = new JPanel(new BorderLayout(4, 0));
        filePanel.add(fileField, BorderLayout.CENTER);
        filePanel.add(browseButton, BorderLayout.EAST);

        // Form: label column + field column. GridBagLayout sizes every row from the same two
        // columns, so rows can't wrap or overlap no matter how wide the dialog ends up.
        JPanel form = new JPanel(new GridBagLayout());
        int row = 0;
        addFormRow(form, row++, "Resolution:", resolutionCombo);
        addFormRow(form, row++, "Supersample:", supersamplePanel);
        addFormRow(form, row++, "Re:", reField);
        addFormRow(form, row++, "Im:", imField);
        addFormRow(form, row++, "Zoom:", zoomField);
        addFormRow(form, row++, "Output file:", filePanel);
        add(form, BorderLayout.CENTER);

        progressBar.setStringPainted(false);
        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.CENTER));
        cancelButton.addActionListener(_ -> onCancelClicked());
        renderButton.addActionListener(_ -> startRender());
        buttonRow.add(cancelButton);
        buttonRow.add(renderButton);

        JPanel footer = new JPanel(new BorderLayout(0, 4));
        footer.add(progressBar, BorderLayout.NORTH);
        footer.add(statusLabel, BorderLayout.CENTER);
        footer.add(buttonRow, BorderLayout.SOUTH);
        add(footer, BorderLayout.SOUTH);
    }

    private void onCancelClicked() {
        if (rendering) {
            cancelRequested = true;
            statusLabel.setText("Cancelling…");
        } else {
            onClose.run();
        }
    }

    private void chooseFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("PNG image", "png"));
        String remembered = fileField.getText().trim();
        if (remembered.isEmpty()) {
            chooser.setSelectedFile(new File("mandelbrot.png"));
        } else {
            // Open in the folder used last time, with that file name suggested.
            chooser.setSelectedFile(new File(remembered));
        }
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            fileField.setText(withPngExtension(chooser.getSelectedFile()).getAbsolutePath());
        }
    }

    private void startRender() {
        Resolution resolution = (Resolution) resolutionCombo.getSelectedItem();
        int factor = selectedSupersampleFactor();

        Double re = parseField(reField, "Re");
        if (re == null) {
            return;
        }
        Double im = parseField(imField, "Im");
        if (im == null) {
            return;
        }
        Double zoom = parseField(zoomField, "Zoom");
        if (zoom == null) {
            return;
        }
        String path = fileField.getText().trim();
        if (path.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Choose an output file first.", "No file selected", JOptionPane.WARNING_MESSAGE);
            return;
        }
        File outputFile = withPngExtension(new File(path));
        if (outputFile.exists() && JOptionPane.showConfirmDialog(this,
                outputFile.getName() + " already exists. Replace it?", "Replace file?",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        onChoicesUsed.accept(new ExportData(resolution.width(), resolution.height(), factor, outputFile.getAbsolutePath()));

        Viewport target = new Viewport(Complex.fromCartesian(re, im), zoom, resolution.width(), resolution.height());
        RenderSettings settings = baseSettings.withSupersampleFactor(factor);

        cancelRequested = false;
        rendering = true;
        setControlsEnabled(false);
        progressBar.setValue(0);
        statusLabel.setText("Rendering…");

        int totalTiles = Math.max(1, ImageExporter.tileCount(target, factor));
        AtomicInteger completed = new AtomicInteger();
        ExecutorService executor = newExportExecutor();

        SwingWorker<BufferedImage, Integer> worker = new SwingWorker<>() {
            @Override
            protected BufferedImage doInBackground() throws Exception {
                return ImageExporter.renderImage(target, settings, palette, executor, () -> cancelRequested, () -> {
                    int done = completed.incrementAndGet();
                    publish((int) Math.min(100, done * 100L / totalTiles));
                });
            }

            @Override
            protected void process(List<Integer> chunks) {
                if (!chunks.isEmpty()) {
                    progressBar.setValue(chunks.get(chunks.size() - 1));
                }
            }

            @Override
            protected void done() {
                rendering = false;
                executor.shutdown();
                if (cancelRequested) {
                    statusLabel.setText("Cancelled.");
                    progressBar.setValue(0);
                    setControlsEnabled(true);
                    return;
                }
                try {
                    BufferedImage image = get();
                    MandelbrotConfig config = MandelbrotConfig.capture(target, palette, settings);
                    MandelbrotConfigIO.writePng(outputFile, image, config);
                    progressBar.setValue(100);
                    statusLabel.setText("Saved to " + outputFile.getAbsolutePath());
                } catch (IOException | java.util.concurrent.ExecutionException | InterruptedException ex) {
                    statusLabel.setText("Failed: " + ex.getMessage());
                } finally {
                    setControlsEnabled(true);
                }
            }
        };
        worker.execute();
    }

    private int selectedSupersampleFactor() {
        for (int i = 0; i < supersampleRadios.length; i++) {
            if (supersampleRadios[i].isSelected()) {
                return SUPERSAMPLE_FACTORS[i];
            }
        }
        return 1;
    }

    private Double parseField(JTextField field, String label) {
        try {
            return Double.parseDouble(field.getText().trim());
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, label + " must be a number.", "Invalid value", JOptionPane.WARNING_MESSAGE);
            return null;
        }
    }

    private static File withPngExtension(File file) {
        if (file.getName().toLowerCase().endsWith(".png")) {
            return file;
        }
        return new File(file.getParentFile(), file.getName() + ".png");
    }

    private void setControlsEnabled(boolean enabled) {
        resolutionCombo.setEnabled(enabled);
        for (JRadioButton radio : supersampleRadios) {
            radio.setEnabled(enabled);
        }
        reField.setEnabled(enabled);
        imField.setEnabled(enabled);
        zoomField.setEnabled(enabled);
        browseButton.setEnabled(enabled);
        renderButton.setEnabled(enabled);
        // cancelButton stays enabled throughout: it closes the dialog when idle, cancels the render when not.
    }

    private static ExecutorService newExportExecutor() {
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "export-render-worker");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(threads, factory);
    }

    private static void addFormRow(JPanel form, int row, String label, Component field) {
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0;
        labelConstraints.gridy = row;
        labelConstraints.anchor = GridBagConstraints.LINE_START;
        labelConstraints.insets = new Insets(3, 0, 3, 8);
        form.add(new JLabel(label), labelConstraints);

        GridBagConstraints fieldConstraints = new GridBagConstraints();
        fieldConstraints.gridx = 1;
        fieldConstraints.gridy = row;
        fieldConstraints.weightx = 1.0;
        fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
        fieldConstraints.insets = new Insets(3, 0, 3, 0);
        form.add(field, fieldConstraints);
    }

    private record Resolution(String label, int width, int height) {
        @Override
        public String toString() {
            return label;
        }
    }
}

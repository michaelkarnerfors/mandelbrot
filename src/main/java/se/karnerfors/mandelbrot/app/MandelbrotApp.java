package se.karnerfors.mandelbrot.app;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

import se.karnerfors.mandelbrot.io.MandelbrotConfig;
import se.karnerfors.mandelbrot.io.MandelbrotConfigIO;
import se.karnerfors.mandelbrot.math.Complex;
import se.karnerfors.mandelbrot.render.JuliaPreset;
import se.karnerfors.mandelbrot.render.RenderAlgorithm;
import se.karnerfors.mandelbrot.render.RenderSettings;
import se.karnerfors.mandelbrot.ui.ExportPanel;
import se.karnerfors.mandelbrot.ui.FractalPanel;
import se.karnerfors.mandelbrot.ui.PaletteEditorPanel;
import se.karnerfors.mandelbrot.ui.PaletteSwatchIcon;
import se.karnerfors.mandelbrot.ui.RenderStatus;
import se.karnerfors.mandelbrot.ui.StatusBar;
import se.karnerfors.mandelbrot.ui.Theme;
import se.karnerfors.mandelbrot.ui.TopBar;

public class MandelbrotApp {

    /** {@code -Dmandelbrot.theme=dark} starts in the dark theme; the status bar switch changes it at runtime. */
    private static final String THEME_PROPERTY = "mandelbrot.theme";

    public static void main(String[] args) {
        SwingUtilities.invokeLater(MandelbrotApp::createAndShowGui);
    }

    private static void createAndShowGui() {
        Theme.install("dark".equalsIgnoreCase(System.getProperty(THEME_PROPERTY)) ? Theme.Mode.DARK : Theme.Mode.LIGHT);

        FractalPanel fractalPanel = new FractalPanel(1000, 700);
        RenderSettings initialSettings = fractalPanel.getRenderSettings();

        JFrame frame = new JFrame("Mandelbrot / Julia — " + fractalPanel.getWorkerThreadCount() + " render threads");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        TopBar topBar = new TopBar(initialSettings.algorithm(), initialSettings.supersampleFactor());
        StatusBar statusBar = new StatusBar();
        topBar.palette.setExtraIcon(new PaletteSwatchIcon(fractalPanel::getPalette));

        topBar.supersample.setChangeListener(factor ->
                fractalPanel.setRenderSettings(fractalPanel.getRenderSettings().withSupersampleFactor(factor)));

        // Julia constant: the Mandelbrot position the view was centred on, plus the famous presets.
        MandelbrotPosition mandelbrotPosition = new MandelbrotPosition(fractalPanel.getCurrentViewport().center());
        topBar.juliaC.addItem(mandelbrotPosition);
        for (JuliaPreset preset : JuliaPreset.values()) {
            topBar.juliaC.addItem(preset);
        }
        topBar.juliaC.setSelectedItem(JuliaPreset.DOUADY_RABBIT);
        topBar.juliaC.setPrototypeDisplayValue(new MandelbrotPosition(Complex.fromCartesian(-0.12345678, -0.12345678)));
        topBar.juliaC.setEnabled(initialSettings.algorithm() == RenderAlgorithm.JULIA);
        boolean[] updatingPresets = { false };

        topBar.fractal.setChangeListener(selected -> {
            RenderSettings current = fractalPanel.getRenderSettings();
            topBar.juliaC.setEnabled(selected == RenderAlgorithm.JULIA);
            if (selected == current.algorithm()) {
                return;
            }
            RenderSettings next = current.withAlgorithm(selected);
            if (selected == RenderAlgorithm.JULIA) {
                // Mandelbrot -> Julia: start from the point the Mandelbrot view is centred on.
                Complex centre = fractalPanel.getCurrentViewport().center();
                mandelbrotPosition.set(centre);
                updatingPresets[0] = true;
                topBar.juliaC.setSelectedItem(mandelbrotPosition);
                updatingPresets[0] = false;
                topBar.juliaC.repaint();
                next = next.withC(centre);
            }
            fractalPanel.setRenderSettingsAndGoHome(next);
        });
        topBar.juliaC.addActionListener(_ -> {
            if (updatingPresets[0]) {
                return;
            }
            Object selected = topBar.juliaC.getSelectedItem();
            if (selected instanceof JuliaPreset preset) {
                fractalPanel.setRenderSettingsAndGoHome(fractalPanel.getRenderSettings().withC(preset.c()));
            } else if (selected instanceof MandelbrotPosition position) {
                fractalPanel.setRenderSettingsAndGoHome(fractalPanel.getRenderSettings().withC(position.c()));
            }
        });

        // After a Load: make the fractal controls show what the loaded config put into the render settings.
        Runnable syncFractalControls = () -> {
            RenderSettings settings = fractalPanel.getRenderSettings();
            updatingPresets[0] = true;
            try {
                topBar.fractal.setSelectedSilently(settings.algorithm());
                topBar.juliaC.setEnabled(settings.algorithm() == RenderAlgorithm.JULIA);
                Object match = mandelbrotPosition;
                for (JuliaPreset preset : JuliaPreset.values()) {
                    Complex presetC = preset.c();
                    if (presetC.real == settings.c().real && presetC.imaginary == settings.c().imaginary) {
                        match = preset;
                        break;
                    }
                }
                if (match == mandelbrotPosition) {
                    mandelbrotPosition.set(settings.c());
                }
                topBar.juliaC.setSelectedItem(match);
            } finally {
                updatingPresets[0] = false;
            }
            topBar.juliaC.repaint();
            topBar.palette.repaint();
        };

        topBar.palette.addActionListener(_ -> {
            JDialog paletteDialog = new JDialog(frame, "Edit Palette", false);
            paletteDialog.setResizable(false);
            PaletteEditorPanel editor = new PaletteEditorPanel(fractalPanel.getPalette(), (palette, done) -> {
                fractalPanel.setPalette(palette, done);
                topBar.palette.repaint();
            }, () -> paletteDialog.setVisible(false));
            paletteDialog.getContentPane().add(editor);
            paletteDialog.pack();
            paletteDialog.setLocationRelativeTo(frame);
            paletteDialog.setVisible(true);
        });

        topBar.render.addActionListener(_ -> {
            JDialog exportDialog = new JDialog(frame, "Render to File", true);
            exportDialog.setResizable(false);
            ExportPanel exportPanel = new ExportPanel(fractalPanel.getWidth(), fractalPanel.getHeight(),
                    fractalPanel.getCurrentViewport(), fractalPanel.getRenderSettings(), fractalPanel.getPalette(),
                    () -> exportDialog.setVisible(false));
            exportDialog.getContentPane().add(exportPanel);
            exportDialog.pack();
            exportDialog.setLocationRelativeTo(frame);
            exportDialog.setVisible(true);
        });

        topBar.save.addActionListener(_ -> saveConfig(frame, fractalPanel));
        topBar.load.addActionListener(_ -> loadConfig(frame, fractalPanel, syncFractalControls));
        topBar.home.addActionListener(_ -> fractalPanel.goHome());
        topBar.back.addActionListener(_ -> fractalPanel.goBack());
        statusBar.goTo.addActionListener(_ -> fractalPanel.promptGoTo());
        statusBar.themeToggle.addActionListener(_ -> {
            Theme.toggle();
            statusBar.syncThemeToggle();
        });

        AtomicReference<RenderStatus> latestStatus = new AtomicReference<>(new RenderStatus(false, 0.0, -1));
        AtomicReference<Complex> hoverPoint = new AtomicReference<>();

        Runnable refreshStatus = () -> {
            topBar.back.setEnabled(fractalPanel.hasHistory());
            statusBar.setZoom(fractalPanel.getCurrentViewport().zoom());
            statusBar.setCursorPoint(hoverPoint.get());
            RenderStatus status = latestStatus.get();
            if (status.rendering()) {
                statusBar.showRendering(status.progress());
            } else {
                statusBar.showIdle(status.lastRenderMillis());
            }
        };

        fractalPanel.setViewportListener(_ -> SwingUtilities.invokeLater(refreshStatus));
        fractalPanel.setRenderStatusListener(status -> SwingUtilities.invokeLater(() -> {
            latestStatus.set(status);
            refreshStatus.run();
        }));
        fractalPanel.setCursorMoveListener(point -> SwingUtilities.invokeLater(() -> {
            hoverPoint.set(point);
            refreshStatus.run();
        }));

        frame.getContentPane().add(topBar, BorderLayout.NORTH);
        frame.getContentPane().add(fractalPanel, BorderLayout.CENTER);
        frame.getContentPane().add(statusBar, BorderLayout.SOUTH);

        frame.pack();
        frame.setMinimumSize(new Dimension(900, 560));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        SwingUtilities.invokeLater(fractalPanel::requestFocusInWindow);

        refreshStatus.run();
    }

    /** The Julia c dropdown's top entry: the Mandelbrot coordinate the view was centred on when Julia was last selected. */
    private static final class MandelbrotPosition {
        private double real;
        private double imaginary;

        MandelbrotPosition(Complex c) {
            set(c);
        }

        void set(Complex c) {
            this.real = c.real;
            this.imaginary = c.imaginary;
        }

        Complex c() {
            return Complex.fromCartesian(real, imaginary);
        }

        @Override
        public String toString() {
            return String.format("Mandelbrot position (%.8f %s %.8fi)", real, imaginary < 0 ? "-" : "+", Math.abs(imaginary));
        }
    }

    /** Saves the current view (center/zoom) and palette as a pretty-printed JSON config file. */
    private static void saveConfig(JFrame frame, FractalPanel fractalPanel) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Mandelbrot config (*.json)", "json"));
        chooser.setSelectedFile(new File("mandelbrot-config.json"));
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = withJsonExtension(chooser.getSelectedFile());
        MandelbrotConfig config = MandelbrotConfig.capture(fractalPanel.getCurrentViewport(), fractalPanel.getPalette(),
                fractalPanel.getRenderSettings());
        try {
            MandelbrotConfigIO.saveJson(file, config);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(frame, "Failed to save: " + ex.getMessage(), "Save failed",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Loads a {@link MandelbrotConfig} from either a {@code .json} config
     * file or a {@code .png} previously exported by this app (the config is
     * embedded as metadata in every exported PNG), and jumps the live view
     * to it. Window size is left untouched -- only center, zoom, and
     * palette are restored.
     */
    private static void loadConfig(JFrame frame, FractalPanel fractalPanel, Runnable syncFractalControls) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Mandelbrot config (*.json, *.png)", "json", "png"));
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        try {
            MandelbrotConfig config = MandelbrotConfigIO.load(file);
            fractalPanel.applyConfig(config);
            syncFractalControls.run();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(frame, "Failed to load: " + ex.getMessage(), "Load failed",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private static File withJsonExtension(File file) {
        if (file.getName().toLowerCase().endsWith(".json")) {
            return file;
        }
        return new File(file.getParentFile(), file.getName() + ".json");
    }
}

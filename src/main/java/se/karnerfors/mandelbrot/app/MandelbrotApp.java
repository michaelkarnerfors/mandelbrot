package se.karnerfors.mandelbrot.app;

import java.awt.BorderLayout;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JToolBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

import se.karnerfors.mandelbrot.io.MandelbrotConfig;
import se.karnerfors.mandelbrot.io.MandelbrotConfigIO;
import se.karnerfors.mandelbrot.math.Complex;
import se.karnerfors.mandelbrot.math.Viewport;
import se.karnerfors.mandelbrot.render.JuliaPreset;
import se.karnerfors.mandelbrot.render.RenderAlgorithm;
import se.karnerfors.mandelbrot.render.RenderSettings;
import se.karnerfors.mandelbrot.ui.ExportPanel;
import se.karnerfors.mandelbrot.ui.FractalPanel;
import se.karnerfors.mandelbrot.ui.PaletteEditorPanel;
import se.karnerfors.mandelbrot.ui.RenderStatus;

public class MandelbrotApp {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(MandelbrotApp::createAndShowGui);
    }

    private static void createAndShowGui() {
        FractalPanel fractalPanel = new FractalPanel(1000, 700);

        JFrame frame = new JFrame("Mandelbrot — " + fractalPanel.getWorkerThreadCount() + " render threads");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JButton homeButton = new JButton("Home");
        JButton backButton = new JButton("Back");
        backButton.setEnabled(false);
        JButton paletteButton = new JButton("Palette…");
        JButton renderButton = new JButton("Render…");
        JButton saveButton = new JButton("Save…");
        JButton loadButton = new JButton("Load…");

        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.add(homeButton);
        toolBar.add(backButton);
        toolBar.add(paletteButton);
        toolBar.add(renderButton);
        toolBar.addSeparator();
        toolBar.add(saveButton);
        toolBar.add(loadButton);
        toolBar.addSeparator();
        toolBar.add(new JLabel("Supersample: "));

        ButtonGroup supersampleGroup = new ButtonGroup();
        int currentFactor = fractalPanel.getRenderSettings().supersampleFactor();
        for (int factor : new int[] { 1, 2, 4, 9 }) {
            JRadioButton radio = new JRadioButton(factor + "x", factor == currentFactor);
            radio.addActionListener(_ -> {
                fractalPanel.setRenderSettings(fractalPanel.getRenderSettings().withSupersampleFactor(factor));
            });
            supersampleGroup.add(radio);
            toolBar.add(radio);
        }

        toolBar.addSeparator();
        toolBar.add(new JLabel("Fractal: "));

        JComboBox<RenderAlgorithm> fractalSelector = new JComboBox<>(RenderAlgorithm.values());
        fractalSelector.setSelectedItem(fractalPanel.getRenderSettings().algorithm());
        fractalSelector.setMaximumSize(fractalSelector.getPreferredSize());
        toolBar.add(fractalSelector);

        toolBar.add(new JLabel("  Julia c: "));
        MandelbrotPosition mandelbrotPosition = new MandelbrotPosition(fractalPanel.getCurrentViewport().center());
        JComboBox<Object> presetSelector = new JComboBox<>();
        presetSelector.addItem(mandelbrotPosition);
        for (JuliaPreset preset : JuliaPreset.values()) {
            presetSelector.addItem(preset);
        }
        presetSelector.setSelectedItem(JuliaPreset.DOUADY_RABBIT);
        presetSelector.setPrototypeDisplayValue(new MandelbrotPosition(Complex.fromCartesian(-0.12345678, -0.12345678)));
        presetSelector.setMaximumSize(presetSelector.getPreferredSize());
        boolean[] updatingPresets = { false };
        presetSelector.setEnabled(fractalPanel.getRenderSettings().algorithm() == RenderAlgorithm.JULIA);
        toolBar.add(presetSelector);

        fractalSelector.addActionListener(_ -> {
            RenderAlgorithm selected = (RenderAlgorithm) fractalSelector.getSelectedItem();
            RenderSettings current = fractalPanel.getRenderSettings();
            presetSelector.setEnabled(selected == RenderAlgorithm.JULIA);
            if (selected == null || selected == current.algorithm()) {
                return;
            }
            RenderSettings next = current.withAlgorithm(selected);
            if (selected == RenderAlgorithm.JULIA) {
                // Mandelbrot -> Julia: start from the point the Mandelbrot view is centred on.
                Complex centre = fractalPanel.getCurrentViewport().center();
                mandelbrotPosition.set(centre);
                updatingPresets[0] = true;
                presetSelector.setSelectedItem(mandelbrotPosition);
                updatingPresets[0] = false;
                presetSelector.repaint();
                next = next.withC(centre);
            }
            fractalPanel.setRenderSettingsAndGoHome(next);
        });
        presetSelector.addActionListener(_ -> {
            if (updatingPresets[0]) {
                return;
            }
            Object selected = presetSelector.getSelectedItem();
            if (selected instanceof JuliaPreset preset) {
                fractalPanel.setRenderSettingsAndGoHome(fractalPanel.getRenderSettings().withC(preset.c()));
            } else if (selected instanceof MandelbrotPosition position) {
                fractalPanel.setRenderSettingsAndGoHome(fractalPanel.getRenderSettings().withC(position.c()));
            }
        });

        // After a Load: make the two dropdowns show what the loaded config put into the render settings.
        Runnable syncFractalControls = () -> {
            RenderSettings settings = fractalPanel.getRenderSettings();
            updatingPresets[0] = true;
            try {
                fractalSelector.setSelectedItem(settings.algorithm());
                presetSelector.setEnabled(settings.algorithm() == RenderAlgorithm.JULIA);
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
                presetSelector.setSelectedItem(match);
            } finally {
                updatingPresets[0] = false;
            }
            presetSelector.repaint();
        };

        paletteButton.addActionListener(_ -> {
            JDialog paletteDialog = new JDialog(frame, "Edit Palette", false);
            paletteDialog.setResizable(false);
            PaletteEditorPanel editor = new PaletteEditorPanel(fractalPanel.getPalette(), fractalPanel::setPalette,
                    () -> paletteDialog.setVisible(false));
            paletteDialog.getContentPane().add(editor);
            paletteDialog.pack();
            paletteDialog.setLocationRelativeTo(frame);
            paletteDialog.setVisible(true);
        });

        renderButton.addActionListener(_ -> {
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

        saveButton.addActionListener(_ -> saveConfig(frame, fractalPanel));
        loadButton.addActionListener(_ -> loadConfig(frame, fractalPanel, syncFractalControls));

        JLabel hintLabel = new JLabel(" Left-drag: zoom to region   ·   Right-drag: pan   ·   Wheel: zoom ");
        JLabel infoLabel = new JLabel("", SwingConstants.RIGHT);

        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.add(hintLabel, BorderLayout.WEST);
        statusBar.add(infoLabel, BorderLayout.EAST);
        statusBar.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));

        AtomicReference<RenderStatus> latestStatus = new AtomicReference<>(new RenderStatus(false, 0.0, -1));
        AtomicReference<Complex> hoverPoint = new AtomicReference<>();

        Runnable refreshStatus = () -> {
            backButton.setEnabled(fractalPanel.hasHistory());
            Viewport viewport = fractalPanel.getCurrentViewport();
            StringBuilder text = new StringBuilder();
            Complex hover = hoverPoint.get();
            if (hover != null) {
                text.append(String.format("Re: %.6f  Im: %.6f   ", hover.real, hover.imaginary));
            }
            text.append(String.format("Zoom: %.3e", viewport.zoom()));
            RenderStatus status = latestStatus.get();
            if (status.rendering()) {
                text.append(String.format("   Rendering… %.0f%%", status.progress() * 100));
            } else if (status.lastRenderMillis() >= 0) {
                text.append(String.format("   Last render: %.2fs", status.lastRenderMillis() / 1000.0));
            }
            text.append(' ');
            infoLabel.setText(text.toString());
        };

        homeButton.addActionListener(_ -> fractalPanel.goHome());
        backButton.addActionListener(_ -> fractalPanel.goBack());

        fractalPanel.setViewportListener(_ -> SwingUtilities.invokeLater(refreshStatus));
        fractalPanel.setRenderStatusListener(status -> SwingUtilities.invokeLater(() -> {
            latestStatus.set(status);
            refreshStatus.run();
        }));
        fractalPanel.setCursorMoveListener(point -> SwingUtilities.invokeLater(() -> {
            hoverPoint.set(point);
            refreshStatus.run();
        }));

        frame.getContentPane().add(toolBar, BorderLayout.NORTH);
        frame.getContentPane().add(fractalPanel, BorderLayout.CENTER);
        frame.getContentPane().add(statusBar, BorderLayout.SOUTH);

        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

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

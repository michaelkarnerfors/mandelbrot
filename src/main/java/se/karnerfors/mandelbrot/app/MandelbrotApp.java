package se.karnerfors.mandelbrot.app;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import se.karnerfors.mandelbrot.io.Bookmark;
import se.karnerfors.mandelbrot.io.BookmarkStore;
import se.karnerfors.mandelbrot.io.MandelbrotConfig;
import se.karnerfors.mandelbrot.io.PaletteLibrary;
import se.karnerfors.mandelbrot.io.SessionState;
import se.karnerfors.mandelbrot.io.SessionStore;
import se.karnerfors.mandelbrot.math.Complex;
import se.karnerfors.mandelbrot.render.IterationGrid;
import se.karnerfors.mandelbrot.render.JuliaPreset;
import se.karnerfors.mandelbrot.render.RenderAlgorithm;
import se.karnerfors.mandelbrot.render.RenderJob;
import se.karnerfors.mandelbrot.render.RenderQueue;
import se.karnerfors.mandelbrot.render.RenderSettings;
import se.karnerfors.mandelbrot.ui.BookmarkPopover;
import se.karnerfors.mandelbrot.ui.FractalPanel;
import se.karnerfors.mandelbrot.palette.Palette;
import se.karnerfors.mandelbrot.ui.PaletteEditorPanel;
import se.karnerfors.mandelbrot.ui.PaletteMenu;
import se.karnerfors.mandelbrot.ui.RenderDialog;
import se.karnerfors.mandelbrot.ui.RenderQueuePopover;
import se.karnerfors.mandelbrot.ui.PaletteSwatchIcon;
import se.karnerfors.mandelbrot.ui.RenderStatus;
import se.karnerfors.mandelbrot.ui.StatusBar;
import se.karnerfors.mandelbrot.ui.Theme;
import se.karnerfors.mandelbrot.ui.TopBar;

public class MandelbrotApp {

    /** {@code -Dmandelbrot.theme=dark} starts in the dark theme; the status bar switch changes it at runtime. */
    private static final String THEME_PROPERTY = "mandelbrot.theme";

    private static final int MIN_WIDTH = 900;
    private static final int MIN_HEIGHT = 560;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(MandelbrotApp::createAndShowGui);
    }

    private static void createAndShowGui() {
        Theme.install("dark".equalsIgnoreCase(System.getProperty(THEME_PROPERTY)) ? Theme.Mode.DARK : Theme.Mode.LIGHT);

        // Pick up where the last run left off (view, palette, fractal, supersample, window, export choices).
        SessionStore sessionStore = new SessionStore(SessionStore.defaultDirectory());
        Optional<SessionState> remembered = sessionStore.load();

        FractalPanel fractalPanel = new FractalPanel(1000, 700);
        if (remembered.isPresent()) {
            try {
                fractalPanel.restoreSession(remembered.get().view(), remembered.get().supersample());
            } catch (RuntimeException ex) {
                System.err.println("Could not restore the previous session: " + ex.getMessage());
                remembered = Optional.empty();
            }
        }
        RenderSettings initialSettings = fractalPanel.getRenderSettings();

        JFrame frame = new JFrame("Mandelbrot / Julia — " + fractalPanel.getWorkerThreadCount() + " render threads");

        AtomicReference<SessionState.ExportData> exportChoices =
                new AtomicReference<>(remembered.map(SessionState::export).orElse(null));
        Rectangle[] normalBounds = { null };
        SessionRecorder recorder = new SessionRecorder(sessionStore, () -> new SessionState(SessionState.CURRENT_VERSION,
                MandelbrotConfig.capture(fractalPanel.getCurrentViewport(), fractalPanel.getPalette(),
                        fractalPanel.getRenderSettings()),
                fractalPanel.getRenderSettings().supersampleFactor(),
                windowData(frame, normalBounds[0]), exportChoices.get()));

        TopBar topBar = new TopBar(initialSettings.algorithm(), initialSettings.supersampleFactor());
        StatusBar statusBar = new StatusBar();
        topBar.palette.setExtraIcon(new PaletteSwatchIcon(fractalPanel::getPalette));

        // The palette button is labelled with the name of the active palette, or "Custom" when it is not in the library.
        PaletteLibrary paletteLibrary = new PaletteLibrary(SessionStore.defaultDirectory());
        // Several entries can hold identical colours (a copy saved without edits): remember which one was picked.
        String[] pickedName = { null };
        Runnable updatePaletteLabel = () -> {
            topBar.palette.setLabel(paletteLibrary.nameOf(fractalPanel.getPalette(), pickedName[0])
                    .map(MandelbrotApp::shortLabel).orElse("Custom"));
            topBar.palette.repaint();
        };
        updatePaletteLabel.run();

        // Warn next to the settle control when the chosen quality would take a large share of the Java heap.
        Runnable updateMemoryNote = () -> {
            int factor = fractalPanel.getRenderSettings().supersampleFactor();
            long cells = (long) fractalPanel.getWidth() * factor;
            topBar.setMemoryEstimate(IterationGrid.estimatedBytes(cells, (long) fractalPanel.getHeight() * factor),
                    Runtime.getRuntime().maxMemory());
        };
        topBar.supersample.setChangeListener(factor -> {
            fractalPanel.setRenderSettings(fractalPanel.getRenderSettings().withSupersampleFactor(factor));
            updateMemoryNote.run();
            recorder.markDirty();
        });

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
            updatePaletteLabel.run();
        };

        if (remembered.isPresent()) {
            syncFractalControls.run();
        }

        // Palette menu and the non-modal editor.
        JDialog[] editorDialog = { null };
        PaletteEditorPanel[] editorPanel = { null };
        PaletteMenu.Host[] menuHost = { null };

        Runnable closeEditor = () -> {
            JDialog dialog = editorDialog[0];
            if (dialog == null) {
                return;
            }
            editorDialog[0] = null;
            if (editorPanel[0].isDirty()) {
                fractalPanel.setPalette(editorPanel[0].baseline(), null); // unapplied edits are discarded
            }
            dialog.dispose();
            updatePaletteLabel.run();
        };

        Runnable openEditor = () -> {
            if (editorDialog[0] != null) {
                editorDialog[0].toFront();
                return;
            }
            JDialog dialog = new JDialog(frame, "Edit palette", false);
            // When one of your own palettes is active, the editor starts with its name so Overwrite is one click away.
            String startName = paletteLibrary.nameOf(fractalPanel.getPalette(), pickedName[0])
                    .filter(n -> paletteLibrary.statusOf(n) == PaletteLibrary.NameStatus.EXISTING).orElse(null);
            PaletteEditorPanel panel = new PaletteEditorPanel(fractalPanel.getPalette(), startName, new PaletteEditorPanel.Host() {
                @Override
                public void preview(Palette palette) {
                    fractalPanel.setPaletteLive(palette);
                    updatePaletteLabel.run();
                }

                @Override
                public void apply(Palette palette) {
                    fractalPanel.setPalette(palette, null);
                    recorder.markDirty();
                    updatePaletteLabel.run();
                }

                @Override
                public void revert(Palette palette) {
                    fractalPanel.setPalette(palette, null);
                    topBar.palette.repaint();
                }

                @Override
                public PaletteLibrary.NameStatus nameStatus(String name) {
                    return paletteLibrary.statusOf(name);
                }

                @Override
                public Optional<String> saveAs(String name, Palette palette) {
                    try {
                        String stored = paletteLibrary.save(name, palette);
                        pickedName[0] = stored;
                        fractalPanel.setPalette(palette, null);
                        recorder.markDirty();
                        updatePaletteLabel.run();
                        return Optional.of(stored);
                    } catch (IOException ex) {
                        javax.swing.JOptionPane.showMessageDialog(dialog,
                                "Could not save " + paletteLibrary.file() + ":\n" + ex.getMessage(), "Save failed",
                                javax.swing.JOptionPane.ERROR_MESSAGE);
                        return Optional.empty();
                    }
                }
            });
            editorDialog[0] = dialog;
            editorPanel[0] = panel;
            dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
            dialog.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    closeEditor.run();
                }
            });
            dialog.getRootPane().registerKeyboardAction(_ -> closeEditor.run(),
                    javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
                    javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
            dialog.getContentPane().add(panel);
            dialog.pack();
            Point origin = fractalPanel.isShowing() ? fractalPanel.getLocationOnScreen() : frame.getLocation();
            dialog.setLocation(origin.x + fractalPanel.getWidth() - dialog.getWidth() - 16, origin.y + 12);
            dialog.setVisible(true);
        };

        menuHost[0] = new PaletteMenu.Host() {
            @Override
            public Palette current() {
                return fractalPanel.getPalette();
            }

            @Override
            public Optional<String> activeName() {
                return paletteLibrary.nameOf(fractalPanel.getPalette(), pickedName[0]);
            }

            @Override
            public void choose(String name, Palette palette) {
                pickedName[0] = name;
                closeEditor.run();
                fractalPanel.setPalette(palette, null);
                recorder.markDirty();
                updatePaletteLabel.run();
            }

            @Override
            public void editCurrent() {
                openEditor.run();
            }

            @Override
            public void libraryChanged() {
                updatePaletteLabel.run();
            }
        };
        topBar.palette.addActionListener(_ -> PaletteMenu.show(topBar.palette, paletteLibrary, menuHost[0]));

        // Bookmarks: places you want to come back to (replaces the old Save/Load buttons).
        BookmarkStore bookmarkStore = new BookmarkStore(SessionStore.defaultDirectory());
        BookmarkPopover.Host bookmarkHost = new BookmarkPopover.Host() {
            @Override
            public MandelbrotConfig currentView() {
                return MandelbrotConfig.capture(fractalPanel.getCurrentViewport(), fractalPanel.getPalette(),
                        fractalPanel.getRenderSettings());
            }

            @Override
            public java.awt.image.BufferedImage snapshot() {
                return fractalPanel.snapshot();
            }

            @Override
            public void open(Bookmark bookmark) {
                closeEditor.run();
                pickedName[0] = null;
                fractalPanel.applyConfig(bookmark.view());
                syncFractalControls.run();
                recorder.markDirty();
            }
        };
        topBar.bookmarks.addActionListener(_ -> BookmarkPopover.show(topBar.bookmarks, bookmarkStore, bookmarkHost));
        topBar.home.addActionListener(_ -> fractalPanel.goHome());
        topBar.back.addActionListener(_ -> fractalPanel.goBack());
        statusBar.goTo.addActionListener(_ -> fractalPanel.promptGoTo());
        statusBar.themeToggle.addActionListener(_ -> {
            Theme.toggle();
            statusBar.syncThemeToggle();
        });

        AtomicReference<RenderStatus> latestStatus = new AtomicReference<>(RenderStatus.idle());
        AtomicReference<Complex> hoverPoint = new AtomicReference<>();

        Runnable refreshStatus = () -> {
            topBar.back.setEnabled(fractalPanel.hasHistory());
            statusBar.setZoom(fractalPanel.getCurrentViewport().zoom());
            statusBar.setCursorPoint(hoverPoint.get());
            statusBar.showStatus(latestStatus.get());
            updateMemoryNote.run();
        };

        // Only viewport changes mark the session dirty: cursor movement and render progress fire far too often.
        fractalPanel.setViewportListener(_ -> SwingUtilities.invokeLater(() -> {
            refreshStatus.run();
            recorder.markDirty();
        }));
        fractalPanel.setRenderStatusListener(status -> SwingUtilities.invokeLater(() -> {
            latestStatus.set(status);
            refreshStatus.run();
        }));
        fractalPanel.setCursorMoveListener(point -> SwingUtilities.invokeLater(() -> {
            hoverPoint.set(point);
            refreshStatus.run();
        }));

        // Render…: pictures are added to a queue that draws them one at a time in the background.
        RenderQueue renderQueue = new RenderQueue();
        renderQueue.addChangeListener(() -> statusBar.queue.update(renderQueue));
        File rendersFolder = SessionStore.defaultDirectory().resolve("renders").toFile();
        RenderQueuePopover.Host queueHost = new RenderQueuePopover.Host() {
            @Override
            public void open(File file) {
                try {
                    Desktop.getDesktop().open(file);
                } catch (IOException | RuntimeException ex) {
                    JOptionPane.showMessageDialog(frame, "Could not open " + file.getName() + ": " + ex.getMessage(),
                            "Open failed", JOptionPane.ERROR_MESSAGE);
                }
            }

            @Override
            public void showInFolder(File file) {
                try {
                    Desktop desktop = Desktop.getDesktop();
                    if (desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
                        desktop.browseFileDirectory(file);
                    } else {
                        desktop.open(file.getAbsoluteFile().getParentFile());
                    }
                } catch (IOException | RuntimeException ex) {
                    JOptionPane.showMessageDialog(frame, "Could not show the folder: " + ex.getMessage(),
                            "Show in folder failed", JOptionPane.ERROR_MESSAGE);
                }
            }

            @Override
            public void openRendersFolder() {
                try {
                    File folder = rendersFolder;
                    java.nio.file.Files.createDirectories(folder.toPath());
                    Desktop.getDesktop().open(folder);
                } catch (IOException | RuntimeException ex) {
                    JOptionPane.showMessageDialog(frame, "Could not open the renders folder: " + ex.getMessage(),
                            "Open failed", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        statusBar.queue.addActionListener(_ -> RenderQueuePopover.show(statusBar.queue, renderQueue, queueHost));

        RenderDialog.Host renderHost = new RenderDialog.Host() {
            @Override
            public MandelbrotConfig view() {
                return MandelbrotConfig.capture(fractalPanel.getCurrentViewport(), fractalPanel.getPalette(),
                        fractalPanel.getRenderSettings());
            }

            @Override
            public Palette palette() {
                return fractalPanel.getPalette();
            }

            @Override
            public String paletteLabel() {
                return paletteLibrary.nameOf(fractalPanel.getPalette(), pickedName[0]).orElse("Custom");
            }

            @Override
            public Dimension windowSize() {
                return fractalPanel.getSize();
            }

            @Override
            public java.awt.image.BufferedImage snapshot() {
                return fractalPanel.snapshot();
            }

            @Override
            public SessionState.ExportData remembered() {
                return exportChoices.get();
            }

            @Override
            public OptionalDouble secondsPerSample() {
                RenderStatus status = latestStatus.get();
                int pixels = fractalPanel.getWidth() * fractalPanel.getHeight();
                if (status.stage() != RenderStatus.Stage.SETTLED || status.millis() <= 0 || pixels <= 0) {
                    return OptionalDouble.empty();
                }
                double samples = (double) pixels * status.settleFactor() * status.settleFactor();
                return OptionalDouble.of(status.millis() / 1000.0 / samples);
            }

            @Override
            public int settleFactor() {
                return fractalPanel.getRenderSettings().supersampleFactor();
            }

            @Override
            public File defaultFolder() {
                return rendersFolder;
            }

            @Override
            public boolean queued(File file) {
                return renderQueue.usesFile(file);
            }
        };
        JDialog[] renderDialog = { null };
        topBar.render.addActionListener(_ -> {
            if (renderDialog[0] != null && renderDialog[0].isShowing()) {
                renderDialog[0].toFront();
                return;
            }
            renderDialog[0] = RenderDialog.show(frame, topBar.render, renderHost, request -> {
                renderQueue.add(request.view(), request.paletteLabel(), request.framingScale(), request.width(),
                        request.height(), request.supersample(), request.file(), request.thumbnail());
                exportChoices.set(new SessionState.ExportData(request.width(), request.height(), request.supersample(),
                        request.file().getAbsolutePath()));
                recorder.markDirty();
            });
        });

        // Closing with pictures still being drawn would lose them: ask first.
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (renderQueue.hasActiveJobs()) {
                    int active = renderQueue.count(RenderJob.State.RENDERING) + renderQueue.count(RenderJob.State.WAITING);
                    int answer = JOptionPane.showConfirmDialog(frame,
                            active + (active == 1 ? " picture is" : " pictures are")
                                    + " still in the render queue. Quit and lose "
                                    + (active == 1 ? "it?" : "them?"),
                            "Quit with renders in progress", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                    if (answer != JOptionPane.YES_OPTION) {
                        return;
                    }
                }
                recorder.flush();
                renderQueue.cancelAll();
                System.exit(0);
            }
        });

        frame.getContentPane().add(topBar, BorderLayout.NORTH);
        frame.getContentPane().add(fractalPanel, BorderLayout.CENTER);
        frame.getContentPane().add(statusBar, BorderLayout.SOUTH);

        frame.pack();
        frame.setMinimumSize(new Dimension(MIN_WIDTH, MIN_HEIGHT));
        if (!restoreWindow(frame, remembered.map(SessionState::window).orElse(null))) {
            frame.setLocationRelativeTo(null);
        }
        normalBounds[0] = frame.getBounds();
        frame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentMoved(ComponentEvent e) {
                windowChanged();
            }

            @Override
            public void componentResized(ComponentEvent e) {
                windowChanged();
            }

            private void windowChanged() {
                if ((frame.getExtendedState() & JFrame.MAXIMIZED_BOTH) != JFrame.MAXIMIZED_BOTH) {
                    normalBounds[0] = frame.getBounds();
                }
                recorder.markDirty();
            }
        });
        frame.addWindowStateListener(_ -> recorder.markDirty());
        frame.setVisible(true);
        SwingUtilities.invokeLater(fractalPanel::requestFocusInWindow);

        refreshStatus.run();
    }

    /** Keeps the palette button from growing with long names. */
    private static String shortLabel(String name) {
        return name.length() > 12 ? name.substring(0, 11) + "…" : name;
    }

    private static SessionState.WindowData windowData(JFrame frame, Rectangle normalBounds) {
        Rectangle bounds = normalBounds != null ? normalBounds : frame.getBounds();
        boolean maximized = (frame.getExtendedState() & JFrame.MAXIMIZED_BOTH) == JFrame.MAXIMIZED_BOTH;
        return new SessionState.WindowData(bounds.x, bounds.y, bounds.width, bounds.height, maximized);
    }

    /** Puts the window back where it was, unless that spot is no longer on any screen (monitor unplugged). */
    private static boolean restoreWindow(JFrame frame, SessionState.WindowData saved) {
        if (saved == null) {
            return false;
        }
        Rectangle bounds = new Rectangle(saved.x(), saved.y(), Math.max(saved.width(), MIN_WIDTH),
                Math.max(saved.height(), MIN_HEIGHT));
        if (!isOnScreen(bounds)) {
            return false;
        }
        frame.setBounds(bounds);
        if (saved.maximized()) {
            frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
        }
        return true;
    }

    /** True if a reasonable part of {@code bounds} (including its title bar) is visible on some screen. */
    private static boolean isOnScreen(Rectangle bounds) {
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            Rectangle visible = device.getDefaultConfiguration().getBounds().intersection(bounds);
            if (visible.width >= 160 && visible.height >= 80) {
                return true;
            }
        }
        return false;
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
}

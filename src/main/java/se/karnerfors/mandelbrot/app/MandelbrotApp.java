package se.karnerfors.mandelbrot.app;

import java.awt.BorderLayout;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToolBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

import se.karnerfors.mandelbrot.math.Complex;
import se.karnerfors.mandelbrot.math.Viewport;
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

        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.add(homeButton);
        toolBar.add(backButton);
        toolBar.add(paletteButton);

        paletteButton.addActionListener(e -> {
            JDialog paletteDialog = new JDialog(frame, "Edit Palette", false);
            PaletteEditorPanel editor = new PaletteEditorPanel(fractalPanel.getPalette(), fractalPanel::setPalette,
                    () -> paletteDialog.setVisible(false));
            paletteDialog.getContentPane().add(editor);
            paletteDialog.pack();
            paletteDialog.setLocationRelativeTo(frame);
            paletteDialog.setVisible(true);
        });

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

        homeButton.addActionListener(e -> fractalPanel.goHome());
        backButton.addActionListener(e -> fractalPanel.goBack());

        fractalPanel.setViewportListener(v -> SwingUtilities.invokeLater(refreshStatus));
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
}

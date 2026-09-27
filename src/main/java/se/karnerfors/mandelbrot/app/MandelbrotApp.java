package se.karnerfors.mandelbrot.app;

import java.awt.BorderLayout;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToolBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

import se.karnerfors.mandelbrot.math.Complex;
import se.karnerfors.mandelbrot.math.Viewport;
import se.karnerfors.mandelbrot.ui.FractalPanel;

public class MandelbrotApp {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(MandelbrotApp::createAndShowGui);
    }

    private static void createAndShowGui() {
        JFrame frame = new JFrame("Mandelbrot");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        FractalPanel fractalPanel = new FractalPanel(1000, 700);

        JButton homeButton = new JButton("Home");
        JButton backButton = new JButton("Back");
        backButton.setEnabled(false);

        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.add(homeButton);
        toolBar.add(backButton);

        JLabel hintLabel = new JLabel(" Left-drag: zoom to region   ·   Right-drag: pan   ·   Wheel: zoom ");
        JLabel infoLabel = new JLabel("", SwingConstants.RIGHT);

        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.add(hintLabel, BorderLayout.WEST);
        statusBar.add(infoLabel, BorderLayout.EAST);
        statusBar.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));

        AtomicBoolean rendering = new AtomicBoolean(false);
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
            if (rendering.get()) {
                text.append("   Rendering…");
            }
            text.append(' ');
            infoLabel.setText(text.toString());
        };

        homeButton.addActionListener(e -> fractalPanel.goHome());
        backButton.addActionListener(e -> fractalPanel.goBack());

        fractalPanel.setViewportListener(v -> SwingUtilities.invokeLater(refreshStatus));
        fractalPanel.setRenderStateListener(isRendering -> SwingUtilities.invokeLater(() -> {
            rendering.set(isRendering);
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

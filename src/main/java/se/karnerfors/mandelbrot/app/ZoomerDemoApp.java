package se.karnerfors.mandelbrot.app;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import se.karnerfors.mandelbrot.ui.DrawingPanel;
import se.karnerfors.mandelbrot.ui.PointF;
import se.karnerfors.mandelbrot.ui.ZoomEvent;
import se.karnerfors.mandelbrot.ui.ZoomListener;

public class ZoomerDemoApp {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                createAndShowGUI();
            }
        });
    }

    private static void createAndShowGUI() {
        JFrame frame = new JFrame("Zoomer Application");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        // Add the custom drawing panel
        DrawingPanel drawingPanel = new DrawingPanel();

        drawingPanel.addZoomListener(new ZoomListener() {
            @Override
            public void zoomPerformed(ZoomEvent event) {
//                System.out.println("Zoom Factor: " + event.getZoomFactor());
//                System.out.println("Offset X: " + event.getOffsetX());
//                System.out.println("Offset Y: " + event.getOffsetY());
                PointF origoOffset = event.getOrigoOffset();
                System.out.println("Origo Offset: " + origoOffset.getX() + ", " + origoOffset.getY());
            }
        });

        frame.getContentPane().add(drawingPanel);

        frame.pack(); // This will cause the frame to be sized to fit the preferred size and layouts of its subcomponents
        frame.setVisible(true);
    }
}

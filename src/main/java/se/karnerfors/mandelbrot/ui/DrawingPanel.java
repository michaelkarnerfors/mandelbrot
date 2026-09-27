package se.karnerfors.mandelbrot.ui;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.JPanel;

public class DrawingPanel extends JPanel {

    private static final long serialVersionUID = 2172823872617270409L;

    private BufferedImage image;
    private double zoomFactor = 1.0;
    private int lastX, lastY; // Tracks the last position of the mouse
    private double offsetX = 0, offsetY = 0;
    private PointF origoOffset = new PointF(0.0, 0.0);
    private final List<ZoomListener> zoomListeners = new ArrayList<>();

    public void addZoomListener(ZoomListener listener) {
        zoomListeners.add(listener);
    }

    public void removeZoomListener(ZoomListener listener) {
        zoomListeners.remove(listener);
    }

    protected void fireZoomEvent() {
        ZoomEvent event = new ZoomEvent(this, zoomFactor, offsetX, offsetY, origoOffset);
        for (ZoomListener listener : zoomListeners) {
            listener.zoomPerformed(event);
        }
    }
    
    public DrawingPanel() {

        MouseAdapter ma = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                // Record the starting point
                lastX = e.getX();
                lastY = e.getY();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                int dx = e.getX() - lastX;
                int dy = e.getY() - lastY;

                offsetX += dx;
                offsetY += dy;
                adjustOffsetOnPan(); // Ensure the image borders do not violate restrictions
                fireZoomEvent();
                repaint();

                lastX = e.getX();
                lastY = e.getY();
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);

        try {
            ClassLoader classLoader = getClass().getClassLoader();
            image = ImageIO.read(classLoader.getResourceAsStream("Acolyte.png"));
            if (image != null) {
                setPreferredSize(new Dimension(image.getWidth(), image.getHeight()));
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }

        // Add mouse wheel listener for zooming
        addMouseWheelListener(new MouseWheelListener() {
            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                double oldZoomFactor = zoomFactor;
                zoomFactor *= Math.pow(2, -e.getPreciseWheelRotation() * 0.2);
                zoomFactor = Math.max(zoomFactor, 1.0); // Minimum zoom limit

                if (zoomFactor != oldZoomFactor) {
                    int x = e.getX();
                    int y = e.getY();
                    offsetX = (offsetX - x) * (zoomFactor / oldZoomFactor) + x;
                    offsetY = (offsetY - y) * (zoomFactor / oldZoomFactor) + y;

                    adjustOffsetOnZoom();
                    fireZoomEvent();
                }

                revalidate();
                repaint();
            }
        });
    }

    private void adjustOffsetOnPan() {
        int imageWidth = image.getWidth();
        int imageHeight = image.getHeight();

        double maxOffsetX = getWidth() - (imageWidth * zoomFactor);
        double maxOffsetY = getHeight() - (imageHeight * zoomFactor);

        if (zoomFactor * image.getWidth() > getWidth()) {
            offsetX = Math.min(Math.max(offsetX, maxOffsetX), 0);
        } else {
            offsetX = Math.max(Math.min(offsetX, maxOffsetX), 0);
        }

        if (zoomFactor * image.getHeight() > getHeight()) {
            offsetY = Math.min(Math.max(offsetY, maxOffsetY), 0);
        } else {
            offsetY = Math.max(Math.min(offsetY, maxOffsetY), 0);
        }

        // Calculate the center of the viewport
        double viewportCenterX = getWidth() / 2.0;
        double viewportCenterY = getHeight() / 2.0;

        // Calculate the new origoOffset based on the current offsets
        double imageCenterX = (viewportCenterX - offsetX) / zoomFactor;
        double imageCenterY = (viewportCenterY - offsetY) / zoomFactor;

        // Update origoOffset
        origoOffset = new PointF(imageCenterX - imageWidth / 2.0, imageCenterY - imageHeight / 2.0);
    }
    
    private void adjustOffsetOnZoom() {

        int imageWidth = image.getWidth();
        int imageHeight = image.getHeight();

        // Assume this method contains logic to adjust offsets based on the zoom level,
        // similar to `adjustOffsetOnPan`, but potentially with additional considerations
        // for keeping the zoom focused around the mouse pointer or other zoom-specific adjustments.
        int imageWidthScaled = (int) (imageWidth * zoomFactor);
        int imageHeightScaled = (int) (imageHeight * zoomFactor);

        // Adjust horizontal offset
        if (imageWidthScaled < getWidth()) {
            offsetX = (getWidth() - imageWidthScaled) / 2;
        } else {
            offsetX = Math.min(offsetX, 0);
            offsetX = Math.max(offsetX, getWidth() - imageWidthScaled);
        }

        // Adjust vertical offset
        if (imageHeightScaled < getHeight()) {
            offsetY = (getHeight() - imageHeightScaled) / 2;
        } else {
            offsetY = Math.min(offsetY, 0);
            offsetY = Math.max(offsetY, getHeight() - imageHeightScaled);
        }

        // Calculate the center of the viewport
        double viewportCenterX = getWidth() / 2.0;
        double viewportCenterY = getHeight() / 2.0;

        // Calculate the new origoOffset based on zoom and current offsets
        double imageCenterX = (viewportCenterX - offsetX) / zoomFactor;
        double imageCenterY = (viewportCenterY - offsetY) / zoomFactor;

        // Update origoOffset
        origoOffset = new PointF(imageCenterX - imageWidth / 2.0, imageCenterY - imageHeight / 2.0);

    }

    /**
     * Converts screen coordinates to coordinates on the original image.
     *
     * @param screenX The x-coordinate on the screen.
     * @param screenY The y-coordinate on the screen.
     * @return A Point object containing the corresponding coordinates on the original image.
     */
    public PointF convertToImageCoordinates(int screenX, int screenY) {
        double imageX = (screenX - offsetX) / zoomFactor;
        double imageY = (screenY - offsetY) / zoomFactor;
        return new PointF(imageX, imageY);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (image != null) {
            Graphics2D g2d = (Graphics2D) g;
            g2d.translate(offsetX, offsetY);
            g2d.scale(zoomFactor, zoomFactor);
            g2d.drawImage(image, 0, 0, this);
        }
    }
}

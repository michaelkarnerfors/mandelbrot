package se.karnerfors.mandelbrot.ui;

import java.util.EventObject;

public class ZoomEvent extends EventObject {

    private static final long serialVersionUID = -7726655913302926337L;

    private final double zoomFactor;
    private final double offsetX;
    private final double offsetY;
    private final PointF origoOffset; // Add this line

    public ZoomEvent(Object source, double zoomFactor, double offsetX, double offsetY, PointF origoOffset) {
        super(source);
        this.zoomFactor = zoomFactor;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.origoOffset = origoOffset;
    }

    public double getZoomFactor() {
        return zoomFactor;
    }

    public double getOffsetX() {
        return offsetX;
    }

    public double getOffsetY() {
        return offsetY;
    }

    public PointF getOrigoOffset() {
        return origoOffset;
    }
}

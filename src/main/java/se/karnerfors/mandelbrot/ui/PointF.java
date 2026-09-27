package se.karnerfors.mandelbrot.ui;

public class PointF {
    private final double x;
    private final double y;

    public PointF(double x, double y) {
        this.x = x;
        this.y = y;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    @Override
    public String toString() {
        return "PointF{" +
               "x=" + x +
               ", y=" + y +
               '}';
    }
}

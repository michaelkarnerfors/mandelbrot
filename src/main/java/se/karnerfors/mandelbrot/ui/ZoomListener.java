package se.karnerfors.mandelbrot.ui;

import java.util.EventListener;

public interface ZoomListener extends EventListener {
    void zoomPerformed(ZoomEvent event);
}

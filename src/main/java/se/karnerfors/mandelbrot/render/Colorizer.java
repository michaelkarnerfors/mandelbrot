package se.karnerfors.mandelbrot.render;

import java.awt.Color;
import java.awt.image.BufferedImage;

import se.karnerfors.mandelbrot.palette.Palette;

/**
 * Turns raw iteration data into a plain BufferedImage. Because this is
 * decoupled from Renderer, a palette change only needs to re-run this (cheap)
 * pass over already-computed data, not a full recompute.
 */
public final class Colorizer {

    private Colorizer() {
    }

    public static BufferedImage colorize(IterationGrid grid, Palette palette) {
        BufferedImage image = new BufferedImage(grid.width(), grid.height(), BufferedImage.TYPE_INT_ARGB);

        for (int x = 0; x < grid.width(); x++) {
            for (int y = 0; y < grid.height(); y++) {
                Color color = grid.isEscaped(x, y)
                        ? palette.colorFor(grid.smoothIterationCountAt(x, y), grid.nuAt(x, y))
                        : Color.BLACK;
                image.setRGB(x, y, color.getRGB());
            }
        }

        return image;
    }
}

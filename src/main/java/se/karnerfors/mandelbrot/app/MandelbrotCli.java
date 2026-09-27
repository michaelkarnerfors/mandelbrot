package se.karnerfors.mandelbrot.app;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.imageio.ImageIO;

import se.karnerfors.mandelbrot.math.Complex;
import se.karnerfors.mandelbrot.math.Viewport;
import se.karnerfors.mandelbrot.palette.Palette;
import se.karnerfors.mandelbrot.render.Colorizer;
import se.karnerfors.mandelbrot.render.IterationGrid;
import se.karnerfors.mandelbrot.render.RenderSettings;
import se.karnerfors.mandelbrot.render.Renderer;

public class MandelbrotCli {
    public static void main(String[] args) throws InterruptedException {

        Viewport viewport = new Viewport(Complex.fromCartesian(-0.7602490234375D, -0.080498046875D),
                6553600.0D, 3840, 2160);
        RenderSettings settings = RenderSettings.defaults();
        int factor = Math.max(1, settings.supersampleFactor());

        int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        long start = System.nanoTime();
        IterationGrid grid;
        try {
            grid = Renderer.computeGridParallel(viewport.supersampled(factor), settings, executor,
                    Renderer.DEFAULT_TILE_SIZE, () -> false, null);
        } finally {
            executor.shutdown();
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        BufferedImage colorized = Colorizer.colorize(grid, Palette.classicRedWhiteBlack());
        BufferedImage image = Colorizer.downsample(colorized, factor);

        try {
            File outputfile = new File("saved.png");
            ImageIO.write(image, "png", outputfile);
        } catch (IOException e) {
        }

        System.out.println("Rendered in " + elapsedMillis + " ms using " + threads + " threads.");
    }
}

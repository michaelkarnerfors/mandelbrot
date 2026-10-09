package se.karnerfors.mandelbrot.app;

import java.awt.GraphicsEnvironment;

/**
 * The jar's entry point: with arguments it renders headlessly ({@link MandelbrotCli}), without them it opens the
 * window ({@link MandelbrotApp}).
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length > 0) {
            MandelbrotCli.main(args);
        } else if (GraphicsEnvironment.isHeadless()) {
            System.err.println("mandelbrot: no display to open the window on. To render a picture without it, "
                    + "give an input file (try --help).");
            System.exit(2);
        } else {
            MandelbrotApp.main(args);
        }
    }
}

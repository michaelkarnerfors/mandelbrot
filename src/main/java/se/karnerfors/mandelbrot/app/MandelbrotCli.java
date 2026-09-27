package se.karnerfors.mandelbrot.app;

import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

import se.karnerfors.mandelbrot.render.MandelbrotImage;

public class MandelbrotCli {
    public static void main(String[] args) {

        MandelbrotImage image = new MandelbrotImage(3840, 2160);
        image.render(-0.7602490234375D, -0.080498046875D, 6553600.0D);

        try {
            // retrieve image
            File outputfile = new File("saved.png");
            ImageIO.write(image, "png", outputfile);
        } catch (IOException e) {
        }

    }
}

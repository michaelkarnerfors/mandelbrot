package se.karnerfors.mandelbrot.render;

import java.awt.Color;
import java.awt.image.BufferedImage;

import se.karnerfors.mandelbrot.math.Complex;

public class MandelbrotImage extends BufferedImage {

    private static Color[] colours = { Color.RED, Color.WHITE, Color.BLACK };
    private static int shades = 16;

    private static Color[] palette = new Color[colours.length * shades];

    static {
        for (int i = 0; i < colours.length; i++) {
            for (int j = 0; j < shades; j++) {
                palette[i * shades + j] = blend(colours[i], colours[(i + 1) % colours.length],
                        ((float) j) / ((float) shades));
            }
        }

    }

    private static double log2 = Math.log(2.0D);

    public static int maxIterations = 1000;
    public static double escapeRadius = 256;

    public MandelbrotImage(int width, int height) {
        super(width, height, BufferedImage.TYPE_INT_ARGB);
    }

    public void render(double centerR, double centerI, double zoom) {
        for (int x = 0; x < this.getWidth(); x++) {
            System.out.println("Column: " + x);
            for (int y = 0; y < this.getHeight(); y++) {
                int iterations = 0;
                Complex c = Complex.fromCartesian(centerR + (x - this.getWidth() / 2) / zoom,
                        centerI + (y - this.getHeight() / 2) / zoom);
                Complex pixel = Complex.fromCartesian(0.0D, 0.0D);
                while (pixel.getValue() < escapeRadius && iterations++ < maxIterations) {
                    pixel.square().add(c);
                }

                double nu = 0.0D;

                Color result = null;
                if (iterations < maxIterations) {
                    nu = Math.log(Math.log(pixel.getValue()) / log2) / log2;
                    iterations++;
                    int colourIndex = Math.floorMod((int) Math.floor(iterations - nu), palette.length);
                    result = blend(palette[colourIndex], palette[(colourIndex + 1) % palette.length],
                            1.0F - (float) (nu % 1.0));
                } else {
                    result = Color.BLACK;
                }

                this.setRGB(x, y, result.getRGB());

            }
        }
    }

    private static Color blend(Color c1, Color c2, float balance) {

        float r1 = c1.getRed() / 255.0F;
        float r2 = c2.getRed() / 255.0F;

        float g1 = c1.getGreen() / 255.0F;
        float g2 = c2.getGreen() / 255.0F;

        float b1 = c1.getBlue() / 255.0F;
        float b2 = c2.getBlue() / 255.0F;

        float r = (float) Math.sqrt((1 - balance) * r1 * r1 + balance * r2 * r2);
        float g = (float) Math.sqrt((1 - balance) * g1 * g1 + balance * g2 * g2);
        float b = (float) Math.sqrt((1 - balance) * b1 * b1 + balance * b2 * b2);

        Color result = new Color(r, g, b);
        return result;
    }
}

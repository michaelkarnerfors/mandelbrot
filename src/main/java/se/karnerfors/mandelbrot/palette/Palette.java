package se.karnerfors.mandelbrot.palette;

import java.awt.Color;

/**
 * A cyclic palette built from a small set of anchor colors, each expanded into
 * a fixed number of blended shades. This is the exact color model the
 * original MandelbrotImage used (Red/White/Black x 16 shades, sqrt-blend);
 * it's lifted out here unchanged so Task 5 can build an editor around it.
 */
public final class Palette {

    private final Color[] stops;
    private final int shadesPerSegment;
    private final Color[] gradient;

    public Palette(Color[] stops, int shadesPerSegment) {
        this.stops = stops.clone();
        this.shadesPerSegment = shadesPerSegment;
        this.gradient = buildGradient(this.stops, shadesPerSegment);
    }

    public static Palette classicRedWhiteBlack() {
        return new Palette(new Color[] { Color.RED, Color.WHITE, Color.BLACK }, 16);
    }

    public Color[] stops() {
        return stops.clone();
    }

    public int shadesPerSegment() {
        return shadesPerSegment;
    }

    private static Color[] buildGradient(Color[] stops, int shadesPerSegment) {
        Color[] gradient = new Color[stops.length * shadesPerSegment];
        for (int i = 0; i < stops.length; i++) {
            for (int j = 0; j < shadesPerSegment; j++) {
                gradient[i * shadesPerSegment + j] = blend(stops[i], stops[(i + 1) % stops.length],
                        ((float) j) / ((float) shadesPerSegment));
            }
        }
        return gradient;
    }

    /**
     * @param smoothIterationCount continuous escape value (iterations - nu); only meaningful when escaped
     * @param nu                   fractional correction term; only meaningful when escaped
     */
    public Color colorFor(double smoothIterationCount, double nu) {
        int colourIndex = Math.floorMod((int) Math.floor(smoothIterationCount), gradient.length);
        return blend(gradient[colourIndex], gradient[(colourIndex + 1) % gradient.length],
                1.0F - (float) (nu % 1.0));
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

        return new Color(r, g, b);
    }
}

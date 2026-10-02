package se.karnerfors.mandelbrot.palette;

import java.awt.Color;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class Palette {

    private static final ObjectMapper JSON = new ObjectMapper();

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

    /**
     * The precomputed, fully gamma-blended gradient this palette cycles
     * through ({@code stops.length * shadesPerSegment} entries). Used by the
     * palette editor's preview strip so it shows the exact colors the real
     * render uses, rather than a separately-computed approximation.
     */
    public Color[] gradient() {
        return gradient.clone();
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

    /**
     * Renders this Palette as a JSON object string, e.g.
     * {@code {"stops":[{"r":255,"g":0,"b":0},...],"shadesPerSegment":16}}.
     */
    @Override
    public String toString() {
        try {
            return JSON.writeValueAsString(new PaletteView(stops, shadesPerSegment));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize Palette to JSON", e);
        }
    }

    /**
     * Jackson-friendly view of a Palette's exported state. Kept private and
     * separate from Palette itself so the derived {@code gradient} field
     * never leaks into the JSON, and so {@link Color} (not bean-friendly for
     * Jackson) doesn't need to be serialized directly.
     */
    private record PaletteView(List<RgbColor> stops, int shadesPerSegment) {
        PaletteView(Color[] stops, int shadesPerSegment) {
            this(Arrays.stream(stops).map(RgbColor::new).toList(), shadesPerSegment);
        }
    }

    private record RgbColor(int r, int g, int b) {
        RgbColor(Color color) {
            this(color.getRed(), color.getGreen(), color.getBlue());
        }
    }
}

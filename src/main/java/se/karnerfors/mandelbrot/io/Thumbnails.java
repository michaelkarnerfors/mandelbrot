package se.karnerfors.mandelbrot.io;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Bookmark pictures: cropped to 3:2, scaled to {@value #WIDTH} x {@value #HEIGHT} and stored as a JPEG in
 * base64, so a bookmark stays one self-contained piece of JSON. About 3 to 6 KB each.
 */
public final class Thumbnails {

    public static final int WIDTH = 144;
    public static final int HEIGHT = 96;

    private static final float QUALITY = 0.72f;

    private Thumbnails() {
    }

    /** Crops {@code source} to 3:2 around its centre, scales it down and returns it as base64 JPEG. */
    public static String encode(BufferedImage source) throws IOException {
        BufferedImage small = scale(source);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG writer available");
        }
        ImageWriter writer = writers.next();
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(QUALITY);
            writer.setOutput(out);
            writer.write(null, new IIOImage(small, null, null), param);
        } finally {
            writer.dispose();
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    /** The picture, or null if there is none or it cannot be read. */
    public static BufferedImage decode(String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        try {
            return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64.trim())));
        } catch (IOException | IllegalArgumentException ex) {
            return null;
        }
    }

    private static BufferedImage scale(BufferedImage source) {
        return cover(source, WIDTH, HEIGHT);
    }

    /** Crops {@code source} to the aspect ratio of {@code width x height} around its centre and scales it to that size. */
    public static BufferedImage cover(BufferedImage source, int width, int height) {
        int sw = source.getWidth();
        int sh = source.getHeight();
        // Largest rectangle with the target's proportions, centred in the source.
        int cw = sw;
        int ch = (int) Math.round(sw * (double) height / width);
        if (ch > sh) {
            ch = sh;
            cw = (int) Math.round(sh * (double) width / height);
        }
        int cx = (sw - cw) / 2;
        int cy = (sh - ch) / 2;

        // Halve repeatedly while the picture is much bigger than the target: much smoother than one big jump.
        BufferedImage current = toRgb(source.getSubimage(cx, cy, Math.max(1, cw), Math.max(1, ch)));
        while (current.getWidth() >= width * 2 && current.getHeight() >= height * 2) {
            current = resize(current, current.getWidth() / 2, current.getHeight() / 2);
        }
        return resize(current, width, height);
    }

    private static BufferedImage toRgb(BufferedImage image) {
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return rgb;
    }

    private static BufferedImage resize(BufferedImage image, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, 0, 0, w, h, null);
        g.dispose();
        return out;
    }
}

package se.karnerfors.mandelbrot.io;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.Optional;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Persistence for {@link MandelbrotConfig}: plain {@code .json} files for
 * explicit Save/Load, and a {@code tEXt} chunk embedded directly in exported
 * PNGs so an image carries the exact view and palette that produced it.
 * {@link #load(File)} dispatches on file extension, so a single "Load…" file
 * chooser can transparently open either a {@code .json} config or a
 * previously exported {@code .png} and recover the same view.
 */
public final class MandelbrotConfigIO {

    private static final String PNG_METADATA_FORMAT = "javax_imageio_png_1.0";
    private static final String PNG_TEXT_KEYWORD = "MandelbrotConfig";

    private static final ObjectMapper JSON = new ObjectMapper();

    private MandelbrotConfigIO() {
    }

    /** Writes {@code config} as pretty-printed JSON to {@code file}. */
    public static void saveJson(File file, MandelbrotConfig config) throws IOException {
        JSON.writerWithDefaultPrettyPrinter().writeValue(file, config);
    }

    /** Reads a {@link MandelbrotConfig} back from a JSON file written by {@link #saveJson}. */
    public static MandelbrotConfig loadJson(File file) throws IOException {
        return JSON.readValue(file, MandelbrotConfig.class);
    }

    /**
     * Loads a {@link MandelbrotConfig} from either a {@code .json} config
     * file or a {@code .png} previously written by {@link #writePng}.
     */
    public static MandelbrotConfig load(File file) throws IOException {
        if (file.getName().toLowerCase().endsWith(".png")) {
            return readPngConfig(file)
                    .orElseThrow(() -> new IOException("No Mandelbrot config metadata found in " + file.getName()));
        }
        return loadJson(file);
    }

    /**
     * Writes {@code image} as a PNG to {@code file}, embedding {@code
     * config} as a {@code tEXt} chunk so the exact view/palette that
     * produced it can later be recovered with {@link #readPngConfig}.
     */
    public static void writePng(File file, BufferedImage image, MandelbrotConfig config) throws IOException {
        String json = JSON.writeValueAsString(config);

        ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
        try {
            ImageWriteParam writeParam = writer.getDefaultWriteParam();
            ImageTypeSpecifier typeSpecifier = ImageTypeSpecifier.createFromBufferedImageType(image.getType());
            IIOMetadata metadata = writer.getDefaultImageMetadata(typeSpecifier, writeParam);

            IIOMetadataNode textEntry = new IIOMetadataNode("tEXtEntry");
            textEntry.setAttribute("keyword", PNG_TEXT_KEYWORD);
            textEntry.setAttribute("value", json);
            IIOMetadataNode text = new IIOMetadataNode("tEXt");
            text.appendChild(textEntry);
            IIOMetadataNode root = new IIOMetadataNode(PNG_METADATA_FORMAT);
            root.appendChild(text);
            metadata.mergeTree(PNG_METADATA_FORMAT, root);

            try (ImageOutputStream stream = ImageIO.createImageOutputStream(file)) {
                writer.setOutput(stream);
                writer.write(null, new IIOImage(image, null, metadata), writeParam);
            }
        } finally {
            writer.dispose();
        }
    }

    /** Reads back the {@link MandelbrotConfig} embedded in a PNG by {@link #writePng}, if any. */
    public static Optional<MandelbrotConfig> readPngConfig(File file) throws IOException {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("png");
        if (!readers.hasNext()) {
            return Optional.empty();
        }
        ImageReader reader = readers.next();
        try (ImageInputStream stream = ImageIO.createImageInputStream(file)) {
            if (stream == null) {
                return Optional.empty();
            }
            reader.setInput(stream);
            IIOMetadata metadata = reader.getImageMetadata(0);
            if (metadata == null) {
                return Optional.empty();
            }
            Node root = metadata.getAsTree(PNG_METADATA_FORMAT);
            String json = findTextEntryValue(root, PNG_TEXT_KEYWORD);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(JSON.readValue(json, MandelbrotConfig.class));
        } finally {
            reader.dispose();
        }
    }

    private static String findTextEntryValue(Node node, String keyword) {
        if ("tEXtEntry".equals(node.getNodeName())) {
            Node keywordAttr = node.getAttributes() != null ? node.getAttributes().getNamedItem("keyword") : null;
            if (keywordAttr != null && keyword.equals(keywordAttr.getNodeValue())) {
                Node valueAttr = node.getAttributes().getNamedItem("value");
                return valueAttr != null ? valueAttr.getNodeValue() : null;
            }
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            String found = findTextEntryValue(children.item(i), keyword);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}

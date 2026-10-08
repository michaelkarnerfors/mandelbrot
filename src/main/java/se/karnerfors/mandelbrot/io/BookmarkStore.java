package se.karnerfors.mandelbrot.io;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.imageio.ImageIO;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The user's bookmarks, kept in {@code ~/mandelbrot/bookmarks.json}, newest first. Loading never throws: a
 * missing or damaged file means "no bookmarks", and entries that cannot be used are skipped while the
 * rest are kept. Saving writes a temporary file and renames it over the real one.
 *
 * <p>Bookmarks travel as ordinary JSON files: {@link #export} writes one in the same format as
 * {@code bookmarks.json}, and {@link #importFrom} reads those, plus the older formats: a config file
 * saved by the former Save button, and PNGs exported by this app (which carry their config inside).
 */
public final class BookmarkStore {

    public static final int MAX_NAME_LENGTH = 60;

    private static final int CURRENT_VERSION = 1;
    private static final String FILE_NAME = "bookmarks.json";
    private static final ObjectMapper JSON = new ObjectMapper();

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FileData(int version, List<Bookmark> bookmarks) {
    }

    private final Path file;
    private final List<Bookmark> bookmarks = new ArrayList<>();

    public BookmarkStore(Path directory) {
        this.file = directory.resolve(FILE_NAME);
        reload();
    }

    public Path file() {
        return file;
    }

    public List<Bookmark> all() {
        return List.copyOf(bookmarks);
    }

    /** Re-reads the file, keeping the usable entries. */
    public void reload() {
        bookmarks.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            FileData data = JSON.readValue(file.toFile(), FileData.class);
            if (data == null || data.bookmarks() == null || data.version() > CURRENT_VERSION) {
                return;
            }
            addUsable(data.bookmarks(), bookmarks);
        } catch (IOException | RuntimeException ex) {
            System.err.println("Ignoring unreadable " + file + ": " + ex.getMessage());
            bookmarks.clear();
        }
    }

    /** Adds a bookmark at the top of the list. */
    public Bookmark add(String name, MandelbrotConfig view, String thumbnail) throws IOException {
        Bookmark bookmark = new Bookmark(UUID.randomUUID().toString(), cleanName(name), Instant.now().toString(), view,
                thumbnail);
        bookmarks.add(0, bookmark);
        save();
        return bookmark;
    }

    public void rename(String id, String newName) throws IOException {
        int index = indexOf(id);
        if (index >= 0) {
            bookmarks.set(index, bookmarks.get(index).withName(cleanName(newName)));
            save();
        }
    }

    public void delete(String id) throws IOException {
        int index = indexOf(id);
        if (index >= 0) {
            bookmarks.remove(index);
            save();
        }
    }

    /** Writes one bookmark to {@code target} in the same format as {@code bookmarks.json}. */
    public void export(Path target, Bookmark bookmark) throws IOException {
        writeAtomically(target, new FileData(CURRENT_VERSION, List.of(bookmark)));
    }

    /**
     * Reads bookmarks from a file and adds them (newest first, in the order they appear in the file).
     * Understands bookmark files, config files from the old Save button, and PNGs exported by this app.
     * Returns what was added.
     */
    public List<Bookmark> importFrom(Path source) throws IOException {
        List<Bookmark> incoming = read(source);
        if (incoming.isEmpty()) {
            throw new IOException("No usable bookmark found in " + source.getFileName());
        }
        List<Bookmark> added = new ArrayList<>();
        for (Bookmark bookmark : incoming) {
            // Always a fresh id: importing the same file twice, or one exported from here, must not collide.
            Bookmark copy = bookmark.withId(UUID.randomUUID().toString());
            added.add(copy);
        }
        List<Bookmark> reversed = new ArrayList<>(added);
        java.util.Collections.reverse(reversed);
        for (Bookmark bookmark : reversed) {
            bookmarks.add(0, bookmark);
        }
        save();
        return added;
    }

    private List<Bookmark> read(Path source) throws IOException {
        String fileName = source.getFileName().toString();
        String baseName = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
        File asFile = source.toFile();
        if (fileName.toLowerCase().endsWith(".png")) {
            MandelbrotConfig config = MandelbrotConfigIO.readPngConfig(asFile)
                    .orElseThrow(() -> new IOException("No Mandelbrot view is stored in " + fileName));
            if (!config.isUsable()) {
                throw new IOException("The view stored in " + fileName + " is not valid");
            }
            String thumbnail = null;
            try {
                BufferedImage image = ImageIO.read(asFile);
                if (image != null) {
                    thumbnail = Thumbnails.encode(image);
                }
            } catch (IOException | RuntimeException ex) {
                // The picture is a nicety; the view is what matters.
            }
            return List.of(new Bookmark(null, cleanName(baseName), Instant.now().toString(), config, thumbnail));
        }

        JsonNode root;
        try {
            root = JSON.readTree(asFile);
        } catch (IOException ex) {
            throw new IOException(fileName + " is not a bookmark file: " + ex.getMessage());
        }
        if (root != null && root.has("bookmarks")) {
            FileData data = JSON.treeToValue(root, FileData.class);
            List<Bookmark> usable = new ArrayList<>();
            if (data.bookmarks() != null) {
                addUsable(data.bookmarks(), usable);
            }
            return usable;
        }
        if (root != null && root.has("viewport")) {
            MandelbrotConfig config = JSON.treeToValue(root, MandelbrotConfig.class);
            if (!config.isUsable()) {
                throw new IOException("The view in " + fileName + " is not valid");
            }
            return List.of(new Bookmark(null, cleanName(baseName), Instant.now().toString(), config, null));
        }
        throw new IOException(fileName + " does not look like a bookmark or a saved view");
    }

    private static void addUsable(List<Bookmark> from, List<Bookmark> into) {
        for (Bookmark candidate : from) {
            if (candidate == null || candidate.view() == null || !candidate.view().isUsable()) {
                continue;
            }
            String id = candidate.id() == null || candidate.id().isBlank() ? UUID.randomUUID().toString() : candidate.id();
            boolean duplicate = false;
            for (Bookmark existing : into) {
                if (existing.id().equals(id)) {
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) {
                id = UUID.randomUUID().toString();
            }
            String created = candidate.created() == null ? Instant.now().toString() : candidate.created();
            into.add(new Bookmark(id, cleanName(candidate.name()), created, candidate.view(), candidate.thumbnail()));
        }
    }

    private int indexOf(String id) {
        for (int i = 0; i < bookmarks.size(); i++) {
            if (bookmarks.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private void save() throws IOException {
        writeAtomically(file, new FileData(CURRENT_VERSION, bookmarks));
    }

    private static void writeAtomically(Path target, FileData data) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        JSON.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), data);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Tidied name: trimmed, inner spaces collapsed, limited in length; a blank name becomes "Bookmark". */
    public static String cleanName(String name) {
        String tidy = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (tidy.isEmpty()) {
            return "Bookmark";
        }
        return tidy.length() > MAX_NAME_LENGTH ? tidy.substring(0, MAX_NAME_LENGTH).trim() : tidy;
    }

    /** Convenience for the UI. */
    public Optional<Bookmark> find(String id) {
        int index = indexOf(id);
        return index < 0 ? Optional.empty() : Optional.of(bookmarks.get(index));
    }
}

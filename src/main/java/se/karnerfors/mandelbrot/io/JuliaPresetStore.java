package se.karnerfors.mandelbrot.io;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import se.karnerfors.mandelbrot.math.Complex;
import se.karnerfors.mandelbrot.render.JuliaPreset;

/**
 * The user's Julia constants, kept in {@code ~/mandelbrot/julia-presets.json} in the order they are shown.
 * When the file does not exist yet, the list starts out as the classic presets; once it has been written
 * it is the user's own list, and deleting everything really leaves it empty. Loading never throws: a damaged
 * file means "the classic presets", and unusable entries are skipped. Saving writes a temporary file and
 * renames it over the real one.
 */
public final class JuliaPresetStore {

    public static final int MAX_NAME_LENGTH = 40;

    private static final int CURRENT_VERSION = 1;
    private static final String FILE_NAME = "julia-presets.json";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One saved constant c = re + im·i. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(String id, String name, double re, double im) {

        public Entry withName(String newName) {
            return new Entry(id, newName, re, im);
        }

        public Complex c() {
            return Complex.fromCartesian(re, im);
        }

        /** Same constant, to the precision of the file. */
        public boolean sameAs(double otherRe, double otherIm) {
            return Math.abs(re - otherRe) < 1e-12 && Math.abs(im - otherIm) < 1e-12;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FileData(int version, List<Entry> presets) {
    }

    private final Path file;
    private final List<Entry> entries = new ArrayList<>();

    public JuliaPresetStore(Path directory) {
        this.file = directory.resolve(FILE_NAME);
        reload();
    }

    public Path file() {
        return file;
    }

    public List<Entry> all() {
        return List.copyOf(entries);
    }

    /** Re-reads the file; a missing or damaged one gives the classic presets (not written until something changes). */
    public void reload() {
        entries.clear();
        if (Files.isRegularFile(file)) {
            try {
                FileData data = JSON.readValue(file.toFile(), FileData.class);
                if (data != null && data.presets() != null && data.version() <= CURRENT_VERSION) {
                    addUsable(data.presets());
                    return;
                }
            } catch (IOException | RuntimeException ex) {
                System.err.println("Ignoring unreadable " + file + ": " + ex.getMessage());
                entries.clear();
            }
        }
        for (JuliaPreset preset : JuliaPreset.values()) {
            Complex c = preset.c();
            entries.add(new Entry(UUID.randomUUID().toString(), preset.displayName(), c.real, c.imaginary));
        }
    }

    /** Adds a constant at the end of the list. */
    public Entry add(String name, double re, double im) throws IOException {
        Entry entry = new Entry(UUID.randomUUID().toString(), cleanName(name), re, im);
        entries.add(entry);
        save();
        return entry;
    }

    public void rename(String id, String newName) throws IOException {
        int index = indexOf(id);
        if (index >= 0) {
            entries.set(index, entries.get(index).withName(cleanName(newName)));
            save();
        }
    }

    public void delete(String id) throws IOException {
        int index = indexOf(id);
        if (index >= 0) {
            entries.remove(index);
            save();
        }
    }

    /** Moves an entry so that it ends up at {@code newIndex} in the list. */
    public void move(String id, int newIndex) throws IOException {
        int index = indexOf(id);
        if (index < 0) {
            return;
        }
        int target = Math.max(0, Math.min(entries.size() - 1, newIndex));
        if (target == index) {
            return;
        }
        entries.add(target, entries.remove(index));
        save();
    }

    /** The first entry with this constant, if any (used to show a check mark on the active one). */
    public Entry findByValue(double re, double im) {
        for (Entry entry : entries) {
            if (entry.sameAs(re, im)) {
                return entry;
            }
        }
        return null;
    }

    /** "Preset 3" style default name that is not in use yet. */
    public String suggestName() {
        Set<String> used = new HashSet<>();
        for (Entry entry : entries) {
            used.add(entry.name());
        }
        int n = entries.size() + 1;
        while (used.contains("Preset " + n)) {
            n++;
        }
        return "Preset " + n;
    }

    private void addUsable(List<Entry> from) {
        Set<String> ids = new HashSet<>();
        for (Entry candidate : from) {
            if (candidate == null || !Double.isFinite(candidate.re()) || !Double.isFinite(candidate.im())) {
                continue;
            }
            String id = candidate.id() == null || candidate.id().isBlank() || !ids.add(candidate.id())
                    ? UUID.randomUUID().toString() : candidate.id();
            ids.add(id);
            entries.add(new Entry(id, cleanName(candidate.name()), candidate.re(), candidate.im()));
        }
    }

    private int indexOf(String id) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private void save() throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        JSON.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), new FileData(CURRENT_VERSION, entries));
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Tidied name: trimmed, inner spaces collapsed, limited in length; a blank name becomes "Preset". */
    public static String cleanName(String name) {
        String tidy = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (tidy.isEmpty()) {
            return "Preset";
        }
        return tidy.length() > MAX_NAME_LENGTH ? tidy.substring(0, MAX_NAME_LENGTH).trim() : tidy;
    }

    /** "−0.391 − 0.587i" with a real minus sign, trimmed of trailing zeros. */
    public static String format(double re, double im) {
        return trim(re) + (im < 0 ? " − " : " + ") + trim(Math.abs(im)) + "i";
    }

    private static String trim(double v) {
        String s = String.format(java.util.Locale.ROOT, "%.5f", v);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        if (s.equals("-0")) {
            s = "0";
        }
        return s.startsWith("-") ? "−" + s.substring(1) : s;
    }
}

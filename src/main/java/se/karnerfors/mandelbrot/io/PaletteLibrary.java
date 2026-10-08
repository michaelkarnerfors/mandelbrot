package se.karnerfors.mandelbrot.io;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import se.karnerfors.mandelbrot.palette.Palette;

/**
 * The named palettes offered by the Palette menu: a few built in, and the user's own, which live in
 * {@code ~/mandelbrot/palettes.json} so they can be edited by hand, copied between machines, or
 * shared. Loading never throws: a missing or damaged file just means "no palettes of your own yet".
 * Entries in the file that are not usable (bad colours, empty name) are skipped, the rest are kept.
 *
 * <p>Call {@link #reload()} before showing the menu so a hand-edited file is picked up without a restart.
 */
public final class PaletteLibrary {

    public static final int MAX_NAME_LENGTH = 40;

    private static final int CURRENT_VERSION = 1;
    private static final String FILE_NAME = "palettes.json";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A palette with the name it is listed under. */
    public record NamedPalette(String name, Palette palette) {
    }

    /** One palette as written in the file. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(String name, MandelbrotConfig.PaletteData palette) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FileData(int version, List<Entry> palettes) {
    }

    private static final List<NamedPalette> BUILT_IN = List.of(
            builtIn("Classic", 0xFF0000, 0xFFFFFF, 0x000000),
            builtIn("Ocean", 0x081C40, 0x1E78C8, 0xAAEBF5, 0xFFFFFF, 0x081C40),
            builtIn("Ember", 0x140808, 0xA01E0A, 0xFF961E, 0xFFF0BE, 0x140808),
            builtIn("Mint", 0x0A2828, 0x1EA082, 0xC8FADC, 0xFAFAF0, 0x0A2828),
            builtIn("Violet", 0x190A32, 0x6E32BE, 0xF078C8, 0xFFE6F5, 0x190A32));

    private final Path file;
    private final List<NamedPalette> yours = new ArrayList<>();

    public PaletteLibrary(Path directory) {
        this.file = directory.resolve(FILE_NAME);
        reload();
    }

    private static NamedPalette builtIn(String name, int... rgb) {
        Color[] stops = Arrays.stream(rgb).mapToObj(Color::new).toArray(Color[]::new);
        return new NamedPalette(name, new Palette(stops, 16));
    }

    public Path file() {
        return file;
    }

    public List<NamedPalette> builtIn() {
        return BUILT_IN;
    }

    public List<NamedPalette> yours() {
        return List.copyOf(yours);
    }

    /** Re-reads the file; keeps what is usable, ignores the rest. */
    public void reload() {
        yours.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            FileData data = JSON.readValue(file.toFile(), FileData.class);
            if (data == null || data.palettes() == null || data.version() > CURRENT_VERSION) {
                return;
            }
            for (Entry entry : data.palettes()) {
                if (entry == null || !validName(entry.name()) || !validPalette(entry.palette()) || nameTaken(entry.name())) {
                    continue;
                }
                yours.add(new NamedPalette(entry.name().trim(), entry.palette().toPalette()));
            }
        } catch (IOException | RuntimeException ex) {
            System.err.println("Ignoring unreadable " + file + ": " + ex.getMessage());
            yours.clear();
        }
    }

    /**
     * Like {@link #nameOf(Palette)}, but when several entries have exactly this palette (a copy saved
     * without changes, say) the one called {@code preferred} wins, so the entry the user picked stays
     * the active one instead of the first twin in the list.
     */
    public Optional<String> nameOf(Palette palette, String preferred) {
        if (preferred != null) {
            for (NamedPalette candidate : BUILT_IN) {
                if (candidate.name().equals(preferred) && same(candidate.palette(), palette)) {
                    return Optional.of(preferred);
                }
            }
            for (NamedPalette candidate : yours) {
                if (candidate.name().equals(preferred) && same(candidate.palette(), palette)) {
                    return Optional.of(preferred);
                }
            }
        }
        return nameOf(palette);
    }

    /** The name this exact palette is listed under (built in first), if any. */
    public Optional<String> nameOf(Palette palette) {
        for (NamedPalette candidate : BUILT_IN) {
            if (same(candidate.palette(), palette)) {
                return Optional.of(candidate.name());
            }
        }
        for (NamedPalette candidate : yours) {
            if (same(candidate.palette(), palette)) {
                return Optional.of(candidate.name());
            }
        }
        return Optional.empty();
    }

    /** What a name would do if a palette were saved under it. */
    public enum NameStatus {
        /** Empty, or too long. */
        INVALID,
        /** Belongs to a built-in palette, which cannot be replaced. */
        BUILT_IN,
        /** One of your own palettes has it: saving replaces that palette. */
        EXISTING,
        /** Nothing has it: saving creates a new palette. */
        FREE
    }

    public NameStatus statusOf(String requestedName) {
        String name = cleanName(requestedName);
        if (name == null) {
            return NameStatus.INVALID;
        }
        for (NamedPalette candidate : BUILT_IN) {
            if (candidate.name().equalsIgnoreCase(name)) {
                return NameStatus.BUILT_IN;
            }
        }
        return indexOfYours(name) >= 0 ? NameStatus.EXISTING : NameStatus.FREE;
    }

    /**
     * Stores {@code palette} under {@code requestedName}: creates it, or replaces your palette that already
     * has that name. Built-in names and invalid names are refused. Returns the name stored.
     */
    public String save(String requestedName, Palette palette) throws IOException {
        NameStatus status = statusOf(requestedName);
        if (status == NameStatus.INVALID || status == NameStatus.BUILT_IN) {
            throw new IOException(status == NameStatus.BUILT_IN ? "That name belongs to a built-in palette"
                    : "Give the palette a name of 1 to " + MAX_NAME_LENGTH + " characters");
        }
        String name = cleanName(requestedName);
        if (status == NameStatus.EXISTING) {
            yours.set(indexOfYours(name), new NamedPalette(name, palette));
        } else {
            yours.add(new NamedPalette(name, palette));
        }
        save();
        return name;
    }

    public void delete(String name) throws IOException {
        int index = indexOfYours(name);
        if (index >= 0) {
            yours.remove(index);
            save();
        }
    }

    private void save() throws IOException {
        List<Entry> entries = yours.stream()
                .map(p -> new Entry(p.name(), MandelbrotConfig.PaletteData.from(p.palette())))
                .toList();
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(FILE_NAME + ".tmp");
        JSON.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), new FileData(CURRENT_VERSION, entries));
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private int indexOfYours(String name) {
        for (int i = 0; i < yours.size(); i++) {
            if (yours.get(i).name().equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    private boolean nameTaken(String name) {
        for (NamedPalette candidate : BUILT_IN) {
            if (candidate.name().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return indexOfYours(name) >= 0;
    }

    /** The name tidied up (trimmed, inner spaces collapsed), or null if it is empty or too long. */
    private static String cleanName(String name) {
        String tidy = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        return tidy.isEmpty() || tidy.length() > MAX_NAME_LENGTH ? null : tidy;
    }

    private static boolean validName(String name) {
        return name != null && !name.isBlank() && name.trim().length() <= MAX_NAME_LENGTH;
    }

    private static boolean validPalette(MandelbrotConfig.PaletteData data) {
        if (data == null || data.stops() == null || data.stops().size() < 2 || data.stops().size() > 256
                || data.shadesPerSegment() < 1 || data.shadesPerSegment() > 1024) {
            return false;
        }
        for (MandelbrotConfig.RgbColor stop : data.stops()) {
            if (stop == null || !channel(stop.r()) || !channel(stop.g()) || !channel(stop.b())) {
                return false;
            }
        }
        return true;
    }

    private static boolean channel(int value) {
        return value >= 0 && value <= 255;
    }

    private static boolean same(Palette a, Palette b) {
        return a.shadesPerSegment() == b.shadesPerSegment() && Arrays.equals(a.stops(), b.stops());
    }

    /** Lower-cased comparison key, exposed for tests. */
    static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}

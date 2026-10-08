package se.karnerfors.mandelbrot.io;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads and writes {@code state.json}, the "where I was last time" file, in the app's data directory
 * ({@code ~/mandelbrot/} by default). Loading never throws: a missing, unreadable, corrupt or too-new
 * file simply means a normal first start. Saving writes a temporary file and renames it over the real
 * one, so a crash mid-write can't leave a half-written state file behind.
 */
public final class SessionStore {

    /** {@code -Dmandelbrot.home=/some/dir} moves the data directory (used for testing). */
    public static final String HOME_PROPERTY = "mandelbrot.home";

    private static final String FILE_NAME = "state.json";

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true);

    private final Path file;

    public SessionStore(Path directory) {
        this.file = directory.resolve(FILE_NAME);
    }

    /** The data directory: {@code -Dmandelbrot.home} if set, otherwise {@code ~/mandelbrot}. */
    public static Path defaultDirectory() {
        String override = System.getProperty(HOME_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("user.home"), "mandelbrot");
    }

    public Path file() {
        return file;
    }

    /** The remembered session, or empty if there is none or it cannot be trusted. */
    public Optional<SessionState> load() {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            SessionState state = JSON.readValue(file.toFile(), SessionState.class);
            if (state == null || state.version() > SessionState.CURRENT_VERSION || !state.hasUsableView()) {
                return Optional.empty();
            }
            return Optional.of(state);
        } catch (IOException | RuntimeException ex) {
            System.err.println("Ignoring unreadable " + file + ": " + ex.getMessage());
            return Optional.empty();
        }
    }

    public void save(SessionState state) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(FILE_NAME + ".tmp");
        JSON.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), state);
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

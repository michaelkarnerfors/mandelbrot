package se.karnerfors.mandelbrot.app;

import java.io.IOException;
import java.util.function.Supplier;

import javax.swing.Timer;

import se.karnerfors.mandelbrot.io.SessionState;
import se.karnerfors.mandelbrot.io.SessionStore;

/**
 * Saves the session shortly after things stop changing. Every pan, zoom, palette or window change calls
 * {@link #markDirty()}; the write happens once the user has been quiet for a moment, so dragging around
 * does not hit the disk on every event. {@link #flush()} writes immediately (used when the window closes).
 * Must be used from the Swing event thread.
 */
final class SessionRecorder {

    private static final int QUIET_PERIOD_MS = 800;

    private final SessionStore store;
    private final Supplier<SessionState> capture;
    private final Timer timer;

    SessionRecorder(SessionStore store, Supplier<SessionState> capture) {
        this.store = store;
        this.capture = capture;
        this.timer = new Timer(QUIET_PERIOD_MS, _ -> flush());
        this.timer.setRepeats(false);
    }

    void markDirty() {
        timer.restart();
    }

    void flush() {
        timer.stop();
        try {
            store.save(capture.get());
        } catch (IOException | RuntimeException ex) {
            // Remembering the session is a convenience; never let it get in the way of using the app.
            System.err.println("Could not save " + store.file() + ": " + ex.getMessage());
        }
    }
}

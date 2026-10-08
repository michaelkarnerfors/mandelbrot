package se.karnerfors.mandelbrot.render;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.SwingUtilities;

import se.karnerfors.mandelbrot.io.MandelbrotConfig;
import se.karnerfors.mandelbrot.io.MandelbrotConfigIO;
import se.karnerfors.mandelbrot.palette.Palette;

/**
 * Pictures to draw to files, one after the other, in the background and at low priority so the live view
 * stays usable. Add jobs, cancel or dismiss them, retry failed ones. All methods are for the UI thread; the
 * change listener is also called on the UI thread.
 *
 * <p>Jobs only live in memory: if the program closes, unfinished jobs are gone.
 */
public final class RenderQueue {

    private static final int[] LIGHTER = { 9, 4, 2, 1 };

    private final List<RenderJob> jobs = new ArrayList<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final ExecutorService runner = Executors.newSingleThreadExecutor(daemon("render-queue", Thread.MIN_PRIORITY));
    private final int tileThreads;
    private final List<Runnable> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private boolean changePending;
    private volatile RenderJob running;

    public RenderQueue() {
        this(Math.max(1, Runtime.getRuntime().availableProcessors()));
    }

    public RenderQueue(int tileThreads) {
        this.tileThreads = Math.max(1, tileThreads);
    }

    /** Called (on the UI thread) whenever a job is added, removed, changes state, or makes progress. */
    public void addChangeListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeChangeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }

    public List<RenderJob> jobs() {
        return List.copyOf(jobs);
    }

    public int count(RenderJob.State state) {
        int n = 0;
        for (RenderJob job : jobs) {
            if (job.state() == state) {
                n++;
            }
        }
        return n;
    }

    public boolean hasActiveJobs() {
        for (RenderJob job : jobs) {
            if (job.isActive()) {
                return true;
            }
        }
        return false;
    }

    /** True if some job in the queue (in any state) writes to this file. */
    public boolean usesFile(File file) {
        for (RenderJob job : jobs) {
            if (job.file().getAbsoluteFile().equals(file.getAbsoluteFile())) {
                return true;
            }
        }
        return false;
    }

    public RenderJob add(MandelbrotConfig view, String paletteLabel, double framingScale, int width, int height,
            int supersample, File file, BufferedImage thumbnail) {
        RenderJob job = new RenderJob(nextId.getAndIncrement(), view, paletteLabel, framingScale, width, height,
                supersample, file, thumbnail);
        jobs.add(job);
        fireChanged();
        pump(job);
        return job;
    }

    /** A new job like a failed one, at another supersample factor (0 keeps the same), put last in the queue. */
    public RenderJob retry(RenderJob failed, int supersample) {
        remove(failed);
        return add(failed.view(), failed.paletteLabel(), failed.framingScale(), failed.width(), failed.height(),
                supersample > 0 ? supersample : failed.supersample(), failed.file(), failed.thumbnail());
    }

    /** Stops a running job or removes a waiting one. Finished and failed jobs are dismissed with {@link #remove}. */
    public void cancel(RenderJob job) {
        if (job.state() == RenderJob.State.WAITING) {
            job.requestCancel();
            jobs.remove(job);
        } else if (job.state() == RenderJob.State.RENDERING) {
            job.requestCancel(); // the runner removes it when the tiles have stopped
        } else {
            jobs.remove(job);
        }
        fireChanged();
    }

    public void remove(RenderJob job) {
        if (job.isActive()) {
            cancel(job);
            return;
        }
        jobs.remove(job);
        fireChanged();
    }

    public void clearFinished() {
        jobs.removeIf(job -> !job.isActive());
        fireChanged();
    }

    /** Stops everything; for shutdown. */
    public void cancelAll() {
        for (RenderJob job : new ArrayList<>(jobs)) {
            if (job.isActive()) {
                job.requestCancel();
            }
        }
        jobs.removeIf(job -> job.state() == RenderJob.State.WAITING);
        runner.shutdown();
    }

    // --- running -------------------------------------------------------------------------------------

    private void pump(RenderJob job) {
        runner.execute(() -> runJob(job));
    }

    private void runJob(RenderJob job) {
        if (job.cancelRequested()) {
            return;
        }
        running = job;
        job.start();
        fireChanged();
        ExecutorService tiles = Executors.newFixedThreadPool(tileThreads, daemon("render-queue-tile", Thread.MIN_PRIORITY));
        try {
            se.karnerfors.mandelbrot.math.Viewport target = job.targetViewport();
            RenderSettings settings = job.view().applyTo(RenderSettings.defaults()).withSupersampleFactor(job.supersample());
            Palette palette = job.view().toPalette();
            int total = Math.max(1, ImageExporter.tileCount(target, job.supersample()));
            AtomicInteger done = new AtomicInteger();
            BufferedImage image = ImageExporter.renderImage(target, settings, palette, tiles, job::cancelRequested, () -> {
                job.progress(done.incrementAndGet() / (double) total);
                fireChanged();
            });
            if (job.cancelRequested()) {
                SwingUtilities.invokeLater(() -> {
                    jobs.remove(job);
                    fireChanged();
                });
                return;
            }
            write(job, image, MandelbrotConfig.capture(
                    new se.karnerfors.mandelbrot.math.Viewport(
                            se.karnerfors.mandelbrot.math.Complex.fromCartesian(job.view().viewport().real(),
                                    job.view().viewport().imaginary()),
                            job.view().viewport().zoom(), job.width(), job.height()),
                    palette, settings));
            job.finish();
        } catch (OutOfMemoryError ex) {
            job.fail("Out of memory at " + job.supersample() + "x", lighter(job.supersample()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            job.fail("Interrupted", 0);
        } catch (java.nio.file.FileSystemException ex) {
            String reason = ex.getReason() != null ? ex.getReason()
                    : ex instanceof java.nio.file.AccessDeniedException ? "access denied" : ex.getClass().getSimpleName();
            job.fail("Could not write " + job.file().getName() + ": " + reason, 0);
        } catch (IOException | RuntimeException ex) {
            job.fail(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(), 0);
        } finally {
            tiles.shutdownNow();
            running = null;
            fireChanged();
        }
    }

    private static void write(RenderJob job, BufferedImage image, MandelbrotConfig config) throws IOException {
        File target = job.file();
        File parent = target.getAbsoluteFile().getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        File partial = new File(target.getPath() + ".part");
        try {
            MandelbrotConfigIO.writePng(partial, image, config);
            try {
                Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(partial.toPath());
        }
    }

    /** The next lighter factor below {@code factor}, or 0 when it is already 1x. */
    static int lighter(int factor) {
        for (int candidate : LIGHTER) {
            if (candidate < factor) {
                return candidate;
            }
        }
        return 0;
    }

    /** Coalesces bursts of tile completions into one listener call per UI turn. */
    private void fireChanged() {
        if (SwingUtilities.isEventDispatchThread()) {
            notifyListeners();
            return;
        }
        synchronized (this) {
            if (changePending) {
                return;
            }
            changePending = true;
        }
        SwingUtilities.invokeLater(() -> {
            synchronized (this) {
                changePending = false;
            }
            notifyListeners();
        });
    }

    private static ThreadFactory daemon(String name, int priority) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            thread.setPriority(priority);
            return thread;
        };
    }

    /** "1 m 40 s", "24 s", "0.8 s": for ETAs and durations in the UI. */
    public static String formatDuration(long millis) {
        if (millis < 0) {
            return "";
        }
        if (millis < 10_000) {
            return String.format(Locale.ROOT, "%.1f s", millis / 1000.0);
        }
        long seconds = Math.round(millis / 1000.0);
        if (seconds < 60) {
            return seconds + " s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + " m " + (seconds % 60) + " s";
        }
        return (minutes / 60) + " h " + (minutes % 60) + " m";
    }
}

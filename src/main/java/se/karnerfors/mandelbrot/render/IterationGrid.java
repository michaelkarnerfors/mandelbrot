package se.karnerfors.mandelbrot.render;

/**
 * A width x height grid of raw escape-time results, backed by primitive arrays
 * rather than a 2D array of objects. At 4K resolution that's the difference
 * between ~8 million small object allocations and four flat arrays -- it also
 * means multiple threads can later fill disjoint index ranges of the same
 * grid without any synchronization (Task 4).
 */
public final class IterationGrid {

    private final int width;
    private final int height;
    private final boolean[] escaped;
    private final int[] iterations;
    private final double[] smoothIterationCount;
    private final double[] nu;

    public IterationGrid(int width, int height) {
        this.width = width;
        this.height = height;
        int size = width * height;
        this.escaped = new boolean[size];
        this.iterations = new int[size];
        this.smoothIterationCount = new double[size];
        this.nu = new double[size];
    }

    /** Bytes the backing arrays take for a grid of this size (1 + 4 + 8 + 8 per cell), for memory warnings. */
    public static long estimatedBytes(long width, long height) {
        return width * height * (1 + Integer.BYTES + 2L * Double.BYTES);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public void set(int x, int y, EscapeResult result) {
        int index = index(x, y);
        escaped[index] = result.escaped();
        iterations[index] = result.iterations();
        smoothIterationCount[index] = result.smoothIterationCount();
        nu[index] = result.nu();
    }

    /** Copies cell ({@code fromX}, {@code fromY}) of {@code from} into cell ({@code x}, {@code y}) of this grid. */
    void copyCell(IterationGrid from, int fromX, int fromY, int x, int y) {
        int source = from.index(fromX, fromY);
        int index = index(x, y);
        escaped[index] = from.escaped[source];
        iterations[index] = from.iterations[source];
        smoothIterationCount[index] = from.smoothIterationCount[source];
        nu[index] = from.nu[source];
    }

    public boolean isEscaped(int x, int y) {
        return escaped[index(x, y)];
    }

    public int iterationsAt(int x, int y) {
        return iterations[index(x, y)];
    }

    public double smoothIterationCountAt(int x, int y) {
        return smoothIterationCount[index(x, y)];
    }

    public double nuAt(int x, int y) {
        return nu[index(x, y)];
    }

    private int index(int x, int y) {
        return y * width + x;
    }
}

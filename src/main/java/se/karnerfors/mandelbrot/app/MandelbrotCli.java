package se.karnerfors.mandelbrot.app;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import se.karnerfors.mandelbrot.io.Bookmark;
import se.karnerfors.mandelbrot.io.MandelbrotConfig;
import se.karnerfors.mandelbrot.io.MandelbrotConfig.FractalData;
import se.karnerfors.mandelbrot.io.MandelbrotConfig.ViewportData;
import se.karnerfors.mandelbrot.io.MandelbrotConfigIO;
import se.karnerfors.mandelbrot.math.Complex;
import se.karnerfors.mandelbrot.math.Viewport;
import se.karnerfors.mandelbrot.palette.Palette;
import se.karnerfors.mandelbrot.render.ImageExporter;
import se.karnerfors.mandelbrot.render.RenderAlgorithm;
import se.karnerfors.mandelbrot.render.RenderSettings;

/**
 * Renders a picture without the window: from a config (a bookmark exported as {@code .json}, or a {@code .png}
 * the app made, which carries its view inside) or, with no input, a built-in deep-zoom demo.
 *
 * <p>The picture frames the same area the app's Render dialog would: a config's zoom is in terms of a window (by
 * default the app's 1100 x 700 canvas, see {@code --window}), and the output is scaled to contain it. Memory is
 * bounded by the output size, however high the supersampling.
 */
public final class MandelbrotCli {

    private static final int DEFAULT_WIDTH = 1920;
    private static final int DEFAULT_HEIGHT = 1080;
    private static final int DEMO_WIDTH = 3840;
    private static final int DEMO_HEIGHT = 2160;
    private static final int WINDOW_WIDTH = 1100;
    private static final int WINDOW_HEIGHT = 700;
    private static final int MAX_SIDE = 16384;
    private static final int DEFAULT_SAMPLES = 2;

    private static final Pattern SIZE = Pattern.compile("(\\d+)\\s*[xX×]\\s*(\\d+)");
    private static final Pattern NUMBER = Pattern.compile("[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?");
    private static final Pattern COMPLEX = Pattern.compile("(" + NUMBER.pattern() + ")\\s*(?:,|\\s)\\s*(" + NUMBER.pattern() + ")"
            + "|(" + NUMBER.pattern() + ")\\s*([+-])\\s*((?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?)\\s*i");

    static final String USAGE = """
            Usage: MandelbrotCli [input] [options]

            Renders a picture to a PNG file.

            input            a bookmark or view file (.json), or a .png made by this app. This can be an
                             exported bookmark, or bookmarks.json itself (then pick one with --bookmark).
                             With no input, a deep-zoom demo is rendered.

            Options:
              -o, --output FILE    where to write the PNG (default: the input's name with .png, or saved.png)
              --size WxH           picture size in pixels (default 1920x1080; the demo is 3840x2160)
              --samples N          supersampling per axis: 1, 2, 4 or 9 (default 2)
              --bookmark NAME|N    which bookmark to use when the file holds several (a name, or 1 for the first)
              --window WxH         the window size the view's zoom refers to (default 1100x700). The picture
                                   frames the same area that window showed.
              --fractal NAME       mandelbrot or julia (default: from the input)
              --c RE,IM            the Julia constant, e.g. -0.8,0.156 or -0.8+0.156i
              --re X --im Y        centre of the view
              --zoom Z             zoom, in terms of --window
              --threads N          worker threads (default: all processors)
              -s, --silent         print nothing on success (errors still go to stderr); use the exit status
              -h, --help           this text

            Exit status: 0 if the picture was written, 1 if the render failed, 2 for a command-line mistake.
            In a batch: for f in *.json; do java ... MandelbrotCli "$f" -s || echo "failed: $f"; done
            """;

    /** A mistake on the command line: reported with the usage hint, exit status 2. */
    static final class UsageException extends Exception {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }

    /** What the command line asked for. Null means "not given: use the input's own value". */
    record Options(File input, String bookmark, File output, int[] size, Integer samples, int[] window, RenderAlgorithm fractal,
            Complex c, Double re, Double im, Double zoom, Integer threads, boolean silent, boolean help) {
    }

    private MandelbrotCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /** The whole program, with its streams passed in so it can be driven from tests. Returns the exit status. */
    static int run(String[] args, PrintStream out, PrintStream err) {
        Options options;
        try {
            options = parse(args);
        } catch (UsageException ex) {
            err.println("mandelbrot: " + ex.getMessage());
            err.println("Try --help for the options.");
            return 2;
        }
        if (options.help()) {
            out.print(USAGE);
            return 0;
        }
        PrintStream normal = options.silent() ? new PrintStream(java.io.OutputStream.nullOutputStream()) : out;
        try {
            return render(options, normal, err);
        } catch (UsageException ex) {
            err.println("mandelbrot: " + ex.getMessage());
            return 2;
        } catch (IOException ex) {
            err.println("mandelbrot: " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
            return 1;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            err.println("mandelbrot: interrupted");
            return 1;
        } catch (RuntimeException ex) {
            err.println("mandelbrot: " + ex);
            return 1;
        } catch (OutOfMemoryError ex) {
            err.println("mandelbrot: out of memory. Try a smaller --size or lower --samples, "
                    + "or start Java with a larger -Xmx.");
            return 1;
        }
    }

    static Options parse(String[] args) throws UsageException {
        File input = null;
        String bookmark = null;
        File output = null;
        int[] size = null;
        Integer samples = null;
        int[] window = null;
        RenderAlgorithm fractal = null;
        Complex c = null;
        Double re = null;
        Double im = null;
        Double zoom = null;
        Integer threads = null;
        boolean silent = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-h", "--help" -> {
                    return new Options(null, null, null, null, null, null, null, null, null, null, null, null, false, true);
                }
                case "-o", "--output" -> output = new File(value(args, ++i, arg));
                case "-s", "--silent" -> silent = true;
                case "--bookmark" -> bookmark = value(args, ++i, arg);
                case "--size" -> size = size(value(args, ++i, arg), arg);
                case "--window" -> window = size(value(args, ++i, arg), arg);
                case "--samples" -> {
                    samples = integer(value(args, ++i, arg), arg);
                    if (samples != 1 && samples != 2 && samples != 4 && samples != 9) {
                        throw new UsageException("--samples must be 1, 2, 4 or 9, not " + samples);
                    }
                }
                case "--threads" -> {
                    threads = integer(value(args, ++i, arg), arg);
                    if (threads < 1) {
                        throw new UsageException("--threads must be at least 1");
                    }
                }
                case "--fractal" -> fractal = fractal(value(args, ++i, arg));
                case "--c" -> c = complex(value(args, ++i, arg));
                case "--re" -> re = number(value(args, ++i, arg), arg);
                case "--im" -> im = number(value(args, ++i, arg), arg);
                case "--zoom" -> {
                    zoom = number(value(args, ++i, arg), arg);
                    if (zoom <= 0) {
                        throw new UsageException("--zoom must be positive");
                    }
                }
                default -> {
                    if (arg.startsWith("-") && arg.length() > 1) {
                        throw new UsageException("unknown option " + arg);
                    }
                    if (input != null) {
                        throw new UsageException("only one input file is allowed (got " + input.getName() + " and "
                                + arg + ")");
                    }
                    input = new File(arg);
                }
            }
        }
        return new Options(input, bookmark, output, size, samples, window, fractal, c, re, im, zoom, threads, silent, false);
    }

    private static String value(String[] args, int index, String option) throws UsageException {
        if (index >= args.length) {
            throw new UsageException(option + " needs a value");
        }
        return args[index];
    }

    private static int integer(String text, String option) throws UsageException {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException ex) {
            throw new UsageException(option + " needs a whole number, not \"" + text + "\"");
        }
    }

    private static double number(String text, String option) throws UsageException {
        try {
            double v = Double.parseDouble(text.trim());
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                throw new NumberFormatException();
            }
            return v;
        } catch (NumberFormatException ex) {
            throw new UsageException(option + " needs a number, not \"" + text + "\"");
        }
    }

    static int[] size(String text, String option) throws UsageException {
        Matcher m = SIZE.matcher(text.trim());
        if (!m.matches()) {
            throw new UsageException(option + " needs a size like 1920x1080, not \"" + text + "\"");
        }
        int w;
        int h;
        try {
            w = Integer.parseInt(m.group(1));
            h = Integer.parseInt(m.group(2));
        } catch (NumberFormatException ex) {
            throw new UsageException(option + " is too large: \"" + text + "\"");
        }
        if (w < 1 || h < 1 || w > MAX_SIDE || h > MAX_SIDE) {
            throw new UsageException(option + " must be between 1x1 and " + MAX_SIDE + "x" + MAX_SIDE);
        }
        return new int[] { w, h };
    }

    private static RenderAlgorithm fractal(String text) throws UsageException {
        return switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "mandelbrot" -> RenderAlgorithm.MANDELBROT;
            case "julia" -> RenderAlgorithm.JULIA;
            default -> throw new UsageException("--fractal must be mandelbrot or julia, not \"" + text + "\"");
        };
    }

    static Complex complex(String text) throws UsageException {
        String t = text.trim().replace('−', '-');
        Matcher m = COMPLEX.matcher(t);
        if (!m.matches()) {
            throw new UsageException("--c needs RE,IM or RE+IMi, e.g. -0.8,0.156, not \"" + text + "\"");
        }
        try {
            if (m.group(1) != null) {
                return Complex.fromCartesian(Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)));
            }
            double im = Double.parseDouble(m.group(5));
            return Complex.fromCartesian(Double.parseDouble(m.group(3)), "-".equals(m.group(4)) ? -im : im);
        } catch (NumberFormatException ex) {
            throw new UsageException("--c needs RE,IM or RE+IMi, not \"" + text + "\"");
        }
    }

    private static int render(Options o, PrintStream out, PrintStream err)
            throws UsageException, IOException, InterruptedException {
        MandelbrotConfig view = o.input() != null ? load(o.input(), o.bookmark()) : demo();
        int[] window = o.window() != null ? o.window() : new int[] { WINDOW_WIDTH, WINDOW_HEIGHT };
        int[] size = o.size() != null ? o.size()
                : o.input() != null ? new int[] { DEFAULT_WIDTH, DEFAULT_HEIGHT } : new int[] { DEMO_WIDTH, DEMO_HEIGHT };
        int samples = o.samples() != null ? o.samples() : DEFAULT_SAMPLES;
        File output = o.output() != null ? o.output() : defaultOutput(o.input());
        if (o.input() != null && sameFile(o.input(), output)) {
            throw new UsageException("the output would overwrite the input (" + o.input().getName()
                    + "). Choose another with -o.");
        }

        ViewportData v = view.viewport();
        FractalData f = view.fractal();
        RenderAlgorithm algorithm = o.fractal() != null ? o.fractal() : f.algorithm();
        Complex c = o.c() != null ? o.c() : Complex.fromCartesian(f.cReal(), f.cImaginary());
        double re = o.re() != null ? o.re() : v.real();
        double im = o.im() != null ? o.im() : v.imaginary();
        double zoom = o.zoom() != null ? o.zoom() : v.zoom();

        // Same framing as the Render dialog: the picture contains what a window of that size showed.
        double scale = Math.min(size[0] / (double) window[0], size[1] / (double) window[1]);
        Complex centre = Complex.fromCartesian(re, im);
        Viewport target = new Viewport(centre, zoom * scale, size[0], size[1]);
        RenderSettings settings = RenderSettings.defaults().withAlgorithm(algorithm).withC(c)
                .withSupersampleFactor(samples);
        Palette palette = view.toPalette();
        // The view as a window would have it (as the render queue embeds it), so the picture can be re-rendered.
        MandelbrotConfig embedded = MandelbrotConfig.capture(new Viewport(centre, zoom, size[0], size[1]), palette,
                settings);

        int threads = o.threads() != null ? o.threads() : Math.max(1, Runtime.getRuntime().availableProcessors());
        out.println("Rendering " + size[0] + "x" + size[1] + " at " + samples + "x, " + algorithm.toString().toLowerCase(Locale.ROOT)
                + ", " + threads + " thread" + (threads == 1 ? "" : "s") + "...");
        int total = Math.max(1, ImageExporter.tileCount(target, samples));
        AtomicInteger done = new AtomicInteger();
        AtomicInteger reported = new AtomicInteger();
        long start = System.nanoTime();
        BufferedImage image;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            image = ImageExporter.renderImage(target, settings, palette, executor, () -> false, () -> {
                int percent = done.incrementAndGet() * 100 / total / 10 * 10;
                int last = reported.get();
                if (percent > last && reported.compareAndSet(last, percent) && percent < 100) {
                    out.println("  " + percent + "%");
                }
            });
        } finally {
            executor.shutdownNow();
        }
        write(output, image, embedded);
        long millis = (System.nanoTime() - start) / 1_000_000;
        out.println("Wrote " + output.getPath() + " in " + String.format(Locale.ROOT, "%.1f", millis / 1000.0) + " s.");
        return 0;
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static MandelbrotConfig load(File file, String bookmark) throws UsageException, IOException {
        if (!file.isFile()) {
            throw new IOException("no such file: " + file.getPath());
        }
        MandelbrotConfig config;
        try {
            config = file.getName().toLowerCase(Locale.ROOT).endsWith(".png") ? MandelbrotConfigIO.load(file)
                    : fromJson(file, bookmark);
        } catch (IOException ex) {
            String why = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage().lines().findFirst().orElse("");
            throw new IOException("could not read a view from " + file.getName() + ": " + why, ex);
        }
        if (config == null || !config.isUsable()) {
            throw new IOException(file.getName() + " does not hold a usable view (missing or invalid numbers or palette)");
        }
        return config;
    }

    /** A JSON file is either a single view (the old Save format) or bookmarks (an exported one, or bookmarks.json). */
    private static MandelbrotConfig fromJson(File file, String selector) throws UsageException, IOException {
        JsonNode root = JSON.readTree(file);
        if (root == null || !root.has("bookmarks")) {
            return JSON.treeToValue(root, MandelbrotConfig.class);
        }
        List<Bookmark> usable = new ArrayList<>();
        for (JsonNode node : root.get("bookmarks")) {
            Bookmark b = JSON.treeToValue(node, Bookmark.class);
            if (b != null && b.view() != null && b.view().isUsable()) {
                usable.add(b);
            }
        }
        if (usable.isEmpty()) {
            throw new IOException("no usable bookmark in the file");
        }
        if (selector != null) {
            for (int i = 0; i < usable.size(); i++) {
                if (selector.equalsIgnoreCase(usable.get(i).name()) || selector.equals(Integer.toString(i + 1))) {
                    return usable.get(i).view();
                }
            }
            throw new UsageException("no bookmark \"" + selector + "\" in " + file.getName() + ". It has:\n" + names(usable));
        }
        if (usable.size() > 1) {
            throw new UsageException(file.getName() + " holds " + usable.size()
                    + " bookmarks. Pick one with --bookmark NAME (or its number):\n" + names(usable));
        }
        return usable.get(0).view();
    }

    private static String names(List<Bookmark> bookmarks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bookmarks.size(); i++) {
            sb.append("  ").append(i + 1).append("  ").append(bookmarks.get(i).name()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** The built-in demo: a deep zoom at 3840x2160 with the classic palette, in terms of the default window. */
    private static MandelbrotConfig demo() {
        double scale = Math.min(DEMO_WIDTH / (double) WINDOW_WIDTH, DEMO_HEIGHT / (double) WINDOW_HEIGHT);
        Viewport view = new Viewport(Complex.fromCartesian(-0.7602490234375D, -0.080498046875D), 6553600.0D / scale,
                WINDOW_WIDTH, WINDOW_HEIGHT);
        return MandelbrotConfig.capture(view, Palette.classicRedWhiteBlack(), RenderSettings.defaults());
    }

    private static File defaultOutput(File input) {
        if (input == null) {
            return new File("saved.png");
        }
        String name = input.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        File parent = input.getAbsoluteFile().getParentFile();
        File candidate = new File(parent, stem + ".png");
        return sameFile(input, candidate) ? new File(parent, stem + "-render.png") : candidate;
    }

    private static boolean sameFile(File a, File b) {
        return a.getAbsoluteFile().toPath().normalize().equals(b.getAbsoluteFile().toPath().normalize());
    }

    /** Writes through a temporary file, so an interrupted run never leaves half a picture under the real name. */
    private static void write(File target, BufferedImage image, MandelbrotConfig config) throws IOException {
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
        } catch (IOException ex) {
            throw new IOException("could not write " + target.getPath() + ": " + ex.getMessage(), ex);
        } finally {
            Files.deleteIfExists(partial.toPath());
        }
    }
}

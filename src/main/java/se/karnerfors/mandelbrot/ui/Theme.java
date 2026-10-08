package se.karnerfors.mandelbrot.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.GraphicsEnvironment;
import java.awt.font.TextAttribute;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;

/**
 * Look and feel: FlatLaf as the base, plus the app's own colour tokens and the bundled
 * IBM Plex fonts. Our custom-painted components ({@link AppButton}, {@link SegmentedControl},
 * the status chip) read every colour through {@link #color(Token)} at paint time, so
 * switching between light and dark needs no per-component work beyond a repaint.
 */
public final class Theme {

    public enum Mode {
        LIGHT, DARK;

        public Mode other() {
            return this == LIGHT ? DARK : LIGHT;
        }
    }

    /** The colour tokens from the design's token sheet. */
    public enum Token {
        BAR("#F2F3F5", "#1F2227"),
        SURFACE("#FFFFFF", "#2A2D34"),
        SURFACE_2("#F7F8FA", "#24272D"),
        BORDER("#D6D9DF", "#40444D"),
        BORDER_SOFT("#E6E8EC", "#33373F"),
        TEXT("#1A1D23", "#E8EAEE"),
        MUTED("#596070", "#A3AAB7"),
        ACCENT("#2457C5", "#7BA2F5"),
        ACCENT_SOFT("#E4ECFB", "#2B3A5E"),
        ON_ACCENT("#FFFFFF", "#10162A"),
        HOVER("#E6E8EC", "#31353D"),
        TRACK("#E4E6EA", "#16181C"),
        OK("#1F7A4D", "#5BD08F"),
        WARN("#9A5B00", "#F0B35A"),
        ERROR("#B3261E", "#F28B82");

        private final Color light;
        private final Color dark;

        Token(String light, String dark) {
            this.light = Color.decode(light);
            this.dark = Color.decode(dark);
        }
    }

    private static final String[] FONT_FILES = {
            "IBMPlexSans-Regular", "IBMPlexSans-Medium", "IBMPlexSans-SemiBold",
            "IBMPlexMono-Regular", "IBMPlexMono-Medium" };

    private static Mode mode = Mode.LIGHT;
    private static Font sansRegular;
    private static Font sansMedium;
    private static Font sansSemiBold;
    private static Font monoRegular;
    private static Font monoMedium;

    private Theme() {
    }

    /** Installs FlatLaf in the given mode. Call once on the EDT before creating any window. */
    public static void install(Mode initial) {
        mode = initial;
        loadFonts();
        applyLookAndFeel();
    }

    /** Switches between light and dark at runtime and refreshes every open window. */
    public static void toggle() {
        mode = mode.other();
        applyLookAndFeel();
        FlatLaf.updateUI();
    }

    public static Mode mode() {
        return mode;
    }

    public static boolean isDark() {
        return mode == Mode.DARK;
    }

    /** The current colour for a token. */
    public static Color color(Token token) {
        return mode == Mode.DARK ? token.dark : token.light;
    }

    /** {@code color} with its alpha replaced by {@code alpha} (0 to 255). */
    public static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    public static Font sans(float size) {
        return sansRegular.deriveFont(size);
    }

    public static Font sansMedium(float size) {
        return sansMedium.deriveFont(size);
    }

    public static Font sansSemiBold(float size) {
        return sansSemiBold.deriveFont(size);
    }

    public static Font mono(float size) {
        return monoRegular.deriveFont(size);
    }

    public static Font monoMedium(float size) {
        return monoMedium.deriveFont(size);
    }

    /** Small caps-style label font: 11 px semi-bold with +6% tracking. */
    public static Font groupLabelFont() {
        Map<TextAttribute, Object> attributes = new HashMap<>();
        attributes.put(TextAttribute.TRACKING, 0.06f);
        return sansSemiBold.deriveFont(11f).deriveFont(attributes);
    }

    private static void applyLookAndFeel() {
        Map<String, String> extra = new HashMap<>();
        extra.put("@accentColor", hex(color(Token.ACCENT)));
        extra.put("Component.arc", "6");
        extra.put("Button.arc", "6");
        extra.put("TextComponent.arc", "6");
        extra.put("Component.focusWidth", "1");
        extra.put("ScrollBar.thumbArc", "999");
        extra.put("ScrollBar.width", "10");
        FlatLaf.setGlobalExtraDefaults(extra);

        if (mode == Mode.DARK) {
            FlatDarkLaf.setup();
        } else {
            FlatLightLaf.setup();
        }

        // Surfaces that FlatLaf paints for us should match our own tokens.
        UIManager.put("Panel.background", new ColorUIResource(color(Token.BAR)));
        UIManager.put("ToolTip.background", new ColorUIResource(color(Token.TEXT)));
        UIManager.put("ToolTip.foreground", new ColorUIResource(color(Token.BAR)));
        UIManager.put("ToolTip.border", javax.swing.BorderFactory.createEmptyBorder(4, 8, 4, 8));
        UIManager.put("defaultFont", new FontUIResource(sansRegular.deriveFont(13f)));
        UIManager.put("Button.font", new FontUIResource(sansMedium.deriveFont(13f)));
        UIManager.put("TextField.font", new FontUIResource(monoRegular.deriveFont(13f)));
        UIManager.put("FormattedTextField.font", new FontUIResource(monoRegular.deriveFont(13f)));
    }

    private static String hex(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    private static void loadFonts() {
        if (sansRegular != null) {
            return;
        }
        Font[] loaded = new Font[FONT_FILES.length];
        for (int i = 0; i < FONT_FILES.length; i++) {
            loaded[i] = loadFont("/fonts/" + FONT_FILES[i] + ".ttf");
        }
        sansRegular = orFallback(loaded[0], Font.SANS_SERIF, Font.PLAIN);
        sansMedium = orFallback(loaded[1], Font.SANS_SERIF, Font.BOLD);
        sansSemiBold = orFallback(loaded[2], Font.SANS_SERIF, Font.BOLD);
        monoRegular = orFallback(loaded[3], Font.MONOSPACED, Font.PLAIN);
        monoMedium = orFallback(loaded[4], Font.MONOSPACED, Font.BOLD);
    }

    private static Font loadFont(String resource) {
        try (InputStream in = Theme.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            Font font = Font.createFont(Font.TRUETYPE_FONT, in);
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
            return font;
        } catch (IOException | FontFormatException ex) {
            return null;
        }
    }

    private static Font orFallback(Font font, String logicalName, int style) {
        return font != null ? font : new Font(logicalName, style, 13);
    }
}

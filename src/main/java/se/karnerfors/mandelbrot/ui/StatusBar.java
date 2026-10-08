package se.karnerfors.mandelbrot.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.Locale;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

import se.karnerfors.mandelbrot.math.Complex;
import se.karnerfors.mandelbrot.ui.Theme.Token;

/**
 * The bar under the canvas: interaction hint, the coordinate under the cursor, the zoom,
 * Go to..., the render status chip and the light/dark switch.
 */
public final class StatusBar extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final int HEIGHT = 28;
    private static final int HIDE_HINT_BELOW = 880;

    public final AppButton goTo = new AppButton("Go to…", null, AppButton.Style.OUTLINE, true);
    public final AppButton themeToggle;
    private final StatusChip chip = new StatusChip();

    private final ThemedLabel hint = new ThemedLabel("Left-drag: zoom · Right-drag: pan · Wheel: zoom", Token.MUTED,
            () -> Theme.sans(12f));
    private final ThemedLabel reValue = value("—", "−0.000000");
    private final ThemedLabel imValue = value("—", "−0.000000");
    private final ThemedLabel zoomValue = value("", "0.000e+00");

    public StatusBar() {
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        setBorder(new EmptyBorder(0, 12, 0, 12));
        setOpaque(true);

        themeToggle = AppButton.iconButton(Theme.isDark() ? Icons.Glyph.SUN : Icons.Glyph.MOON,
                Theme.isDark() ? "Switch to light theme" : "Switch to dark theme", AppButton.Style.GHOST, true);
        goTo.setToolTipText("Centre on exact coordinates");

        add(center(hint));
        add(Box.createHorizontalStrut(16));
        add(center(label("Re")));
        add(Box.createHorizontalStrut(5));
        add(center(reValue));
        add(Box.createHorizontalStrut(10));
        add(center(label("Im")));
        add(Box.createHorizontalStrut(5));
        add(center(imValue));
        add(Box.createHorizontalStrut(16));
        add(center(label("Zoom")));
        add(Box.createHorizontalStrut(5));
        add(center(zoomValue));
        add(Box.createHorizontalStrut(12));
        add(center(goTo));
        add(Box.createHorizontalGlue());
        add(center(chip));
        add(Box.createHorizontalStrut(8));
        add(center(themeToggle));

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                hint.setVisible(getWidth() >= HIDE_HINT_BELOW);
            }
        });
    }

    /** The complex coordinate under the pointer, or null when the pointer is outside the canvas. */
    public void setCursorPoint(Complex point) {
        if (point == null) {
            reValue.setText("—");
            imValue.setText("—");
        } else {
            reValue.setText(format(point.real));
            imValue.setText(format(point.imaginary));
        }
    }

    public void setZoom(double zoom) {
        zoomValue.setText(String.format(Locale.ROOT, "%.3e", zoom));
    }

    public void showStatus(RenderStatus status) {
        chip.showStatus(status);
    }

    /** Call after {@link Theme#toggle()} so the switch shows the right icon and tooltip. */
    public void syncThemeToggle() {
        themeToggle.setGlyph(Theme.isDark() ? Icons.Glyph.SUN : Icons.Glyph.MOON);
        themeToggle.setToolTipText(Theme.isDark() ? "Switch to light theme" : "Switch to dark theme");
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.6f", value).replace('-', '−');
    }

    private static ThemedLabel label(String text) {
        return new ThemedLabel(text, Token.MUTED, () -> Theme.sans(12f));
    }

    /** A fixed-width monospace value label sized for {@code prototype}, so changing digits never move their neighbours. */
    private static ThemedLabel value(String text, String prototype) {
        ThemedLabel label = new ThemedLabel(text, Token.TEXT, () -> Theme.mono(12f)) {
            private static final long serialVersionUID = 1L;

            @Override
            public Dimension getPreferredSize() {
                FontMetrics fm = getFontMetrics(getFont());
                return new Dimension(fm.stringWidth(prototype) + 2, super.getPreferredSize().height);
            }

            @Override
            public Dimension getMinimumSize() {
                return getPreferredSize();
            }

            @Override
            public Dimension getMaximumSize() {
                return getPreferredSize();
            }
        };
        return label;
    }

    private static JComponent center(JComponent component) {
        component.setAlignmentY(CENTER_ALIGNMENT);
        return component;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(super.getPreferredSize().width, HEIGHT);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, HEIGHT);
    }

    @Override
    protected void paintComponent(Graphics g) {
        g.setColor(Theme.color(Token.BAR));
        g.fillRect(0, 0, getWidth(), getHeight());
        Color border = Theme.color(Token.BORDER);
        g.setColor(border);
        g.fillRect(0, 0, getWidth(), 1);
    }
}

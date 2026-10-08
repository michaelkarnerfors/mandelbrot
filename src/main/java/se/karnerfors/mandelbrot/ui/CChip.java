package se.karnerfors.mandelbrot.ui;

import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

import javax.accessibility.Accessible;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;

import se.karnerfors.mandelbrot.io.JuliaPresetStore;
import se.karnerfors.mandelbrot.ui.Theme.Token;

/**
 * The Julia constant as a button: "c −0.12300 + 0.74500i" in monospace with a chevron. Click to open the
 * preset popover. Its width is fixed so the top bar does not jiggle as c changes.
 */
public final class CChip extends JComponent implements Accessible {

    private static final long serialVersionUID = 1L;

    private static final int HEIGHT = 32;
    private static final int PAD = 10;
    private static final int CHEVRON = 14;
    private static final String PROTOTYPE = "c −1.23456 − 1.23456i";

    private final List<ActionListener> listeners = new ArrayList<>();
    private String text = "c 0 + 0i";
    private String presetName;
    private boolean hover;
    private boolean open;

    public CChip() {
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        getAccessibleContext().setAccessibleName("Julia constant c");
        updateTooltip();
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                hover = true;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hover = false;
                repaint();
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1 && isEnabled()) {
                    ActionEvent event = new ActionEvent(CChip.this, ActionEvent.ACTION_PERFORMED, "open");
                    for (ActionListener listener : List.copyOf(listeners)) {
                        listener.actionPerformed(event);
                    }
                }
            }
        });
    }

    @Override
    public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) {
            accessibleContext = new AccessibleJComponent() {
                @Override
                public AccessibleRole getAccessibleRole() {
                    return AccessibleRole.PUSH_BUTTON;
                }
            };
        }
        return accessibleContext;
    }

    public void addActionListener(ActionListener listener) {
        listeners.add(listener);
    }

    /** Shows this constant; {@code name} is the preset it matches, or null for a custom value. */
    public void setValue(double re, double im, String name) {
        String next = "c " + JuliaPresetStore.format(re, im);
        if (next.equals(text) && java.util.Objects.equals(name, presetName)) {
            return;
        }
        text = next;
        presetName = name;
        updateTooltip();
        repaint();
    }

    public String getText() {
        return text;
    }

    /** Highlighted while its popover is showing. */
    public void setOpen(boolean value) {
        if (open != value) {
            open = value;
            repaint();
        }
    }

    private void updateTooltip() {
        setToolTipText(presetName == null ? "Julia constant c: custom. Click for presets."
                : "Julia constant c: " + presetName + ". Click for presets.");
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(Theme.monoMedium(12f));
        return new Dimension(PAD + fm.stringWidth(PROTOTYPE) + 8 + CHEVRON + PAD - 2, HEIGHT);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            boolean enabled = isEnabled();
            int w = getWidth();
            int h = getHeight();
            g2.setColor(open ? Theme.color(Token.ACCENT_SOFT) : hover && enabled ? Theme.color(Token.HOVER) : Theme.color(Token.SURFACE));
            g2.fill(new RoundRectangle2D.Float(0, 0, w, h, 12, 12));
            g2.setColor(open ? Theme.color(Token.ACCENT) : Theme.color(Token.BORDER));
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 11, 11));

            g2.setFont(Theme.monoMedium(12f));
            g2.setColor(enabled ? Theme.color(Token.TEXT) : Theme.withAlpha(Theme.color(Token.MUTED), 140));
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(text, PAD, (h - fm.getHeight()) / 2 + fm.getAscent());
            Icons.paint(open ? Icons.Glyph.CHEVRON_UP : Icons.Glyph.CHEVRON_DOWN, g2, w - PAD - CHEVRON + 2,
                    (h - CHEVRON) / 2, CHEVRON, Theme.color(Token.MUTED));
        } finally {
            g2.dispose();
        }
    }
}

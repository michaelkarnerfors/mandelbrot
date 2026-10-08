package se.karnerfors.mandelbrot.ui;

import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Locale;

import javax.accessibility.Accessible;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;

import se.karnerfors.mandelbrot.ui.Theme.Token;

/**
 * The pill in the status bar that says what the renderer is doing: a progress arc and
 * percentage while rendering, a green dot and the last render time when idle.
 */
public final class StatusChip extends JComponent implements Accessible {

    private static final long serialVersionUID = 1L;

    private static final int HEIGHT = 22;
    private static final int PAD_X = 9;
    private static final int GAP = 8;
    private static final int INDICATOR = 14;

    private boolean rendering;
    private double progress;
    private long lastMillis = -1;

    public StatusChip() {
        setOpaque(false);
        setFont(Theme.sansMedium(12f));
        getAccessibleContext().setAccessibleName("Render status");
    }

    @Override
    public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) {
            accessibleContext = new AccessibleJComponent() {
                @Override
                public AccessibleRole getAccessibleRole() {
                    return AccessibleRole.LABEL;
                }
            };
        }
        return accessibleContext;
    }

    public void showRendering(double progressFraction) {
        this.rendering = true;
        this.progress = Math.max(0.0, Math.min(1.0, progressFraction));
        revalidate();
        repaint();
    }

    public void showIdle(long lastRenderMillis) {
        this.rendering = false;
        this.lastMillis = lastRenderMillis;
        revalidate();
        repaint();
    }

    private String label() {
        return rendering ? "Rendering" : lastMillis >= 0 ? "Rendered" : "Ready";
    }

    private String detail() {
        if (rendering) {
            return String.format(Locale.ROOT, "%.0f%%", progress * 100);
        }
        return lastMillis >= 0 ? String.format(Locale.ROOT, "%.2f s", lastMillis / 1000.0) : "";
    }

    @Override
    public void updateUI() {
        super.updateUI();
        setFont(Theme.sansMedium(12f));
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics label = getFontMetrics(getFont());
        FontMetrics detail = getFontMetrics(Theme.mono(12f));
        int width = 2 * PAD_X + INDICATOR + GAP + label.stringWidth(label());
        String d = detail();
        if (!d.isEmpty()) {
            width += GAP + detail.stringWidth(d);
        }
        // Reserve the widest detail ("100%") so the chip does not jitter while the percentage counts up.
        width = Math.max(width, 2 * PAD_X + INDICATOR + GAP + label.stringWidth("Rendering") + GAP
                + detail.stringWidth("100%"));
        return new Dimension(width, HEIGHT);
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
            int w = getWidth();
            int h = getHeight();
            g2.setColor(Theme.color(Token.SURFACE));
            g2.fill(new RoundRectangle2D.Float(0, 0, w, h, h, h));
            g2.setColor(Theme.color(Token.BORDER));
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, h - 1, h - 1));

            int x = PAD_X;
            int cy = h / 2;
            if (rendering) {
                double d = INDICATOR - 3;
                Arc2D.Double track = new Arc2D.Double(x + 1.5, cy - d / 2, d, d, 0, 360, Arc2D.OPEN);
                g2.setStroke(new BasicStroke(3f));
                g2.setColor(Theme.color(Token.BORDER));
                g2.draw(track);
                g2.setColor(Theme.color(Token.ACCENT));
                g2.draw(new Arc2D.Double(x + 1.5, cy - d / 2, d, d, 90, -360 * progress, Arc2D.OPEN));
            } else if (lastMillis >= 0) {
                g2.setColor(Theme.color(Token.OK));
                g2.fill(new Ellipse2D.Double(x + (INDICATOR - 8) / 2.0, cy - 4, 8, 8));
            } else {
                g2.setColor(Theme.color(Token.MUTED));
                g2.setStroke(new BasicStroke(1.5f));
                g2.draw(new Ellipse2D.Double(x + (INDICATOR - 8) / 2.0 + 0.75, cy - 4 + 0.75, 6.5, 6.5));
            }
            x += INDICATOR + GAP;

            g2.setFont(getFont());
            g2.setColor(Theme.color(Token.TEXT));
            FontMetrics fm = g2.getFontMetrics();
            int baseline = (h - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString(label(), x, baseline);
            x += fm.stringWidth(label()) + GAP;

            String d = detail();
            if (!d.isEmpty()) {
                g2.setFont(Theme.mono(12f));
                g2.setColor(Theme.color(Token.MUTED));
                FontMetrics mfm = g2.getFontMetrics();
                g2.drawString(d, x, (h - mfm.getHeight()) / 2 + mfm.getAscent());
            }
        } finally {
            g2.dispose();
        }
    }
}

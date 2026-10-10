package se.karnerfors.mandelbrot.ui;

import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Locale;

import javax.accessibility.Accessible;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;

import se.karnerfors.mandelbrot.render.RenderSettings;
import se.karnerfors.mandelbrot.ui.Theme.Token;

/**
 * The pill in the status bar that says where the renderer is in its two-step routine: a quick
 * preview first, then the refinement at the chosen super-sampling.
 *
 * <pre>
 *   ( ) Preview 40%                          drawing the 1×1 pass
 *   ✓ Preview → ( ) Refining 4×4 62%           preview is up, drawing the final quality over it
 *   ● Settled at 4×4 0.84 s                    done
 * </pre>
 *
 * With a settle factor of 1 the preview is the final image, so it just says "Rendering" and then "Settled at 1×1".
 */
public final class StatusChip extends JComponent implements Accessible {

    private static final long serialVersionUID = 1L;

    private static final int HEIGHT = 22;
    private static final int PAD_X = 9;
    private static final int GAP = 8;
    private static final int INDICATOR = 14;
    private static final int ARROW = 12;

    private RenderStatus status = RenderStatus.idle();

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

    public void showStatus(RenderStatus newStatus) {
        Dimension before = getPreferredSize();
        this.status = newStatus;
        setToolTipText(tooltip());
        getAccessibleContext().setAccessibleDescription(summary());
        if (!before.equals(getPreferredSize())) {
            revalidate();
        }
        repaint();
    }

    private String tooltip() {
        return switch (status.stage()) {
            case PREVIEW -> status.settleFactor() > 1
                    ? "Drawing a quick 1×1 preview; it refines to " + label(status) + " when you stop moving."
                    : null;
            case REFINING -> "Refining this view at " + label(status) + ". Moving again restarts from a 1×1 preview.";
            case FAILED -> "Not enough memory for " + label(status)
                    + " at this window size. The 1×1 preview is kept. Pick a lower factor, or start Java with a larger -Xmx.";
            case SETTLED -> status.settleFactor() > 1 && status.refinedShare() >= 0
                    ? share(status.refinedShare()) + " of the pixels were super-sampled at " + label(status)
                            + "; the rest are smooth enough for one sample each."
                    : null;
            default -> null;
        };
    }

    private String summary() {
        return label() + (detail().isEmpty() ? "" : " " + detail());
    }

    private String label() {
        return switch (status.stage()) {
            case IDLE -> "Ready";
            case PREVIEW -> status.settleFactor() > 1 ? "Preview" : "Rendering";
            case REFINING -> "Refining " + label(status);
            case SETTLED -> "Settled at " + label(status);
            case FAILED -> "Not enough memory for " + label(status);
        };
    }

    private String detail() {
        return switch (status.stage()) {
            case PREVIEW, REFINING -> String.format(Locale.ROOT, "%.0f%%", status.progress() * 100);
            case SETTLED -> status.millis() >= 0 ? String.format(Locale.ROOT, "%.2f s", status.millis() / 1000.0) : "";
            default -> "";
        };
    }

    @Override
    public void updateUI() {
        super.updateUI();
        setFont(Theme.sansMedium(12f));
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(layout(null, HEIGHT), HEIGHT);
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
            layout(g2, h);
        } finally {
            g2.dispose();
        }
    }

    /**
     * Lays the chip's contents out left to right. With {@code g2 == null} it only measures; otherwise
     * it paints as it goes. Returns the total width including padding, so measuring and painting
     * can never disagree.
     */
    private int layout(Graphics2D g2, int h) {
        FontMetrics fm = getFontMetrics(getFont());
        FontMetrics mfm = getFontMetrics(Theme.mono(12f));
        int cy = h / 2;
        int x = PAD_X;

        RenderStatus.Stage stage = status.stage();
        if (stage == RenderStatus.Stage.REFINING) {
            // Completed first step: check mark + "Preview", then an arrow into the active step.
            if (g2 != null) {
                Icons.paint(Icons.Glyph.CHECK, g2, x, cy - INDICATOR / 2, INDICATOR, Theme.color(Token.OK));
            }
            x += INDICATOR + GAP;
            x = text(g2, "Preview", fm, x, h, Token.MUTED);
            x += GAP;
            if (g2 != null) {
                drawArrow(g2, x, cy);
            }
            x += ARROW + GAP;
        }

        if (g2 != null) {
            drawIndicator(g2, x, cy);
        }
        x += INDICATOR + GAP;
        x = text(g2, label(), fm, x, h, Token.TEXT);

        String d = detail();
        if (stage == RenderStatus.Stage.PREVIEW || stage == RenderStatus.Stage.REFINING) {
            d = "100%"; // reserve the widest value so the chip does not jitter while the percentage counts up
        }
        if (!d.isEmpty()) {
            x += GAP;
            if (g2 != null) {
                g2.setFont(Theme.mono(12f));
                g2.setColor(Theme.color(Token.MUTED));
                g2.drawString(detail(), x, (h - mfm.getHeight()) / 2 + mfm.getAscent());
            }
            x += mfm.stringWidth(d);
        }
        return x + PAD_X;
    }

    private int text(Graphics2D g2, String value, FontMetrics fm, int x, int h, Token color) {
        if (g2 != null) {
            g2.setFont(getFont());
            g2.setColor(Theme.color(color));
            g2.drawString(value, x, (h - fm.getHeight()) / 2 + fm.getAscent());
        }
        return x + fm.stringWidth(value);
    }

    private void drawArrow(Graphics2D g2, int x, int cy) {
        Path2D.Float arrow = new Path2D.Float();
        arrow.moveTo(x + 1, cy);
        arrow.lineTo(x + ARROW - 1, cy);
        arrow.moveTo(x + ARROW - 5, cy - 4);
        arrow.lineTo(x + ARROW - 1, cy);
        arrow.lineTo(x + ARROW - 5, cy + 4);
        g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.setColor(Theme.color(Token.MUTED));
        g2.draw(arrow);
    }

    private void drawIndicator(Graphics2D g2, int x, int cy) {
        switch (status.stage()) {
            case PREVIEW, REFINING -> {
                double d = INDICATOR - 3;
                g2.setStroke(new BasicStroke(3f));
                g2.setColor(Theme.color(Token.BORDER));
                g2.draw(new Arc2D.Double(x + 1.5, cy - d / 2, d, d, 0, 360, Arc2D.OPEN));
                g2.setColor(Theme.color(Token.ACCENT));
                g2.draw(new Arc2D.Double(x + 1.5, cy - d / 2, d, d, 90, -360 * status.progress(), Arc2D.OPEN));
            }
            case SETTLED -> {
                g2.setColor(Theme.color(Token.OK));
                g2.fill(new Ellipse2D.Double(x + (INDICATOR - 8) / 2.0, cy - 4, 8, 8));
            }
            case FAILED -> {
                g2.setColor(Theme.color(Token.WARN));
                g2.fill(new Ellipse2D.Double(x + (INDICATOR - 8) / 2.0, cy - 4, 8, 8));
            }
            default -> {
                g2.setColor(Theme.color(Token.MUTED));
                g2.setStroke(new BasicStroke(1.5f));
                g2.draw(new Ellipse2D.Double(x + (INDICATOR - 8) / 2.0 + 0.75, cy - 4 + 0.75, 6.5, 6.5));
            }
        }
    }

    private static String share(double share) {
        return share > 0 && share < 0.01 ? "Under 1%" : String.format(Locale.ROOT, "%.0f%%", share * 100);
    }

    private static String label(RenderStatus status) {
        return RenderSettings.supersampleLabel(status.settleFactor());
    }
}

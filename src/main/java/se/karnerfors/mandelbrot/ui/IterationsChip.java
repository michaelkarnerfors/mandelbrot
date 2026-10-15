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

import se.karnerfors.mandelbrot.ui.Theme.Token;

/**
 * The iteration limit as a small button in the status bar: the number in monospace with a chevron. Click to open
 * the editor above it. Its width is fixed, so the bar does not jiggle as the number gains digits.
 */
public final class IterationsChip extends JComponent implements Accessible {

    private static final long serialVersionUID = 1L;

    private static final int HEIGHT = 26;
    private static final int PAD = 9;
    private static final int CHEVRON = 12;
    private static final String PROTOTYPE = "100000";

    private final List<ActionListener> listeners = new ArrayList<>();
    private String text = "";
    private boolean hover;
    private boolean open;

    public IterationsChip() {
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setValue(1000);
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
                    ActionEvent event = new ActionEvent(IterationsChip.this, ActionEvent.ACTION_PERFORMED, "open");
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

    /** Shows the iteration limit now in use. */
    public void setValue(int iterations) {
        String next = Integer.toString(iterations);
        if (next.equals(text)) {
            return;
        }
        text = next;
        String description = "Maximum iterations per point: " + next + ". Click to change.";
        setToolTipText(description);
        getAccessibleContext().setAccessibleName(description);
        repaint();
    }

    /** Highlighted while its editor is showing. */
    public void setOpen(boolean value) {
        if (open != value) {
            open = value;
            repaint();
        }
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(Theme.monoMedium(12f));
        return new Dimension(PAD + fm.stringWidth(PROTOTYPE) + 6 + CHEVRON + PAD - 2, HEIGHT);
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
            g2.setColor(open ? Theme.color(Token.ACCENT_SOFT)
                    : hover && enabled ? Theme.color(Token.HOVER) : Theme.color(Token.SURFACE));
            g2.fill(new RoundRectangle2D.Float(0, 0, w, h, 12, 12));
            g2.setColor(open ? Theme.color(Token.ACCENT) : Theme.color(Token.BORDER));
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 11, 11));

            g2.setFont(Theme.monoMedium(12f));
            g2.setColor(enabled ? Theme.color(Token.TEXT) : Theme.withAlpha(Theme.color(Token.MUTED), 140));
            FontMetrics fm = g2.getFontMetrics();
            int valueRight = w - PAD - CHEVRON + 2 - 6;
            g2.drawString(text, valueRight - fm.stringWidth(text), (h - fm.getHeight()) / 2 + fm.getAscent());
            // The editor opens above the bar, so the chevron points up while it is closed.
            Icons.paint(open ? Icons.Glyph.CHEVRON_DOWN : Icons.Glyph.CHEVRON_UP, g2, w - PAD - CHEVRON + 2,
                    (h - CHEVRON) / 2, CHEVRON, Theme.color(Token.MUTED));
        } finally {
            g2.dispose();
        }
    }
}

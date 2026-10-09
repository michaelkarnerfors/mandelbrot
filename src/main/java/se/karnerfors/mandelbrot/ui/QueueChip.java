package se.karnerfors.mandelbrot.ui;

import java.awt.Color;
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
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.accessibility.Accessible;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;

import se.karnerfors.mandelbrot.render.RenderJob;
import se.karnerfors.mandelbrot.render.RenderQueue;
import se.karnerfors.mandelbrot.ui.Theme.Token;

/**
 * The status bar button that summarises the render queue ("1 rendering · 1 waiting  62%"), and opens it.
 * It is not there at all while the queue is empty.
 */
public final class QueueChip extends JComponent implements Accessible {

    private static final long serialVersionUID = 1L;

    private static final int HEIGHT = 22;
    private static final int PAD = 10;

    private final List<ActionListener> listeners = new ArrayList<>();
    private String text = "";
    private String percent = "";
    private Token tone = Token.ACCENT;
    private boolean hover;
    /** True while a job is being drawn: the dot pulses so a long render looks alive. */
    private boolean busy;
    private final javax.swing.Timer pulse = new javax.swing.Timer(50, _ -> repaint(PAD - 2, 0, 10, getHeight()));

    public QueueChip() {
        setVisible(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setToolTipText("Render queue");
        getAccessibleContext().setAccessibleName("Render queue");
        MouseAdapter mouse = new MouseAdapter() {
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
                if (e.getButton() == MouseEvent.BUTTON1) {
                    ActionEvent event = new ActionEvent(QueueChip.this, ActionEvent.ACTION_PERFORMED, "open");
                    for (ActionListener listener : List.copyOf(listeners)) {
                        listener.actionPerformed(event);
                    }
                }
            }
        };
        addMouseListener(mouse);
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

    /** Re-reads the queue. */
    public void update(RenderQueue queue) {
        int rendering = queue.count(RenderJob.State.RENDERING);
        int waiting = queue.count(RenderJob.State.WAITING);
        int failed = queue.count(RenderJob.State.FAILED);
        int done = queue.count(RenderJob.State.DONE);
        boolean newBusy = rendering > 0;
        String newText;
        String newPercent = "";
        Token newTone = Token.ACCENT;
        if (queue.jobs().isEmpty()) {
            newText = "";
        } else if (rendering + waiting > 0) {
            StringBuilder sb = new StringBuilder();
            if (rendering > 0) {
                sb.append(rendering).append(" rendering");
            }
            if (waiting > 0) {
                sb.append(sb.length() > 0 ? " · " : "").append(waiting).append(" waiting");
            }
            newText = sb.toString();
            for (RenderJob job : queue.jobs()) {
                if (job.state() == RenderJob.State.RENDERING) {
                    newPercent = String.format(Locale.ROOT, "%d%%", (int) Math.floor(job.progress() * 100));
                }
            }
        } else if (failed > 0) {
            newText = failed + (failed == 1 ? " render failed" : " renders failed");
            newTone = Token.ERROR;
        } else {
            newText = done + (done == 1 ? " render done" : " renders done");
            newTone = Token.OK;
        }
        if (newBusy != busy) {
            busy = newBusy;
            if (busy && isDisplayable()) {
                pulse.start();
            } else {
                pulse.stop();
            }
        }
        if (newText.equals(text) && newPercent.equals(percent) && newTone == tone) {
            return;
        }
        text = newText;
        percent = newPercent;
        tone = newTone;
        setVisible(!text.isEmpty());
        revalidate();
        repaint();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (busy) {
            pulse.start();
        }
    }

    @Override
    public void removeNotify() {
        pulse.stop();
        super.removeNotify();
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(Theme.sansMedium(12f));
        int w = PAD + 6 + 7 + fm.stringWidth(text) + PAD;
        if (!percent.isEmpty()) {
            w += 8 + getFontMetrics(Theme.mono(11.5f)).stringWidth("100%");
        }
        return new Dimension(w, HEIGHT);
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Color ink = Theme.color(tone);
            Color fill = tone == Token.ACCENT ? Theme.color(Token.ACCENT_SOFT) : Theme.withAlpha(ink, 28);
            g2.setColor(hover ? Theme.withAlpha(ink, 60) : fill);
            RoundRectangle2D pill = new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1, getHeight() - 1, HEIGHT, HEIGHT);
            g2.fill(pill);
            g2.setColor(ink);
            g2.draw(pill);

            if (busy) {
                // A slow breath, about 1.2 s a cycle, between full and under half strength.
                double phase = (System.nanoTime() % 1_200_000_000L) / 1_200_000_000.0;
                double strength = 0.45 + 0.55 * (0.5 + 0.5 * Math.cos(2 * Math.PI * phase));
                g2.setColor(Theme.withAlpha(ink, (int) Math.round(255 * strength)));
            }
            g2.fill(new Ellipse2D.Float(PAD, (getHeight() - 6) / 2f, 6, 6));
            g2.setColor(ink);
            g2.setFont(Theme.sansMedium(12f));
            FontMetrics fm = g2.getFontMetrics();
            int baseline = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
            int x = PAD + 6 + 7;
            g2.drawString(text, x, baseline);
            if (!percent.isEmpty()) {
                g2.setFont(Theme.mono(11.5f));
                g2.drawString(percent, x + fm.stringWidth(text) + 8, (getHeight() - g2.getFontMetrics().getHeight()) / 2
                        + g2.getFontMetrics().getAscent());
            }
        } finally {
            g2.dispose();
        }
    }
}

package se.karnerfors.mandelbrot.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;

import javax.swing.JComponent;
import javax.swing.JPanel;

import se.karnerfors.mandelbrot.io.JuliaPresetStore;
import se.karnerfors.mandelbrot.ui.Theme.Token;

/**
 * The Julia pane of the split view: the main canvas under a strip that says whether c is following the pointer
 * or locked, shows c, and offers copy.
 */
public final class JuliaPane extends JPanel {

    private static final long serialVersionUID = 1L;

    private final StatePill pill = new StatePill();
    private final ThemedLabel value = new ThemedLabel("", Token.MUTED, () -> Theme.mono(12f));
    public final AppButton copy = AppButton.iconButton(Icons.Glyph.COPY, "Copy c", AppButton.Style.GHOST, true);

    public JuliaPane() {
        super(new BorderLayout());
        PaneHeader header = new PaneHeader("Julia");
        header.addItem(pill, 10);
        header.addGlue();
        header.addItem(value, 8);
        header.addItem(copy, 6);
        add(header, BorderLayout.NORTH);
        setMinimumSize(new Dimension(160, 120));
        setState(true);
    }

    /** Puts the canvas under the header (taking it from wherever it was). */
    public void attach(FractalPanel canvas) {
        add(canvas, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    public void setC(double re, double im) {
        value.setText("c " + JuliaPresetStore.format(re, im));
    }

    public void setState(boolean locked) {
        pill.set(locked);
    }

    /** "Locked (double-click to unlock)" / "Following (click to lock)". */
    private static final class StatePill extends JComponent {
        private static final long serialVersionUID = 1L;

        private boolean locked = true;

        void set(boolean value) {
            locked = value;
            revalidate();
            repaint();
        }

        private String text() {
            return locked ? "Locked (double-click to unlock)" : "Following (click to lock)";
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(Theme.sansMedium(11.5f));
            return new Dimension(fm.stringWidth(text()) + 28, 20);
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
                java.awt.Color ink = Theme.color(locked ? Token.MUTED : Token.ACCENT);
                g2.setColor(locked ? Theme.color(Token.SURFACE_2) : Theme.color(Token.ACCENT_SOFT));
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), getHeight(), getHeight()));
                g2.setColor(ink);
                g2.fill(new Ellipse2D.Float(9, (getHeight() - 6) / 2f, 6, 6));
                g2.setFont(Theme.sansMedium(11.5f));
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(text(), 20, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            } finally {
                g2.dispose();
            }
        }
    }
}

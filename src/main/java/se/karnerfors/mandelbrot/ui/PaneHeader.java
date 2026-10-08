package se.karnerfors.mandelbrot.ui;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

import se.karnerfors.mandelbrot.ui.Theme.Token;

/** The slim strip on top of a pane in the split view: a title, then whatever the pane adds. */
final class PaneHeader extends JPanel {

    private static final long serialVersionUID = 1L;

    static final int HEIGHT = 36;

    PaneHeader(String title) {
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        setBorder(new EmptyBorder(0, 12, 0, 6));
        setOpaque(true);
        add(new ThemedLabel(title, Token.TEXT, () -> Theme.sansSemiBold(13f)));
    }

    /** Adds a component, vertically centred, with {@code gap} pixels before it. */
    void addItem(JComponent component, int gap) {
        if (gap > 0) {
            add(Box.createHorizontalStrut(gap));
        }
        component.setAlignmentY(Component.CENTER_ALIGNMENT);
        add(component);
    }

    void addGlue() {
        add(Box.createHorizontalGlue());
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(super.getPreferredSize().width, HEIGHT);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(0, HEIGHT);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, HEIGHT);
    }

    @Override
    protected void paintComponent(Graphics g) {
        g.setColor(Theme.color(Token.SURFACE));
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setColor(Theme.color(Token.BORDER));
        g.fillRect(0, getHeight() - 1, getWidth(), 1);
    }
}

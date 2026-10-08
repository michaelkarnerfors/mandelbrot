package se.karnerfors.mandelbrot.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.accessibility.Accessible;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;

import se.karnerfors.mandelbrot.ui.Theme.Token;

/**
 * A segmented control: a dark track with a raised pill under the selected item, as drawn in
 * the design. One item is always selected. Arrow keys move the selection when focused.
 */
public final class SegmentedControl<T> extends JComponent implements Accessible {

    private static final long serialVersionUID = 1L;

    private static final int PADDING = 2;
    private static final int ITEM_HEIGHT = 28;
    private static final int ITEM_MIN_WIDTH = 40;
    private static final int ITEM_PAD_X = 10;

    private final List<T> items;
    private final List<String> labels = new ArrayList<>();
    private int selected;
    private int hovered = -1;
    private Consumer<T> changeListener = _ -> { };

    public SegmentedControl(List<T> items, T initial, Function<T, String> labeler) {
        this.items = List.copyOf(items);
        for (T item : this.items) {
            labels.add(labeler.apply(item));
        }
        this.selected = Math.max(0, this.items.indexOf(initial));
        setFocusable(true);
        setOpaque(false);
        setFont(Theme.sansMedium(13f));

        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                setHovered(indexAt(e.getX()));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                setHovered(-1);
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (!isEnabled()) {
                    return;
                }
                int index = indexAt(e.getX());
                if (index >= 0) {
                    requestFocusInWindow();
                    select(index, true);
                }
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (!isEnabled()) {
                    return;
                }
                if (e.getKeyCode() == KeyEvent.VK_LEFT && selected > 0) {
                    select(selected - 1, true);
                } else if (e.getKeyCode() == KeyEvent.VK_RIGHT && selected < SegmentedControl.this.items.size() - 1) {
                    select(selected + 1, true);
                }
            }
        });
        addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                repaint();
            }

            @Override
            public void focusLost(FocusEvent e) {
                repaint();
            }
        });
        getAccessibleContext().setAccessibleName("Segmented control");
    }

    @Override
    public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) {
            accessibleContext = new AccessibleJComponent() {
                @Override
                public AccessibleRole getAccessibleRole() {
                    return AccessibleRole.PAGE_TAB_LIST;
                }
            };
        }
        return accessibleContext;
    }

    public T getSelected() {
        return items.get(selected);
    }

    /** Changes the selection without notifying the listener (for syncing to state set elsewhere). */
    public void setSelectedSilently(T item) {
        int index = items.indexOf(item);
        if (index >= 0 && index != selected) {
            selected = index;
            repaint();
        }
    }

    /** Called when the user picks a different item. */
    public void setChangeListener(Consumer<T> listener) {
        this.changeListener = listener != null ? listener : _ -> { };
    }

    private void select(int index, boolean notify) {
        if (index == selected) {
            return;
        }
        selected = index;
        repaint();
        if (notify) {
            changeListener.accept(items.get(index));
        }
    }

    private void setHovered(int index) {
        if (index != hovered) {
            hovered = index;
            repaint();
        }
    }

    private int itemWidth(int index) {
        FontMetrics fm = getFontMetrics(getFont());
        return Math.max(ITEM_MIN_WIDTH, fm.stringWidth(labels.get(index)) + 2 * ITEM_PAD_X);
    }

    private int indexAt(int x) {
        int left = PADDING;
        for (int i = 0; i < items.size(); i++) {
            int w = itemWidth(i);
            if (x >= left && x < left + w) {
                return i;
            }
            left += w + PADDING;
        }
        return -1;
    }

    @Override
    public Dimension getPreferredSize() {
        int width = PADDING;
        for (int i = 0; i < items.size(); i++) {
            width += itemWidth(i) + PADDING;
        }
        return new Dimension(width, ITEM_HEIGHT + 2 * PADDING);
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
    public void updateUI() {
        super.updateUI();
        setFont(Theme.sansMedium(13f));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            boolean enabled = isEnabled();

            g2.setColor(Theme.color(Token.TRACK));
            g2.fill(new RoundRectangle2D.Float(0, 0, w, h, 16, 16));

            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            int left = PADDING;
            for (int i = 0; i < items.size(); i++) {
                int itemW = itemWidth(i);
                RoundRectangle2D pill = new RoundRectangle2D.Float(left, PADDING, itemW, ITEM_HEIGHT, 12, 12);
                Color textColor = Theme.color(Token.MUTED);
                if (i == selected) {
                    g2.setColor(Theme.withAlpha(Color.BLACK, 38));
                    g2.fill(new RoundRectangle2D.Float(left, PADDING + 1, itemW, ITEM_HEIGHT, 12, 12));
                    g2.setColor(Theme.color(Token.SURFACE));
                    g2.fill(pill);
                    textColor = Theme.color(Token.TEXT);
                } else if (i == hovered && enabled) {
                    g2.setColor(Theme.color(Token.HOVER));
                    g2.fill(pill);
                    textColor = Theme.color(Token.TEXT);
                }
                if (!enabled) {
                    textColor = Theme.withAlpha(textColor, 120);
                }
                g2.setColor(textColor);
                String label = labels.get(i);
                int textX = left + (itemW - fm.stringWidth(label)) / 2;
                int baseline = PADDING + (ITEM_HEIGHT - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString(label, textX, baseline);
                left += itemW + PADDING;
            }

            if (isFocusOwner()) {
                g2.setColor(Theme.color(Token.ACCENT));
                g2.setStroke(new java.awt.BasicStroke(2f));
                g2.draw(new RoundRectangle2D.Float(1, 1, w - 2, h - 2, 14, 14));
            }
        } finally {
            g2.dispose();
        }
    }
}

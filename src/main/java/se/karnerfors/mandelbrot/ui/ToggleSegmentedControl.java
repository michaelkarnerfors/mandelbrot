package se.karnerfors.mandelbrot.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.swing.JComponent;

import se.karnerfors.mandelbrot.ui.Theme.Token;

/**
 * Looks like a {@link SegmentedControl}, but each item switches on and off on its own and every item that is on
 * gets a raised pill. At least one item is always on: clicking the last one that is on does nothing.
 */
public final class ToggleSegmentedControl<T> extends JComponent {

    private static final long serialVersionUID = 1L;

    private static final int PADDING = 2;
    private static final int ITEM_HEIGHT = 28;
    private static final int ITEM_MIN_WIDTH = 40;
    private static final int ITEM_PAD_X = 10;

    private final List<T> items;
    private final List<String> labels = new ArrayList<>();
    private final boolean[] on;
    private int hovered = -1;
    private Consumer<T> changeListener = _ -> { };

    public ToggleSegmentedControl(List<T> items, Collection<T> initial, Function<T, String> labeler) {
        this.items = List.copyOf(items);
        this.on = new boolean[this.items.size()];
        for (T item : this.items) {
            labels.add(labeler.apply(item));
        }
        setOnSilently(initial);
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
                int index = indexAt(e.getX());
                if (isEnabled() && index >= 0 && canToggle(index)) {
                    on[index] = !on[index];
                    repaint();
                    changeListener.accept(ToggleSegmentedControl.this.items.get(index));
                }
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    public boolean isOn(T item) {
        int index = items.indexOf(item);
        return index >= 0 && on[index];
    }

    /**
     * Switches on exactly the items in {@code onItems}, without notifying the listener (for syncing to state set
     * elsewhere). With none of them, the first item is switched on.
     */
    public void setOnSilently(Collection<T> onItems) {
        boolean any = false;
        for (int i = 0; i < items.size(); i++) {
            on[i] = onItems.contains(items.get(i));
            any |= on[i];
        }
        if (!any && on.length > 0) {
            on[0] = true;
        }
        repaint();
    }

    /** Called with the item the user just switched on or off; {@link #isOn} already tells the new state. */
    public void setChangeListener(Consumer<T> listener) {
        this.changeListener = listener != null ? listener : _ -> { };
    }

    /** An item can be switched off only while another one is on. */
    private boolean canToggle(int index) {
        if (!on[index]) {
            return true;
        }
        for (int i = 0; i < on.length; i++) {
            if (i != index && on[i]) {
                return true;
            }
        }
        return false;
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
            boolean enabled = isEnabled();

            g2.setColor(Theme.color(Token.TRACK));
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 16, 16));

            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            int left = PADDING;
            for (int i = 0; i < items.size(); i++) {
                int itemW = itemWidth(i);
                RoundRectangle2D pill = new RoundRectangle2D.Float(left, PADDING, itemW, ITEM_HEIGHT, 12, 12);
                Color textColor = Theme.color(Token.MUTED);
                if (on[i]) {
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
        } finally {
            g2.dispose();
        }
    }
}

package se.karnerfors.mandelbrot.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

import javax.swing.Icon;
import javax.swing.JButton;

import se.karnerfors.mandelbrot.ui.Theme.Token;

/**
 * The app's button. Custom painted so it matches the design in both themes: a ghost
 * button for toolbar actions, an outlined one for secondary actions, a filled accent one
 * for the main action, and a soft accent one for an "on" toggle state.
 */
public class AppButton extends JButton {

    private static final long serialVersionUID = 1L;

    public enum Style { GHOST, OUTLINE, PRIMARY, ON }

    private static final int PAD_X = 10;
    private static final int ICON_TEXT_GAP = 6;
    private static final int ARC = 6;

    private Style style;
    private final boolean small;
    private Icons.Glyph glyph;
    private Icon extraIcon;
    private String fullText;
    private boolean textHidden;
    private boolean ready;

    public AppButton(String text, Icons.Glyph glyph, Style style, boolean small) {
        super(text == null ? "" : text);
        this.fullText = text == null ? "" : text;
        this.glyph = glyph;
        this.style = style;
        this.small = small;
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setRolloverEnabled(true);
        setFocusable(true);
        setFont(Theme.sansMedium(small ? 12f : 13f));
        if (!fullText.isEmpty()) {
            getAccessibleContext().setAccessibleName(fullText);
        }
        ready = true;
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (ready) {
            // A theme switch resets fonts to the look-and-feel default; put ours back.
            setFont(Theme.sansMedium(small ? 12f : 13f));
        }
    }

    /** An icon-only button, square, with {@code tooltip} doubling as its accessible name. */
    public static AppButton iconButton(Icons.Glyph glyph, String tooltip, Style style, boolean small) {
        AppButton button = new AppButton("", glyph, style, small);
        button.setToolTipText(tooltip);
        button.getAccessibleContext().setAccessibleName(tooltip);
        return button;
    }

    public void setStyle(Style style) {
        if (this.style != style) {
            this.style = style;
            repaint();
        }
    }

    public void setGlyph(Icons.Glyph glyph) {
        this.glyph = glyph;
        repaint();
    }

    /** An additional icon drawn before the label (for example the palette swatch). It paints its own colours. */
    public void setExtraIcon(Icon icon) {
        this.extraIcon = icon;
        revalidate();
        repaint();
    }

    /** Changes the label; in compact mode it is kept as the tooltip and the button stays icon-only. */
    public void setLabel(String text) {
        String value = text == null ? "" : text;
        if (value.equals(fullText)) {
            return;
        }
        fullText = value;
        getAccessibleContext().setAccessibleName(value);
        if (textHidden) {
            setToolTipText(value);
        } else {
            setText(value);
        }
        revalidate();
        repaint();
    }

    /** In compact mode the label is dropped and the button keeps its icon; the label becomes the tooltip. */
    public void setCompact(boolean compact) {
        if (fullText.isEmpty() || compact == textHidden) {
            return;
        }
        textHidden = compact;
        setText(compact ? "" : fullText);
        setToolTipText(compact ? fullText : null);
        revalidate();
        repaint();
    }

    private int height() {
        return small ? 26 : 32;
    }

    private int iconSize() {
        return small ? 14 : 16;
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont());
        int content = 0;
        int parts = 0;
        if (extraIcon != null) {
            content += extraIcon.getIconWidth();
            parts++;
        }
        if (glyph != null) {
            content += iconSize();
            parts++;
        }
        String text = getText();
        if (text != null && !text.isEmpty()) {
            content += fm.stringWidth(text);
            parts++;
        }
        content += Math.max(0, parts - 1) * ICON_TEXT_GAP;
        boolean iconOnly = (text == null || text.isEmpty()) && extraIcon == null && glyph != null;
        int width = iconOnly ? height() : content + 2 * PAD_X - (small ? 2 : 0);
        return new Dimension(width, height());
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
            boolean hover = enabled && getModel().isRollover();
            boolean pressed = enabled && getModel().isArmed() && getModel().isPressed();

            Color fill = null;
            Color border = null;
            Color fg = Theme.color(Token.TEXT);
            switch (style) {
                case GHOST:
                    if (hover || pressed) {
                        fill = Theme.color(Token.HOVER);
                    }
                    break;
                case OUTLINE:
                    fill = hover || pressed ? Theme.color(Token.HOVER) : Theme.color(Token.SURFACE);
                    border = Theme.color(Token.BORDER);
                    break;
                case PRIMARY:
                    fill = Theme.color(Token.ACCENT);
                    fg = Theme.color(Token.ON_ACCENT);
                    if (hover || pressed) {
                        fill = brighter(fill, pressed ? 0.88f : 1.08f);
                    }
                    break;
                case ON:
                    fill = Theme.color(Token.ACCENT_SOFT);
                    fg = Theme.color(Token.ACCENT);
                    break;
                default:
                    break;
            }
            if (!enabled) {
                fg = Theme.withAlpha(Theme.color(Token.MUTED), 140);
                if (style == Style.PRIMARY) {
                    fill = Theme.color(Token.TRACK);
                }
            }

            int w = getWidth();
            int h = getHeight();
            if (fill != null) {
                g2.setColor(fill);
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, ARC * 2, ARC * 2));
            }
            if (border != null) {
                g2.setColor(border);
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, ARC * 2 - 1, ARC * 2 - 1));
            }

            String text = getText();
            boolean hasText = text != null && !text.isEmpty();
            boolean iconOnly = !hasText && extraIcon == null;
            FontMetrics fm = g2.getFontMetrics(getFont());
            int contentWidth = getPreferredSize().width - 2 * PAD_X + (small ? 2 : 0);
            int x = iconOnly ? (w - iconSize()) / 2 : (w - contentWidth) / 2;

            if (extraIcon != null) {
                extraIcon.paintIcon(this, g2, x, (h - extraIcon.getIconHeight()) / 2);
                x += extraIcon.getIconWidth() + ICON_TEXT_GAP;
            }
            if (glyph != null) {
                Icons.paint(glyph, g2, x, (h - iconSize()) / 2, iconSize(), fg);
                x += iconSize() + (hasText ? ICON_TEXT_GAP : 0);
            }
            if (hasText) {
                g2.setFont(getFont());
                g2.setColor(fg);
                int baseline = (h - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString(text, x, baseline);
            }

            if (isFocusOwner()) {
                g2.setColor(Theme.color(Token.ACCENT));
                g2.setStroke(new java.awt.BasicStroke(2f));
                g2.draw(new RoundRectangle2D.Float(1, 1, w - 2, h - 2, ARC * 2 - 2, ARC * 2 - 2));
            }
        } finally {
            g2.dispose();
        }
    }

    private static Color brighter(Color c, float factor) {
        int r = Math.min(255, Math.round(c.getRed() * factor));
        int gr = Math.min(255, Math.round(c.getGreen() * factor));
        int b = Math.min(255, Math.round(c.getBlue() * factor));
        return new Color(r, gr, b);
    }
}

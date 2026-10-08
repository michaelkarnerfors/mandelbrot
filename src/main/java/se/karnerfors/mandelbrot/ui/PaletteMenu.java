package se.karnerfors.mandelbrot.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;

import se.karnerfors.mandelbrot.io.PaletteLibrary;
import se.karnerfors.mandelbrot.io.PaletteLibrary.NamedPalette;
import se.karnerfors.mandelbrot.palette.Palette;
import se.karnerfors.mandelbrot.ui.Theme.Token;

/**
 * The menu under the Palette button: built-in palettes, your own (with delete), and one button that opens
 * the editor: "New…" when a built-in palette is active, "New / Edit…" when one of yours is. Picking a
 * palette applies it at once.
 */
public final class PaletteMenu {

    /** What the menu needs from the application. */
    public interface Host {
        Palette current();

        /** The name of the entry that is active, if the current palette is one of the listed ones. */
        Optional<String> activeName();

        /** A palette was picked from the menu. */
        void choose(String name, Palette palette);


        void editCurrent();

        /** The library changed (a palette was deleted). */
        void libraryChanged();
    }

    private static final int WIDTH = 380;

    private PaletteMenu() {
    }

    public static void show(Component invoker, PaletteLibrary library, Host host) {
        library.reload();
        JPopupMenu popup = new JPopupMenu();
        popup.setBorder(new LineBorder(Theme.color(Token.BORDER)));
        popup.setBackground(Theme.color(Token.SURFACE));
        popup.setLightWeightPopupEnabled(true);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(Theme.color(Token.SURFACE));

        Optional<String> active = host.activeName();

        content.add(groupLabel("BUILT IN"));
        for (NamedPalette entry : library.builtIn()) {
            content.add(new Row(entry, active.map(entry.name()::equals).orElse(false), false, popup, library, host));
        }
        List<NamedPalette> yours = library.yours();
        content.add(groupLabel("YOURS"));
        if (yours.isEmpty()) {
            ThemedLabel hint = new ThemedLabel("Nothing yet. Use New… to make one.", Token.MUTED,
                    () -> Theme.sans(12f));
            hint.setBorder(new EmptyBorder(2, 12, 8, 12));
            hint.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(hint);
        }
        for (NamedPalette entry : yours) {
            content.add(new Row(entry, active.map(entry.name()::equals).orElse(false), true, popup, library, host));
        }

        // One button: "New…" when the active palette is a built-in (or unsaved), "New / Edit…" when it is one of yours.
        boolean customActive = active.map(n -> library.statusOf(n) == PaletteLibrary.NameStatus.EXISTING).orElse(false);
        AppButton edit = new AppButton(customActive ? "New / Edit…" : "New…", Icons.Glyph.EDIT, AppButton.Style.OUTLINE, false);
        edit.addActionListener(_ -> {
            popup.setVisible(false);
            host.editCurrent();
        });
        JPanel actionBar = new JPanel(new BorderLayout());
        actionBar.setBackground(Theme.color(Token.SURFACE));
        actionBar.setBorder(new EmptyBorder(10, 12, 10, 12));
        actionBar.add(edit);
        actionBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(Box.createVerticalStrut(4));
        content.add(actionBar);

        ThemedLabel footer = new ThemedLabel(displayPath(library), Token.MUTED, () -> Theme.mono(11f));
        footer.setBorder(new EmptyBorder(6, 12, 8, 12));
        footer.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(footer);

        content.setPreferredSize(new Dimension(WIDTH, content.getPreferredSize().height));
        popup.add(content);
        popup.show(invoker, Math.min(0, invoker.getWidth() - WIDTH), invoker.getHeight() + 4);
    }

    private static String displayPath(PaletteLibrary library) {
        String path = library.file().toString();
        String home = System.getProperty("user.home");
        return home != null && path.startsWith(home) ? "~" + path.substring(home.length()).replace('\\', '/') : path;
    }

    private static JComponent groupLabel(String text) {
        ThemedLabel label = new ThemedLabel(text, Token.MUTED, Theme::groupLabelFont);
        label.setBorder(new EmptyBorder(10, 12, 4, 12));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    /** One palette in the list. */
    private static final class Row extends JPanel {
        private static final long serialVersionUID = 1L;

        private boolean hover;

        Row(NamedPalette entry, boolean active, boolean mine, JPopupMenu popup, PaletteLibrary library, Host host) {
            super(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 10, 0));
            setOpaque(false);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setBorder(new EmptyBorder(4, 2, 4, 2));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
            setPreferredSize(new Dimension(WIDTH, 34));

            add(new Swatch(entry.palette()));
            ThemedLabel name = new ThemedLabel(entry.name(), Token.TEXT, () -> Theme.sansMedium(13f));
            name.setPreferredSize(new Dimension(mine ? 160 : 176, 22));
            add(name);
            if (mine) {
                AppButton delete = AppButton.iconButton(Icons.Glyph.TRASH, "Delete " + entry.name(), AppButton.Style.GHOST, true);
                delete.addActionListener(_ -> {
                    // The confirmation is a window of its own and would dismiss the menu: hide it, then bring it back.
                    Component invoker = popup.getInvoker();
                    popup.setVisible(false);
                    delete(entry.name(), library, host);
                    if (invoker != null && invoker.isShowing()) {
                        PaletteMenu.show(invoker, library, host);
                    }
                });
                add(delete);
            }
            if (active) {
                add(new CheckMark());
            }

            // The highlight and the click must work over the whole row, including the swatch, the label and
            // the empty space; child components swallow mouse events, so listen on every one of them.
            MouseAdapter rowMouse = new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    setHover(true);
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    setHover(true);
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    // Leaving a child to another part of the same row is not leaving the row.
                    Point pointer = MouseInfo.getPointerInfo() == null ? null : MouseInfo.getPointerInfo().getLocation();
                    if (pointer == null || !isShowing()) {
                        setHover(false);
                        return;
                    }
                    SwingUtilities.convertPointFromScreen(pointer, Row.this);
                    setHover(contains(pointer) && !overButton(pointer));
                }
            };
            MouseAdapter pick = new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    popup.setVisible(false);
                    host.choose(entry.name(), entry.palette());
                }
            };
            listenEverywhere(this, rowMouse, pick);
        }

        private void listenEverywhere(Component component, MouseAdapter hoverListener, MouseAdapter pickListener) {
            // The rename and delete buttons keep the pointer to themselves: no row highlight, no row click.
            if (!(component instanceof AppButton)) {
                component.addMouseListener(hoverListener);
                component.addMouseMotionListener(hoverListener);
                component.addMouseListener(pickListener);
            }
            if (component instanceof java.awt.Container container) {
                for (Component child : container.getComponents()) {
                    listenEverywhere(child, hoverListener, pickListener);
                }
            }
        }

        /** True if {@code point} (in this row's coordinates) is over one of the row's buttons. */
        private boolean overButton(Point point) {
            for (Component child : getComponents()) {
                if (child instanceof AppButton && child.getBounds().contains(point)) {
                    return true;
                }
            }
            return false;
        }

        private void setHover(boolean value) {
            if (hover != value) {
                hover = value;
                repaint();
            }
        }

        private static void delete(String name, PaletteLibrary library, Host host) {
            if (JOptionPane.showConfirmDialog(null, "Delete the palette \"" + name + "\"?", "Delete palette",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
                return;
            }
            try {
                library.delete(name);
                host.libraryChanged();
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(null, "Could not delete: " + ex.getMessage(), "Delete failed",
                        JOptionPane.ERROR_MESSAGE);
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (hover) {
                g.setColor(Theme.color(Token.HOVER));
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        }
    }

    /** A 120 x 18 strip showing one cycle of a palette. */
    static final class Swatch extends JComponent {
        private static final long serialVersionUID = 1L;
        private static final int W = 120;
        private static final int H = 18;

        private final transient Color[] gradient;

        Swatch(Palette palette) {
            this.gradient = palette.gradient();
            setPreferredSize(new Dimension(W, H));
            setMinimumSize(new Dimension(W, H));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.clip(new RoundRectangle2D.Float(0, 0, W, H, 8, 8));
                for (int x = 0; x < W; x++) {
                    g2.setColor(gradient[Math.min(gradient.length - 1, x * gradient.length / W)]);
                    g2.fillRect(x, 0, 1, H);
                }
                g2.setClip(null);
                g2.setColor(Theme.withAlpha(Theme.color(Token.TEXT), 46));
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, W - 1, H - 1, 7, 7));
            } finally {
                g2.dispose();
            }
        }
    }

    private static final class CheckMark extends JComponent {
        private static final long serialVersionUID = 1L;

        CheckMark() {
            setPreferredSize(new Dimension(18, 22));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Icons.paint(Icons.Glyph.CHECK, g, 1, 3, 16, Theme.color(Token.ACCENT));
        }
    }
}

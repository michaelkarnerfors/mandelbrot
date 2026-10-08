package se.karnerfors.mandelbrot.ui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

import se.karnerfors.mandelbrot.io.JuliaPresetStore;
import se.karnerfors.mandelbrot.io.JuliaPresetStore.Entry;
import se.karnerfors.mandelbrot.math.Complex;
import se.karnerfors.mandelbrot.math.Viewport;
import se.karnerfors.mandelbrot.palette.Palette;
import se.karnerfors.mandelbrot.render.Colorizer;
import se.karnerfors.mandelbrot.render.IterationGrid;
import se.karnerfors.mandelbrot.render.RenderAlgorithm;
import se.karnerfors.mandelbrot.render.Renderer;
import se.karnerfors.mandelbrot.render.RenderSettings;
import se.karnerfors.mandelbrot.ui.Theme.Token;

/**
 * The popover under the c chip: your Julia constants as a list, each with a small picture of its set, a name
 * and the value. Click a row to use it. Hovering a row shows rename and delete; the dotted handle on the left
 * drags the row to a new place. "Save current c" adds the constant on screen now and lets you name it.
 */
public final class JuliaPresetPopover {

    /** What the popover needs from the application. */
    public interface Host {
        /** The constant the Julia view is using right now. */
        Complex currentC();

        /** The palette to draw the little pictures with. */
        Palette palette();

        /** Use this constant (the view jumps to the Julia home view). */
        void apply(Entry entry);
    }

    private static final int WIDTH = 430;
    private static final int PAD = 12;
    private static final int ROW_H = 54;
    private static final int THUMB_W = 56;
    private static final int THUMB_H = 38;
    private static final int GRIP_W = 18;
    private static final int BUTTON = 26;
    private static final int BUTTON_GAP = 4;
    private static final int LIST_VIEW_HEIGHT = 5 * ROW_H + 8;

    private static final ExecutorService THUMBS = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "julia-preset-thumbnails");
        t.setDaemon(true);
        return t;
    });

    private JuliaPresetPopover() {
    }

    /** {@code onClose} runs when the popover goes away, whichever way (so the chip can pick up a rename). */
    public static void show(Component invoker, JuliaPresetStore store, Host host, Runnable onClose) {
        new Session(invoker, store, host, onClose).show();
    }

    /** A small picture of the Julia set for c, drawn with the palette. Runs on the calling thread; keep it off the EDT. */
    static BufferedImage thumbnail(double re, double im, Palette palette) {
        int w = THUMB_W * 2;
        int h = THUMB_H * 2;
        RenderSettings settings = new RenderSettings(120, 2.0, 1, RenderAlgorithm.JULIA, Complex.fromCartesian(re, im));
        Viewport view = new Viewport(Complex.fromCartesian(0, 0), Math.min(w, h) / 3.0, w, h);
        IterationGrid grid = Renderer.computeGrid(view, settings);
        return Colorizer.colorize(grid, palette);
    }

    /** One opening of the popover. */
    private static final class Session {
        private final Component invoker;
        private final JuliaPresetStore store;
        private final Host host;
        private final Runnable onClose;
        private final JPopupMenu popup = new JPopupMenu();
        private final ListPanel list = new ListPanel();
        private final CountChip count = new CountChip();
        private final Map<String, BufferedImage> pictures = new HashMap<>();
        private final Map<String, Boolean> requested = new HashMap<>();
        private final List<Row> rows = new ArrayList<>();
        private JScrollPane scroll;

        Session(Component invoker, JuliaPresetStore store, Host host, Runnable onClose) {
            this.onClose = onClose;
            this.invoker = invoker;
            this.store = store;
            this.host = host;
        }

        void show() {
            store.reload();
            popup.setBorder(new LineBorder(Theme.color(Token.BORDER)));
            popup.setBackground(Theme.color(Token.SURFACE));
            popup.setLightWeightPopupEnabled(true);
            if (invoker instanceof CChip chip) {
                chip.setOpen(true);
            }
            popup.addPopupMenuListener(new PopupMenuListener() {
                @Override
                public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                    // nothing
                }

                @Override
                public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                    closed();
                }

                @Override
                public void popupMenuCanceled(PopupMenuEvent e) {
                    closed();
                }
            });

            JPanel content = new JPanel();
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            content.setBackground(Theme.color(Token.SURFACE));
            content.add(header());

            list.setOpaque(false);
            list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
            list.setBorder(new EmptyBorder(0, PAD - 4, 6, PAD - 4));
            scroll = new JScrollPane(list, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.setBorder(javax.swing.BorderFactory.createEmptyBorder());
            scroll.setOpaque(false);
            scroll.getViewport().setOpaque(false);
            scroll.getVerticalScrollBar().setUnitIncrement(24);
            scroll.setPreferredSize(new Dimension(WIDTH, LIST_VIEW_HEIGHT));
            scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(scroll);

            JPanel line = new JPanel() {
                private static final long serialVersionUID = 1L;

                @Override
                protected void paintComponent(Graphics g) {
                    g.setColor(Theme.color(Token.BORDER_SOFT));
                    g.fillRect(0, 0, getWidth(), 1);
                }
            };
            line.setOpaque(false);
            line.setPreferredSize(new Dimension(WIDTH, 1));
            line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
            line.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(line);

            JPanel footer = new JPanel(new BorderLayout());
            footer.setOpaque(false);
            footer.setBorder(new EmptyBorder(7, PAD, 8, PAD));
            footer.setAlignmentX(Component.LEFT_ALIGNMENT);
            footer.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
            footer.add(new ThemedLabel(displayPath(store), Token.MUTED, () -> Theme.mono(11f)), BorderLayout.WEST);
            footer.add(new ThemedLabel("Drag to reorder", Token.MUTED, () -> Theme.sans(11f)), BorderLayout.EAST);
            content.add(footer);

            content.setPreferredSize(new Dimension(WIDTH, content.getPreferredSize().height));
            popup.add(content);
            rebuild(null);
            popup.show(invoker, 0, invoker.getHeight() + 4);
        }

        private void closed() {
            if (invoker instanceof CChip chip) {
                chip.setOpen(false);
            }
            if (onClose != null) {
                SwingUtilities.invokeLater(onClose);
            }
        }

        private JComponent header() {
            JPanel row = new JPanel();
            row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
            row.setOpaque(false);
            row.setBorder(new EmptyBorder(PAD, PAD, 8, PAD));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
            row.add(new ThemedLabel("Julia presets", Token.TEXT, () -> Theme.sansSemiBold(14f)));
            row.add(Box.createHorizontalStrut(8));
            row.add(count);
            row.add(Box.createHorizontalGlue());
            AppButton save = new AppButton("Save current c", Icons.Glyph.PLUS, AppButton.Style.PRIMARY, true);
            save.setToolTipText("Keep the constant on screen now as a preset");
            save.addActionListener(_ -> saveCurrent());
            row.add(save);
            for (Component c : row.getComponents()) {
                if (c instanceof JComponent jc) {
                    jc.setAlignmentY(Component.CENTER_ALIGNMENT);
                }
            }
            return row;
        }

        void rebuild(String renameId) {
            list.removeAll();
            rows.clear();
            Complex now = host.currentC();
            for (Entry entry : store.all()) {
                Row row = new Row(entry, now != null && entry.sameAs(now.real, now.imaginary), this);
                rows.add(row);
                list.add(row);
            }
            if (rows.isEmpty()) {
                ThemedLabel empty = new ThemedLabel("No presets. Use Save current c to keep this one.", Token.MUTED,
                        () -> Theme.sans(12f));
                empty.setBorder(new EmptyBorder(28, 0, 28, 0));
                empty.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
                empty.setAlignmentX(Component.CENTER_ALIGNMENT);
                list.add(empty);
            }
            count.set(rows.size());
            list.revalidate();
            list.repaint();
            if (renameId != null) {
                for (Row row : rows) {
                    if (row.entry.id().equals(renameId)) {
                        row.startRename();
                        SwingUtilities.invokeLater(() -> list.scrollRectToVisible(row.getBounds()));
                    }
                }
            }
        }

        /** The little picture for an entry, or null while it is still being drawn. */
        BufferedImage picture(Entry entry) {
            BufferedImage image = pictures.get(entry.id());
            if (image == null && requested.putIfAbsent(entry.id(), Boolean.TRUE) == null) {
                Palette palette = host.palette();
                THUMBS.execute(() -> {
                    try {
                        BufferedImage drawn = thumbnail(entry.re(), entry.im(), palette);
                        SwingUtilities.invokeLater(() -> {
                            pictures.put(entry.id(), drawn);
                            list.repaint();
                        });
                    } catch (RuntimeException ex) {
                        // No picture is not a problem; the row still works.
                    }
                });
            }
            return image;
        }

        // --- actions --------------------------------------------------------

        private void saveCurrent() {
            Complex now = host.currentC();
            if (now == null) {
                return;
            }
            try {
                Entry added = store.add(store.suggestName(), now.real, now.imaginary);
                rebuild(added.id());
            } catch (IOException ex) {
                error("Could not save the preset: " + ex.getMessage(), "Save failed");
            }
        }

        void use(Entry entry) {
            popup.setVisible(false);
            host.apply(entry);
        }

        void rename(Entry entry, String newName) {
            try {
                store.rename(entry.id(), newName);
            } catch (IOException ex) {
                error("Could not rename: " + ex.getMessage(), "Rename failed");
            }
            rebuild(null);
        }

        void delete(Entry entry) {
            try {
                store.delete(entry.id());
            } catch (IOException ex) {
                error("Could not delete: " + ex.getMessage(), "Delete failed");
            }
            rebuild(null);
        }

        void move(Entry entry, int newIndex) {
            try {
                store.move(entry.id(), newIndex);
            } catch (IOException ex) {
                error("Could not reorder: " + ex.getMessage(), "Reorder failed");
            }
            rebuild(null);
        }

        private void error(String message, String title) {
            popup.setVisible(false);
            JOptionPane.showMessageDialog(SwingUtilities.getWindowAncestor(invoker), message, title,
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private static String displayPath(JuliaPresetStore store) {
        String path = store.file().toString();
        String home = System.getProperty("user.home");
        return home != null && path.startsWith(home) ? "~" + path.substring(home.length()).replace('\\', '/') : path;
    }

    /** The list of rows; draws the drop line while a row is being dragged, and follows the scroll pane's width. */
    private static final class ListPanel extends JPanel implements Scrollable {
        private static final long serialVersionUID = 1L;

        private int dropLineY = -1;

        void setDropLine(int y) {
            if (y != dropLineY) {
                dropLineY = y;
                repaint();
            }
        }

        @Override
        protected void paintChildren(Graphics g) {
            super.paintChildren(g);
            if (dropLineY >= 0) {
                g.setColor(Theme.color(Token.ACCENT));
                g.fillRect(PAD - 4, dropLineY - 1, getWidth() - 2 * (PAD - 4), 2);
            }
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 24;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return visibleRect.height - ROW_H;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /** The little pill with the number of presets. */
    private static final class CountChip extends JComponent {
        private static final long serialVersionUID = 1L;

        private String text = "0";

        void set(int total) {
            text = Integer.toString(total);
            revalidate();
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(Theme.monoMedium(11f));
            return new Dimension(fm.stringWidth(text) + 14, 18);
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
                g2.setColor(Theme.color(Token.SURFACE_2));
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), getHeight(), getHeight()));
                g2.setFont(Theme.monoMedium(11f));
                g2.setColor(Theme.color(Token.MUTED));
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(text, (getWidth() - fm.stringWidth(text)) / 2,
                        (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            } finally {
                g2.dispose();
            }
        }
    }

    /** One preset. */
    private static final class Row extends JPanel {
        private static final long serialVersionUID = 1L;

        private final Entry entry;
        private final boolean active;
        private final Session session;
        private final String subtitle;
        private boolean hover;
        private int hoverButton = -1;
        private boolean dragging;
        private JTextField renameField;

        Row(Entry entry, boolean active, Session session) {
            super(null);
            this.entry = entry;
            this.active = active;
            this.session = session;
            this.subtitle = JuliaPresetStore.format(entry.re(), entry.im());
            setOpaque(false);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setPreferredSize(new Dimension(WIDTH, ROW_H));
            setMinimumSize(new Dimension(0, ROW_H));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_H));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            javax.swing.ToolTipManager.sharedInstance().registerComponent(this);

            MouseAdapter mouse = new MouseAdapter() {
                private boolean pressedOnGrip;

                @Override
                public void mouseEntered(MouseEvent e) {
                    track(e.getPoint());
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    track(e.getPoint());
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    if (dragging) {
                        return;
                    }
                    Point pointer = MouseInfo.getPointerInfo() == null ? null : MouseInfo.getPointerInfo().getLocation();
                    if (pointer == null || !isShowing()) {
                        track(null);
                        return;
                    }
                    SwingUtilities.convertPointFromScreen(pointer, Row.this);
                    track(contains(pointer) ? pointer : null);
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    pressedOnGrip = renameField == null && e.getButton() == MouseEvent.BUTTON1 && e.getX() < gripEnd();
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (!pressedOnGrip) {
                        return;
                    }
                    dragging = true;
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    repaint();
                    ListPanel list = (ListPanel) getParent();
                    Point p = SwingUtilities.convertPoint(Row.this, e.getPoint(), list);
                    list.setDropLine(dropY(list, p.y));
                    // Keep the dragged row in view while the pointer is near the edge of the scroll pane.
                    list.scrollRectToVisible(new Rectangle(0, p.y - 12, 1, 24));
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (!dragging) {
                        return;
                    }
                    ListPanel list = (ListPanel) getParent();
                    Point p = SwingUtilities.convertPoint(Row.this, e.getPoint(), list);
                    int target = dropIndex(list, p.y);
                    dragging = false;
                    pressedOnGrip = false;
                    list.setDropLine(-1);
                    setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                    int from = indexIn(list);
                    // Dropping "below row i" means index i, adjusted for the row leaving its old place.
                    session.move(entry, target > from ? target - 1 : target);
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (renameField != null) {
                        int b = renameButtonAt(e.getPoint());
                        if (e.getButton() == MouseEvent.BUTTON1 && b == 0) {
                            commitRename();
                        } else if (e.getButton() == MouseEvent.BUTTON1 && b == 1) {
                            cancelRename();
                        }
                        return;
                    }
                    if (e.getButton() != MouseEvent.BUTTON1 || e.getX() < gripEnd()) {
                        return;
                    }
                    switch (buttonAt(e.getPoint())) {
                        case 0 -> startRename();
                        case 1 -> session.delete(entry);
                        default -> session.use(entry);
                    }
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        private int indexIn(JPanel list) {
            for (int i = 0; i < list.getComponentCount(); i++) {
                if (list.getComponent(i) == this) {
                    return i;
                }
            }
            return 0;
        }

        /** Index at which a dragged row would be dropped: the number of rows whose middle is above y. */
        private static int dropIndex(JPanel list, int y) {
            int index = 0;
            for (Component c : list.getComponents()) {
                if (c instanceof Row && y > c.getY() + c.getHeight() / 2) {
                    index++;
                }
            }
            return index;
        }

        private static int dropY(JPanel list, int y) {
            int index = dropIndex(list, y);
            int count = 0;
            for (Component c : list.getComponents()) {
                if (c instanceof Row) {
                    if (count == index) {
                        return c.getY();
                    }
                    count++;
                }
            }
            return count == 0 ? 0 : list.getComponent(count - 1).getY() + ROW_H;
        }

        private int gripEnd() {
            return GRIP_W + 4;
        }

        private void track(Point p) {
            boolean over = p != null && renameField == null;
            int button = over ? buttonAt(p, true) : -1;
            if (over != hover || button != hoverButton) {
                hover = over;
                hoverButton = button;
                repaint();
            }
        }

        private Rectangle buttonBounds(int index) {
            int right = getWidth() - 8;
            int x = right - (2 - index) * BUTTON - (1 - index) * BUTTON_GAP;
            return new Rectangle(x, (ROW_H - BUTTON) / 2, BUTTON, BUTTON);
        }

        private int buttonAt(Point p) {
            return buttonAt(p, hover);
        }

        private int buttonAt(Point p, boolean visible) {
            if (!visible || p == null) {
                return -1;
            }
            for (int i = 0; i < 2; i++) {
                if (buttonBounds(i).contains(p)) {
                    return i;
                }
            }
            return -1;
        }

        /** In rename mode: 0 = save, 1 = cancel. */
        private Rectangle renameButtonBounds(int index) {
            return buttonBounds(index);
        }

        private int renameButtonAt(Point p) {
            for (int i = 0; i < 2; i++) {
                if (renameButtonBounds(i).contains(p)) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public String getToolTipText(MouseEvent e) {
            if (renameField != null) {
                int b = renameButtonAt(e.getPoint());
                return b == 0 ? "Save name" : b == 1 ? "Cancel" : null;
            }
            if (e.getX() < gripEnd()) {
                return "Drag to reorder";
            }
            int button = buttonAt(e.getPoint());
            if (button >= 0) {
                return button == 0 ? "Rename" : "Delete";
            }
            return "<html><b>" + escape(entry.name()) + "</b><br>c " + subtitle + "</html>";
        }

        private static String escape(String text) {
            return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }

        private int textX() {
            return GRIP_W + 4 + THUMB_W + 12;
        }

        void startRename() {
            if (renameField != null) {
                return;
            }
            hover = false;
            hoverButton = -1;
            JTextField field = new JTextField(entry.name());
            renameField = field;
            field.setFont(Theme.sansMedium(13f));
            field.getAccessibleContext().setAccessibleName("Preset name");
            field.addActionListener(_ -> commitRename());
            field.registerKeyboardAction(_ -> cancelRename(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                    JComponent.WHEN_FOCUSED);
            field.addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusLost(java.awt.event.FocusEvent e) {
                    commitRename();
                }
            });
            add(field);
            revalidate();
            repaint();
            SwingUtilities.invokeLater(() -> {
                field.requestFocusInWindow();
                field.selectAll();
            });
        }

        private boolean renameDone;

        @Override
        public void doLayout() {
            if (renameField != null) {
                int x = textX();
                renameField.setBounds(x, 6, Math.max(40, renameButtonBounds(0).x - 6 - x), 24);
            }
        }

        private void commitRename() {
            if (renameDone || renameField == null) {
                return;
            }
            renameDone = true;
            session.rename(entry, renameField.getText());
        }

        private void cancelRename() {
            if (renameDone || renameField == null) {
                return;
            }
            renameDone = true;
            session.rebuild(null);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                int w = getWidth();

                if (dragging) {
                    g2.setColor(Theme.color(Token.ACCENT_SOFT));
                } else if (active) {
                    g2.setColor(Theme.withAlpha(Theme.color(Token.ACCENT_SOFT), 160));
                } else if (hover || renameField != null) {
                    g2.setColor(Theme.color(Token.HOVER));
                } else {
                    g2.setColor(new Color(0, 0, 0, 0));
                }
                g2.fill(new RoundRectangle2D.Float(0, 2, w, ROW_H - 4, 10, 10));

                // Drag handle: two columns of three dots.
                g2.setColor(hover || dragging ? Theme.color(Token.MUTED) : Theme.withAlpha(Theme.color(Token.MUTED), 110));
                int gx = (GRIP_W - 8) / 2 + 2;
                int gy = (ROW_H - 14) / 2;
                for (int r = 0; r < 3; r++) {
                    for (int c = 0; c < 2; c++) {
                        g2.fillOval(gx + c * 6, gy + r * 6, 3, 3);
                    }
                }

                // Picture.
                int tx = GRIP_W + 4;
                int ty = (ROW_H - THUMB_H) / 2;
                RoundRectangle2D thumb = new RoundRectangle2D.Float(tx, ty, THUMB_W, THUMB_H, 8, 8);
                java.awt.Shape oldClip = g2.getClip();
                g2.clip(thumb);
                BufferedImage picture = session.picture(entry);
                if (picture != null) {
                    g2.drawImage(picture, tx, ty, THUMB_W, THUMB_H, null);
                } else {
                    g2.setColor(Theme.color(Token.SURFACE_2));
                    g2.fillRect(tx, ty, THUMB_W, THUMB_H);
                }
                g2.setClip(oldClip);
                g2.setStroke(new BasicStroke(active ? 2f : 1f));
                g2.setColor(active ? Theme.color(Token.ACCENT) : Theme.withAlpha(Theme.color(Token.TEXT), 46));
                float inset = active ? 1f : 0.5f;
                g2.draw(new RoundRectangle2D.Float(tx + inset, ty + inset, THUMB_W - 2 * inset, THUMB_H - 2 * inset, 8, 8));

                int x = textX();
                int right = w - 8 - (2 * BUTTON + BUTTON_GAP) - 8;
                if (renameField == null) {
                    g2.setFont(Theme.sansMedium(13f));
                    g2.setColor(Theme.color(Token.TEXT));
                    g2.drawString(fit(g2.getFontMetrics(), entry.name(), right - x), x, ROW_H / 2 - 4);
                }
                g2.setFont(Theme.mono(11f));
                g2.setColor(Theme.color(Token.MUTED));
                g2.drawString(fit(g2.getFontMetrics(), "c " + subtitle, right - x), x, ROW_H / 2 + 14);

                if (renameField != null) {
                    drawButton(g2, 0, Icons.Glyph.CHECK, true, false);
                    drawButton(g2, 1, Icons.Glyph.CLOSE, false, false);
                } else if (hover) {
                    drawButton(g2, 0, Icons.Glyph.EDIT, false, hoverButton == 0);
                    drawButton(g2, 1, Icons.Glyph.TRASH, false, hoverButton == 1);
                } else if (active) {
                    Rectangle r = buttonBounds(0);
                    Icons.paint(Icons.Glyph.CHECK, g2, r.x + (2 * BUTTON + BUTTON_GAP - 16) / 2, r.y + 5, 16,
                            Theme.color(Token.ACCENT));
                }
            } finally {
                g2.dispose();
            }
        }

        private void drawButton(Graphics2D g2, int index, Icons.Glyph glyph, boolean primary, boolean over) {
            Rectangle r = buttonBounds(index);
            if (primary) {
                g2.setColor(Theme.color(Token.ACCENT));
            } else {
                g2.setColor(over ? Theme.color(Token.BORDER) : Theme.color(Token.SURFACE_2));
            }
            g2.fill(new RoundRectangle2D.Float(r.x, r.y, r.width, r.height, 8, 8));
            Icons.paint(glyph, g2, r.x + 5, r.y + 5, 16, primary ? Theme.color(Token.ON_ACCENT) : Theme.color(Token.TEXT));
        }

        private static String fit(FontMetrics fm, String text, int width) {
            if (fm.stringWidth(text) <= width) {
                return text;
            }
            String ellipsis = "…";
            int end = text.length();
            while (end > 1 && fm.stringWidth(text.substring(0, end) + ellipsis) > width) {
                end--;
            }
            return text.substring(0, end).trim() + ellipsis;
        }
    }
}

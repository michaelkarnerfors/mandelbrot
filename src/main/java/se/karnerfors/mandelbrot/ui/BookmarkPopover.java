package se.karnerfors.mandelbrot.ui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GridLayout;
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
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.filechooser.FileNameExtensionFilter;

import se.karnerfors.mandelbrot.io.Bookmark;
import se.karnerfors.mandelbrot.io.BookmarkStore;
import se.karnerfors.mandelbrot.io.MandelbrotConfig;
import se.karnerfors.mandelbrot.io.Thumbnails;
import se.karnerfors.mandelbrot.render.RenderAlgorithm;
import se.karnerfors.mandelbrot.ui.Theme.Token;

/**
 * The popover under the Bookmarks button: your saved places as a grid of cards, each with a picture, a name
 * and a one-line description of where it is. Click a card to go there. Hovering a card shows rename,
 * export and delete. "Add view" bookmarks what is on screen now; "Import" reads bookmark files, saved
 * views from the old Save button, and PNGs exported by this app.
 */
public final class BookmarkPopover {

    /** What the popover needs from the application. */
    public interface Host {
        /** The view on screen right now. */
        MandelbrotConfig currentView();

        /** What the canvas looks like right now. */
        BufferedImage snapshot();

        /** Go to this bookmark. */
        void open(Bookmark bookmark);
    }

    private static final int WIDTH = 442;
    private static final int PAD = 12;
    private static final int GAP = 8;
    private static final int COLUMNS = 3;
    private static final int CARD_W = (WIDTH - 2 * PAD - (COLUMNS - 1) * GAP) / COLUMNS;
    private static final int THUMB_H = CARD_W * 2 / 3;
    private static final int CARD_H = THUMB_H + 46;
    private static final int GRID_VIEW_HEIGHT = 300;

    private BookmarkPopover() {
    }

    public static void show(Component invoker, BookmarkStore store, Host host) {
        new Session(invoker, store, host).show();
    }

    /** One opening of the popover. */
    private static final class Session {
        private final Component invoker;
        private final BookmarkStore store;
        private final Host host;
        private final JPopupMenu popup = new JPopupMenu();
        private final JPanel grid = new JPanel(new GridLayout(0, COLUMNS, GAP, GAP));
        private final JPanel gridHolder = new JPanel(new BorderLayout());
        private final JTextField filter = new JTextField();
        private final CountChip count = new CountChip();
        private final List<Card> cards = new ArrayList<>();

        Session(Component invoker, BookmarkStore store, Host host) {
            this.invoker = invoker;
            this.store = store;
            this.host = host;
        }

        void show() {
            store.reload();
            popup.setBorder(new LineBorder(Theme.color(Token.BORDER)));
            popup.setBackground(Theme.color(Token.SURFACE));
            popup.setLightWeightPopupEnabled(true);

            JPanel content = new JPanel();
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            content.setBackground(Theme.color(Token.SURFACE));

            content.add(header());
            content.add(filterRow());

            grid.setOpaque(false);
            gridHolder.setOpaque(false);
            gridHolder.setBorder(new EmptyBorder(2, PAD, PAD - 2, PAD));
            gridHolder.add(grid, BorderLayout.NORTH);
            JScrollPane scroll = new JScrollPane(gridHolder, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.setOpaque(false);
            scroll.getViewport().setOpaque(false);
            scroll.getVerticalScrollBar().setUnitIncrement(24);
            scroll.setPreferredSize(new Dimension(WIDTH, GRID_VIEW_HEIGHT));
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

            ThemedLabel footer = new ThemedLabel(displayPath(store), Token.MUTED, () -> Theme.mono(11f));
            footer.setBorder(new EmptyBorder(7, PAD, 8, PAD));
            footer.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(footer);

            content.setPreferredSize(new Dimension(WIDTH, content.getPreferredSize().height));
            popup.add(content);
            rebuild();
            popup.show(invoker, Math.min(0, invoker.getWidth() - WIDTH), invoker.getHeight() + 4);
        }

        private JComponent header() {
            JPanel row = new JPanel();
            row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
            row.setOpaque(false);
            row.setBorder(new EmptyBorder(PAD, PAD, 8, PAD));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));

            ThemedLabel title = new ThemedLabel("Bookmarks", Token.TEXT, () -> Theme.sansSemiBold(14f));
            row.add(title);
            row.add(Box.createHorizontalStrut(8));
            row.add(count);
            row.add(Box.createHorizontalGlue());

            AppButton importButton = new AppButton("Import", Icons.Glyph.RENDER, AppButton.Style.OUTLINE, true);
            importButton.setToolTipText("Import bookmarks, saved views or exported PNGs");
            importButton.addActionListener(_ -> importFile());
            AppButton add = new AppButton("Add view", Icons.Glyph.PLUS, AppButton.Style.PRIMARY, true);
            add.setToolTipText("Bookmark what is on screen now");
            add.addActionListener(_ -> addCurrent());
            row.add(importButton);
            row.add(Box.createHorizontalStrut(6));
            row.add(add);
            for (Component c : row.getComponents()) {
                if (c instanceof JComponent jc) {
                    jc.setAlignmentY(Component.CENTER_ALIGNMENT);
                }
            }
            return row;
        }

        private JComponent filterRow() {
            filter.putClientProperty("JTextField.placeholderText", "Filter by name");
            filter.putClientProperty("JTextField.leadingIcon", Icons.of(Icons.Glyph.SEARCH, 14));
            filter.putClientProperty("JTextField.showClearButton", Boolean.TRUE);
            filter.getAccessibleContext().setAccessibleName("Filter bookmarks by name");
            filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                @Override
                public void insertUpdate(javax.swing.event.DocumentEvent e) {
                    rebuild();
                }

                @Override
                public void removeUpdate(javax.swing.event.DocumentEvent e) {
                    rebuild();
                }

                @Override
                public void changedUpdate(javax.swing.event.DocumentEvent e) {
                    rebuild();
                }
            });
            JPanel row = new JPanel(new BorderLayout());
            row.setOpaque(false);
            row.setBorder(new EmptyBorder(0, PAD, 8, PAD));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
            row.add(filter, BorderLayout.CENTER);
            return row;
        }

        /** Rebuilds the cards from the store, honouring the filter. */
        void rebuild() {
            rebuild(null);
        }

        void rebuild(String renameId) {
            grid.removeAll();
            cards.clear();
            MandelbrotConfig now = host.currentView();
            String needle = filter.getText().trim().toLowerCase(Locale.ROOT);
            int shown = 0;
            for (Bookmark bookmark : store.all()) {
                if (!needle.isEmpty() && !bookmark.name().toLowerCase(Locale.ROOT).contains(needle)) {
                    continue;
                }
                Card card = new Card(bookmark, sameView(bookmark.view(), now), this);
                cards.add(card);
                grid.add(card);
                shown++;
            }
            // Pad the last row so cards keep their size when there are one or two of them.
            while (shown > 0 && shown % COLUMNS != 0) {
                JPanel filler = new JPanel();
                filler.setOpaque(false);
                grid.add(filler);
                shown++;
            }
            if (cards.isEmpty()) {
                String text = store.all().isEmpty() ? "No bookmarks yet. Use Add view to keep this place."
                        : "Nothing matches \"" + filter.getText().trim() + "\".";
                ThemedLabel empty = new ThemedLabel(text, Token.MUTED, () -> Theme.sans(12f));
                empty.setBorder(new EmptyBorder(30, 0, 30, 0));
                empty.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
                grid.setLayout(new BorderLayout());
                grid.add(empty, BorderLayout.CENTER);
            } else {
                grid.setLayout(new GridLayout(0, COLUMNS, GAP, GAP));
            }
            count.set(cards.size(), store.all().size());
            grid.revalidate();
            grid.repaint();
            if (renameId != null) {
                for (Card card : cards) {
                    if (card.bookmark.id().equals(renameId)) {
                        card.startRename();
                        // Scroll it into view if it sits outside the visible part of the grid.
                        grid.scrollRectToVisible(card.getBounds());
                    }
                }
            }
        }

        private static boolean sameView(MandelbrotConfig a, MandelbrotConfig b) {
            if (a == null || b == null) {
                return false;
            }
            MandelbrotConfig.ViewportData va = a.viewport();
            MandelbrotConfig.ViewportData vb = b.viewport();
            if (va.real() != vb.real() || va.imaginary() != vb.imaginary() || va.zoom() != vb.zoom()) {
                return false;
            }
            if (a.fractal().algorithm() != b.fractal().algorithm()) {
                return false;
            }
            return a.fractal().algorithm() != RenderAlgorithm.JULIA || a.fractal().cReal() == b.fractal().cReal()
                    && a.fractal().cImaginary() == b.fractal().cImaginary();
        }

        // --- actions --------------------------------------------------------

        private void addCurrent() {
            try {
                MandelbrotConfig view = host.currentView();
                String thumbnail = null;
                try {
                    thumbnail = Thumbnails.encode(host.snapshot());
                } catch (IOException | RuntimeException ex) {
                    // A bookmark without a picture is still a bookmark.
                }
                Bookmark added = store.add(nextDefaultName(), view, thumbnail);
                filter.setText("");
                rebuild(added.id());
            } catch (IOException ex) {
                error("Could not save the bookmark: " + ex.getMessage(), "Add view failed");
            }
        }

        private String nextDefaultName() {
            int n = store.all().size() + 1;
            while (true) {
                String candidate = "Bookmark " + n;
                boolean taken = false;
                for (Bookmark b : store.all()) {
                    if (b.name().equalsIgnoreCase(candidate)) {
                        taken = true;
                        break;
                    }
                }
                if (!taken) {
                    return candidate;
                }
                n++;
            }
        }

        void open(Bookmark bookmark) {
            popup.setVisible(false);
            host.open(bookmark);
        }

        void rename(Bookmark bookmark, String newName) {
            try {
                store.rename(bookmark.id(), newName);
            } catch (IOException ex) {
                error("Could not rename: " + ex.getMessage(), "Rename failed");
            }
            rebuild();
        }

        void delete(Bookmark bookmark) {
            popup.setVisible(false);
            int answer = JOptionPane.showConfirmDialog(window(), "Delete the bookmark \"" + bookmark.name() + "\"?",
                    "Delete bookmark", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer == JOptionPane.YES_OPTION) {
                try {
                    store.delete(bookmark.id());
                } catch (IOException ex) {
                    error("Could not delete: " + ex.getMessage(), "Delete failed");
                }
            }
            BookmarkPopover.show(invoker, store, host);
        }

        void export(Bookmark bookmark) {
            popup.setVisible(false);
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Export bookmark");
            chooser.setFileFilter(new FileNameExtensionFilter("Bookmark (*.json)", "json"));
            chooser.setSelectedFile(new File(fileSafe(bookmark.name()) + ".json"));
            if (chooser.showSaveDialog(window()) == JFileChooser.APPROVE_OPTION) {
                File file = chooser.getSelectedFile();
                if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".json")) {
                    file = new File(file.getParentFile(), file.getName() + ".json");
                }
                try {
                    store.export(file.toPath(), bookmark);
                } catch (IOException ex) {
                    error("Could not export: " + ex.getMessage(), "Export failed");
                }
            }
            BookmarkPopover.show(invoker, store, host);
        }

        private void importFile() {
            popup.setVisible(false);
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Import bookmarks");
            chooser.setFileFilter(new FileNameExtensionFilter("Bookmarks, saved views and exported images (*.json, *.png)",
                    "json", "png"));
            if (chooser.showOpenDialog(window()) == JFileChooser.APPROVE_OPTION) {
                try {
                    store.importFrom(chooser.getSelectedFile().toPath());
                } catch (IOException ex) {
                    error("Could not import: " + ex.getMessage(), "Import failed");
                }
            }
            BookmarkPopover.show(invoker, store, host);
        }

        private java.awt.Window window() {
            return SwingUtilities.getWindowAncestor(invoker);
        }

        private void error(String message, String title) {
            JOptionPane.showMessageDialog(window(), message, title, JOptionPane.ERROR_MESSAGE);
        }
    }

    private static String fileSafe(String name) {
        String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return safe.isEmpty() ? "bookmark" : safe;
    }

    private static String displayPath(BookmarkStore store) {
        String path = store.file().toString();
        String home = System.getProperty("user.home");
        return home != null && path.startsWith(home) ? "~" + path.substring(home.length()).replace('\\', '/') : path;
    }

    /** "Mandelbrot · 8.4e+1" or "c −0.123 + 0.745i". */
    static String describe(MandelbrotConfig view) {
        if (view.fractal().algorithm() == RenderAlgorithm.JULIA) {
            double re = view.fractal().cReal();
            double im = view.fractal().cImaginary();
            return String.format(Locale.ROOT, "c %s%.3f %s %.3fi", re < 0 ? "−" : "", Math.abs(re),
                    im < 0 ? "−" : "+", Math.abs(im));
        }
        String zoom = String.format(Locale.ROOT, "%.1e", view.viewport().zoom()).replaceFirst("e([+-])0*(\\d)", "e$1$2");
        return "Mandelbrot · " + zoom;
    }

    /** The little pill with the number of bookmarks. */
    private static final class CountChip extends JComponent {
        private static final long serialVersionUID = 1L;

        private String text = "0";

        void set(int shown, int total) {
            text = shown == total ? Integer.toString(total) : shown + " of " + total;
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

    /** One bookmark: picture, name, description; rename/export/delete appear while the pointer is over it. */
    private static final class Card extends JPanel {
        private static final long serialVersionUID = 1L;

        private static final int BUTTON = 26;
        private static final int BUTTON_GAP = 6;
        private static final int BUTTONS = 4;
        private static final String[] BUTTON_TIPS = { "Go to this view", "Rename", "Export as JSON file", "Delete" };
        private static final Icons.Glyph[] BUTTON_GLYPHS = { Icons.Glyph.OPEN, Icons.Glyph.EDIT, Icons.Glyph.UPLOAD, Icons.Glyph.TRASH };

        private final Bookmark bookmark;
        private final boolean current;
        private final Session session;
        private final transient BufferedImage picture;
        private final transient Color[] fallback;
        private final String subtitle;
        private boolean hover;
        private int hoverButton = -1;
        private JTextField renameField;

        Card(Bookmark bookmark, boolean current, Session session) {
            super(null);
            this.bookmark = bookmark;
            this.current = current;
            this.session = session;
            this.picture = Thumbnails.decode(bookmark.thumbnail());
            this.fallback = picture == null ? bookmark.view().toPalette().gradient() : null;
            this.subtitle = describe(bookmark.view());
            setOpaque(false);
            setPreferredSize(new Dimension(CARD_W, CARD_H));
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            javax.swing.ToolTipManager.sharedInstance().registerComponent(this);

            MouseAdapter mouse = new MouseAdapter() {
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
                    Point pointer = MouseInfo.getPointerInfo() == null ? null : MouseInfo.getPointerInfo().getLocation();
                    if (pointer == null || !isShowing()) {
                        track(null);
                        return;
                    }
                    SwingUtilities.convertPointFromScreen(pointer, Card.this);
                    track(contains(pointer) ? pointer : null);
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (renameField != null || e.getButton() != MouseEvent.BUTTON1) {
                        return;
                    }
                    int button = buttonAt(e.getPoint());
                    switch (button) {
                        case 1 -> startRename();
                        case 2 -> session.export(bookmark);
                        case 3 -> session.delete(bookmark);
                        default -> session.open(bookmark); // the Go button, or a click that misses every button
                    }
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        private void track(Point p) {
            boolean over = p != null && renameField == null;
            int button = over ? buttonAt(p) : -1;
            if (over != hover || button != hoverButton) {
                hover = over;
                hoverButton = button;
                repaint();
            }
        }

        private Rectangle buttonBounds(int index) {
            int total = BUTTONS * BUTTON + (BUTTONS - 1) * BUTTON_GAP;
            int x = (CARD_W - total) / 2 + index * (BUTTON + BUTTON_GAP);
            int y = (THUMB_H - BUTTON) / 2;
            return new Rectangle(x, y, BUTTON, BUTTON);
        }

        private int buttonAt(Point p) {
            if (!hover || p == null) {
                return -1;
            }
            for (int i = 0; i < BUTTONS; i++) {
                if (buttonBounds(i).contains(p)) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public String getToolTipText(MouseEvent e) {
            int button = buttonAt(e.getPoint());
            if (button >= 0) {
                return BUTTON_TIPS[button];
            }
            if (e.getY() >= THUMB_H) {
                String created = bookmark.created() == null ? "" : bookmark.created().replace('T', ' ');
                if (created.length() >= 16) {
                    created = created.substring(0, 16) + " UTC";
                }
                return "<html><b>" + escape(bookmark.name()) + "</b><br>" + subtitle + "<br>Added " + created + "</html>";
            }
            return null;
        }

        private static String escape(String text) {
            return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }

        void startRename() {
            if (renameField != null) {
                return;
            }
            hover = false;
            hoverButton = -1;
            JTextField field = new JTextField(bookmark.name());
            renameField = field;
            field.setFont(Theme.sansMedium(12f));
            field.setBounds(0, THUMB_H + 4, CARD_W, 24);
            field.getAccessibleContext().setAccessibleName("Bookmark name");
            boolean[] done = { false };
            Runnable commit = () -> {
                if (done[0]) {
                    return;
                }
                done[0] = true;
                session.rename(bookmark, field.getText());
            };
            Runnable cancel = () -> {
                if (done[0]) {
                    return;
                }
                done[0] = true;
                session.rebuild();
            };
            field.addActionListener(_ -> commit.run());
            field.registerKeyboardAction(_ -> cancel.run(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                    JComponent.WHEN_FOCUSED);
            field.addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusLost(java.awt.event.FocusEvent e) {
                    commit.run();
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

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

                RoundRectangle2D thumb = new RoundRectangle2D.Float(0, 0, CARD_W, THUMB_H, 10, 10);
                java.awt.Shape oldClip = g2.getClip();
                g2.clip(thumb);
                if (picture != null) {
                    g2.drawImage(picture, 0, 0, CARD_W, THUMB_H, null);
                } else {
                    for (int x = 0; x < CARD_W; x++) {
                        g2.setColor(fallback[Math.min(fallback.length - 1, x * fallback.length / CARD_W)]);
                        g2.fillRect(x, 0, 1, THUMB_H);
                    }
                }
                if (hover) {
                    g2.setColor(new Color(0, 0, 0, 105));
                    g2.fillRect(0, 0, CARD_W, THUMB_H);
                }
                g2.setClip(oldClip);

                g2.setStroke(new BasicStroke(current ? 2f : 1f));
                g2.setColor(current ? Theme.color(Token.ACCENT)
                        : hover ? Theme.color(Token.MUTED) : Theme.withAlpha(Theme.color(Token.TEXT), 46));
                float inset = current ? 1f : 0.5f;
                g2.draw(new RoundRectangle2D.Float(inset, inset, CARD_W - 2 * inset, THUMB_H - 2 * inset, 10, 10));

                // Chips on the picture.
                if (current) {
                    chip(g2, "Current", 6, 6, Theme.color(Token.ACCENT), Theme.color(Token.ON_ACCENT), false);
                }
                if (bookmark.view().fractal().algorithm() == RenderAlgorithm.JULIA) {
                    chip(g2, "Julia", 6, 6, new Color(0, 0, 0, 150), Color.WHITE, true);
                }

                if (hover) {
                    for (int i = 0; i < BUTTONS; i++) {
                        Rectangle r = buttonBounds(i);
                        boolean over = i == hoverButton;
                        Color accent = Theme.color(Token.ACCENT);
                        g2.setColor(i == 0 ? (over ? accent.darker() : accent)
                                : over ? new Color(0, 0, 0, 215) : new Color(0, 0, 0, 150));
                        g2.fill(new RoundRectangle2D.Float(r.x, r.y, r.width, r.height, 8, 8));
                        if (over) {
                            g2.setColor(new Color(255, 255, 255, 90));
                            g2.setStroke(new BasicStroke(1f));
                            g2.draw(new RoundRectangle2D.Float(r.x + 0.5f, r.y + 0.5f, r.width - 1, r.height - 1, 8, 8));
                        }
                        Icons.paint(BUTTON_GLYPHS[i], g2, r.x + 5, r.y + 5, 16, Color.WHITE);
                    }
                }

                if (renameField == null) {
                    g2.setFont(Theme.sansMedium(12.5f));
                    g2.setColor(Theme.color(Token.TEXT));
                    g2.drawString(fit(g2.getFontMetrics(), bookmark.name(), CARD_W), 1, THUMB_H + 17);
                }
                g2.setFont(Theme.mono(10f));
                g2.setColor(Theme.color(Token.MUTED));
                g2.drawString(fit(g2.getFontMetrics(), subtitle, CARD_W), 1, THUMB_H + 34);
            } finally {
                g2.dispose();
            }
        }

        /** A small pill; left-aligned at x, or right-aligned to the picture's edge when {@code right} is true. */
        private static void chip(Graphics2D g2, String text, int x, int y, Color fill, Color ink, boolean right) {
            g2.setFont(Theme.sansSemiBold(10f));
            FontMetrics fm = g2.getFontMetrics();
            int w = fm.stringWidth(text) + 12;
            int h = 16;
            int left = right ? CARD_W - 6 - w : x;
            g2.setColor(fill);
            g2.fill(new RoundRectangle2D.Float(left, y, w, h, h, h));
            g2.setColor(ink);
            g2.drawString(text, left + 6, y + (h - fm.getHeight()) / 2 + fm.getAscent());
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

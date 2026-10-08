package se.karnerfors.mandelbrot.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

import se.karnerfors.mandelbrot.render.RenderJob;
import se.karnerfors.mandelbrot.render.RenderQueue;
import se.karnerfors.mandelbrot.ui.Theme.Token;

/**
 * The render queue, as a popover above the status bar chip: what is drawing (with progress and time left),
 * what waits, and what has finished or failed, with the buttons that belong to each. It follows the queue
 * live while it is open.
 */
public final class RenderQueuePopover {

    /** What the popover needs from the application besides the queue itself. */
    public interface Host {
        void open(File file);

        void showInFolder(File file);

        void openRendersFolder();
    }

    private static final int WIDTH = 440;
    private static final int MAX_LIST_HEIGHT = 372;

    private RenderQueuePopover() {
    }

    public static void show(Component invoker, RenderQueue queue, Host host) {
        new Session(invoker, queue, host).show();
    }

    private static final class Session {
        private final Component invoker;
        private final RenderQueue queue;
        private final Host host;
        private final JPopupMenu popup = new JPopupMenu();
        private final JPanel list = new ListPanel();
        private final JScrollPane scroll = new JScrollPane(list, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        private final ThemedLabel summary = new ThemedLabel("", Token.MUTED, () -> Theme.sans(12f));
        private final AppButton clear = new AppButton("Clear finished", null, AppButton.Style.GHOST, true);
        private final JPanel content = new JPanel();
        private final List<Row> rows = new ArrayList<>();
        private final Runnable listener = this::onQueueChanged;
        private String signature = "";
        private boolean reshowing;
        // The chip hides itself when the queue becomes empty, so the popover is anchored to the chip's parent,
        // at the place where the chip was, and keeps working after the chip is gone.
        private final Component anchor;
        private final int anchorRight;
        private final int anchorTop;

        Session(Component invoker, RenderQueue queue, Host host) {
            this.invoker = invoker;
            this.queue = queue;
            this.host = host;
            this.anchor = invoker.getParent() != null ? invoker.getParent() : invoker;
            this.anchorRight = invoker.getParent() != null ? invoker.getX() + invoker.getWidth() : invoker.getWidth();
            this.anchorTop = invoker.getParent() != null ? invoker.getY() : 0;
        }

        void show() {
            popup.setBorder(new LineBorder(Theme.color(Token.BORDER)));
            popup.setBackground(Theme.color(Token.SURFACE));
            popup.setLightWeightPopupEnabled(true);

            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            content.setBackground(Theme.color(Token.SURFACE));

            content.add(header());
            list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
            list.setOpaque(false);
            scroll.setBorder(javax.swing.BorderFactory.createEmptyBorder());
            scroll.setOpaque(false);
            scroll.getViewport().setOpaque(false);
            scroll.getVerticalScrollBar().setUnitIncrement(24);
            scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(scroll);
            content.add(footer());

            popup.add(content);
            popup.addPopupMenuListener(new PopupMenuListener() {
                @Override
                public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                }

                @Override
                public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                    if (!reshowing) {
                        queue.removeChangeListener(listener);
                    }
                }

                @Override
                public void popupMenuCanceled(PopupMenuEvent e) {
                    if (!reshowing) {
                        queue.removeChangeListener(listener);
                    }
                }
            });
            queue.addChangeListener(listener);
            rebuild();
            place();
        }

        private JComponent header() {
            JPanel row = new JPanel();
            row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
            row.setOpaque(false);
            row.setBorder(new EmptyBorder(12, 14, 8, 10));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
            row.add(new ThemedLabel("Render queue", Token.TEXT, () -> Theme.sansSemiBold(14f)));
            row.add(Box.createHorizontalStrut(8));
            row.add(summary);
            row.add(Box.createHorizontalGlue());
            clear.addActionListener(_ -> queue.clearFinished());
            row.add(clear);
            for (Component c : row.getComponents()) {
                ((JComponent) c).setAlignmentY(Component.CENTER_ALIGNMENT);
            }
            return row;
        }

        private JComponent footer() {
            JPanel footer = new JPanel();
            footer.setLayout(new BoxLayout(footer, BoxLayout.X_AXIS));
            footer.setBackground(Theme.color(Token.SURFACE));
            footer.setBorder(new javax.swing.border.CompoundBorder(
                    new javax.swing.border.MatteBorder(1, 0, 0, 0, Theme.color(Token.BORDER_SOFT)),
                    new EmptyBorder(8, 14, 8, 10)));
            footer.setAlignmentX(Component.LEFT_ALIGNMENT);
            footer.add(new ThemedLabel("One job at a time, at low priority", Token.MUTED, () -> Theme.sans(11.5f)));
            footer.add(Box.createHorizontalGlue());
            AppButton folder = new AppButton("Renders folder", Icons.Glyph.FOLDER, AppButton.Style.GHOST, true);
            folder.addActionListener(_ -> host.openRendersFolder());
            footer.add(folder);
            for (Component c : footer.getComponents()) {
                ((JComponent) c).setAlignmentY(Component.CENTER_ALIGNMENT);
            }
            return footer;
        }

        /** Sits above the chip, right-aligned with it. */
        private void place() {
            Dimension size = content.getPreferredSize();
            popup.show(anchor, anchorRight - WIDTH, anchorTop - size.height - 6);
        }

        private void onQueueChanged() {
            if (!anchor.isShowing()) {
                return;
            }
            if (!signature().equals(signature)) {
                rebuild();
                reshowing = true;
                try {
                    popup.setVisible(false);
                    place();
                } finally {
                    reshowing = false;
                }
            } else {
                for (Row row : rows) {
                    row.refresh();
                }
                refreshSummary();
            }
        }

        private String signature() {
            StringBuilder sb = new StringBuilder();
            for (RenderJob job : queue.jobs()) {
                sb.append(job.id()).append(':').append(job.state()).append(',');
            }
            return sb.toString();
        }

        private void rebuild() {
            signature = signature();
            list.removeAll();
            rows.clear();
            List<RenderJob> jobs = queue.jobs();
            if (jobs.isEmpty()) {
                ThemedLabel empty = new ThemedLabel("Nothing in the queue. Use Render… to add a picture.", Token.MUTED,
                        () -> Theme.sans(12f));
                empty.setBorder(new EmptyBorder(26, 14, 26, 14));
                empty.setAlignmentX(Component.LEFT_ALIGNMENT);
                list.add(empty);
            }
            boolean first = true;
            for (RenderJob job : jobs) {
                Row row = new Row(job, queue, host, !first);
                first = false;
                rows.add(row);
                list.add(row);
            }
            list.revalidate();
            Dimension natural = list.getPreferredSize();
            scroll.setPreferredSize(new Dimension(WIDTH, Math.min(MAX_LIST_HEIGHT, natural.height)));
            scroll.setMinimumSize(scroll.getPreferredSize());
            scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, scroll.getPreferredSize().height));
            content.setPreferredSize(null);
            Dimension pref = content.getPreferredSize();
            content.setPreferredSize(new Dimension(WIDTH, pref.height));
            boolean anyFinished = false;
            for (RenderJob job : jobs) {
                anyFinished |= !job.isActive();
            }
            clear.setEnabled(anyFinished);
            refreshSummary();
        }

        private void refreshSummary() {
            int rendering = queue.count(RenderJob.State.RENDERING);
            int waiting = queue.count(RenderJob.State.WAITING);
            StringBuilder sb = new StringBuilder();
            if (rendering > 0) {
                sb.append(rendering).append(" running");
            }
            if (waiting > 0) {
                sb.append(sb.length() > 0 ? " · " : "").append(waiting).append(" waiting");
            }
            summary.setText(sb.toString());
        }
    }

    /** The rows' container: always as wide as the scroll pane, so a long message cannot push the buttons out of view. */
    private static final class ListPanel extends JPanel implements javax.swing.Scrollable {
        private static final long serialVersionUID = 1L;

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle visibleRect, int orientation, int direction) {
            return 24;
        }

        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle visibleRect, int orientation, int direction) {
            return visibleRect.height;
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

    /** One job. */
    private static final class Row extends JPanel {
        private static final long serialVersionUID = 1L;

        private final RenderJob job;
        private final Bar bar = new Bar();
        private final ThemedLabel progressText = new ThemedLabel(" ", Token.MUTED, () -> Theme.mono(11f));

        Row(RenderJob job, RenderQueue queue, Host host, boolean separator) {
            super(new BorderLayout(12, 0));
            this.job = job;
            setOpaque(false);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setBorder(new javax.swing.border.CompoundBorder(
                    separator ? new javax.swing.border.MatteBorder(1, 0, 0, 0, Theme.color(Token.BORDER_SOFT))
                            : javax.swing.BorderFactory.createEmptyBorder(),
                    new EmptyBorder(10, 14, 10, 10)));

            add(new Thumb(job.thumbnail()), BorderLayout.WEST);

            JPanel body = new JPanel();
            body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
            body.setOpaque(false);

            JPanel title = new JPanel();
            title.setLayout(new BoxLayout(title, BoxLayout.X_AXIS));
            title.setOpaque(false);
            title.setAlignmentX(Component.LEFT_ALIGNMENT);
            ThemedLabel name = new ThemedLabel(job.file().getName(), Token.TEXT, () -> Theme.monoMedium(12.5f));
            name.setToolTipText(job.file().getAbsolutePath());
            title.add(name);
            title.add(Box.createHorizontalStrut(8));
            title.add(new Tag(job.state()));
            title.add(Box.createHorizontalGlue());
            for (Component c : title.getComponents()) {
                ((JComponent) c).setAlignmentY(Component.CENTER_ALIGNMENT);
            }
            body.add(title);
            body.add(Box.createVerticalStrut(2));

            String caption = job.width() + "×" + job.height() + " · " + job.supersample() + "x";
            switch (job.state()) {
                case WAITING -> body.add(caption(caption + " · " + job.paletteLabel() + heavyNote()));
                case RENDERING -> {
                    body.add(caption(caption + " · " + job.paletteLabel()));
                    body.add(Box.createVerticalStrut(5));
                    bar.setAlignmentX(Component.LEFT_ALIGNMENT);
                    body.add(bar);
                    body.add(Box.createVerticalStrut(4));
                    progressText.setAlignmentX(Component.LEFT_ALIGNMENT);
                    body.add(progressText);
                }
                case DONE -> {
                    body.add(caption(caption + " · " + RenderQueue.formatDuration(job.elapsedMillis())));
                    body.add(Box.createVerticalStrut(6));
                    body.add(buttons(button("Open", () -> host.open(job.file())),
                            button("Show in folder", () -> host.showInFolder(job.file()))));
                }
                case FAILED -> {
                    ThemedLabel reason = new ThemedLabel("<html><body style='width:250px'>" + escape(job.message()) + "</body></html>", Token.MUTED,
                            () -> Theme.sans(11.5f));
                    reason.setAlignmentX(Component.LEFT_ALIGNMENT);
                    reason.setPreferredSize(null);
                    body.add(reason);
                    body.add(Box.createVerticalStrut(6));
                    String retryLabel = job.retryFactor() > 0 ? "Retry at " + job.retryFactor() + "x" : "Retry";
                    AppButton dismiss = button("Dismiss", () -> queue.remove(job));
                    dismiss.setStyle(AppButton.Style.GHOST);
                    body.add(buttons(button(retryLabel, () -> queue.retry(job, job.retryFactor())), dismiss));
                }
            }
            add(body, BorderLayout.CENTER);

            if (job.isActive()) {
                JPanel east = new JPanel(new BorderLayout());
                east.setOpaque(false);
                AppButton cancel = AppButton.iconButton(Icons.Glyph.CLOSE,
                        job.state() == RenderJob.State.RENDERING ? "Cancel render" : "Remove from queue",
                        AppButton.Style.GHOST, true);
                cancel.addActionListener(_ -> queue.cancel(job));
                east.add(cancel, BorderLayout.NORTH);
                add(east, BorderLayout.EAST);
            }
            refresh();
        }

        private String heavyNote() {
            long need = RenderDialog.memoryNeeded(job.width(), job.height(), job.supersample());
            return need > Runtime.getRuntime().maxMemory() * 0.35
                    ? " · heavy, about " + RenderDialog.formatBytes(need) : "";
        }

        void refresh() {
            if (job.state() == RenderJob.State.RENDERING) {
                bar.set(job.progress());
                long eta = job.etaMillis();
                progressText.setText(String.format(Locale.ROOT, "%d%%", (int) Math.floor(job.progress() * 100))
                        + (eta >= 0 ? " · ETA " + RenderQueue.formatDuration(eta) : ""));
            }
        }

        private static ThemedLabel caption(String text) {
            ThemedLabel label = new ThemedLabel(text, Token.MUTED, () -> Theme.mono(11f));
            label.setAlignmentX(Component.LEFT_ALIGNMENT);
            return label;
        }

        private static AppButton button(String text, Runnable action) {
            AppButton button = new AppButton(text, null, AppButton.Style.OUTLINE, true);
            button.addActionListener(_ -> action.run());
            return button;
        }

        private static JPanel buttons(AppButton... buttons) {
            JPanel row = new JPanel();
            row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
            row.setOpaque(false);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            for (int i = 0; i < buttons.length; i++) {
                if (i > 0) {
                    row.add(Box.createHorizontalStrut(6));
                }
                row.add(buttons[i]);
            }
            row.add(Box.createHorizontalGlue());
            return row;
        }

        private static String escape(String text) {
            return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }

    /** 64 x 42 picture of what the job will draw. */
    private static final class Thumb extends JComponent {
        private static final long serialVersionUID = 1L;
        private final transient BufferedImage image;

        Thumb(BufferedImage image) {
            this.image = image;
            Dimension size = new Dimension(64, 42);
            setPreferredSize(size);
            setMinimumSize(size);
            setMaximumSize(size);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                java.awt.Shape old = g2.getClip();
                g2.clip(new RoundRectangle2D.Float(0, 0, 64, 42, 8, 8));
                if (image != null) {
                    g2.drawImage(image, 0, 0, 64, 42, null);
                } else {
                    g2.setColor(Theme.color(Token.SURFACE_2));
                    g2.fillRect(0, 0, 64, 42);
                }
                g2.setClip(old);
                g2.setColor(Theme.withAlpha(Theme.color(Token.TEXT), 46));
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, 63, 41, 8, 8));
            } finally {
                g2.dispose();
            }
        }
    }

    /** The state next to the file name: Waiting, Rendering, Done or Failed. */
    private static final class Tag extends JComponent {
        private static final long serialVersionUID = 1L;
        private final String text;
        private final Token tone;
        private final boolean pill;
        private final boolean dot;
        private final Icons.Glyph glyph;

        Tag(RenderJob.State state) {
            switch (state) {
                case RENDERING -> {
                    text = "Rendering";
                    tone = Token.ACCENT;
                    pill = true;
                    dot = true;
                    glyph = null;
                }
                case DONE -> {
                    text = "Done";
                    tone = Token.OK;
                    pill = false;
                    dot = false;
                    glyph = Icons.Glyph.CHECK;
                }
                case FAILED -> {
                    text = "Failed";
                    tone = Token.ERROR;
                    pill = false;
                    dot = false;
                    glyph = Icons.Glyph.ALERT;
                }
                default -> {
                    text = "Waiting";
                    tone = Token.MUTED;
                    pill = true;
                    dot = false;
                    glyph = null;
                }
            }
            setPreferredSize(naturalSize());
            setMaximumSize(getPreferredSize());
        }

        private Dimension naturalSize() {
            FontMetrics fm = getFontMetrics(Theme.sansMedium(11.5f));
            int w = fm.stringWidth(text) + (pill ? 16 : 0) + (dot ? 11 : 0) + (glyph != null ? 17 : 0);
            return new Dimension(w, 18);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                Color ink = Theme.color(tone);
                int x = 0;
                if (pill) {
                    g2.setColor(tone == Token.ACCENT ? Theme.color(Token.ACCENT_SOFT) : Theme.color(Token.SURFACE_2));
                    g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), getHeight(), getHeight()));
                    x = 8;
                }
                g2.setColor(ink);
                if (dot) {
                    g2.fill(new Ellipse2D.Float(x, (getHeight() - 6) / 2f, 6, 6));
                    x += 11;
                }
                if (glyph != null) {
                    Icons.paint(glyph, g2, x, (getHeight() - 13) / 2, 13, ink);
                    x += 17;
                }
                g2.setFont(Theme.sansMedium(11.5f));
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(text, x, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            } finally {
                g2.dispose();
            }
        }
    }

    /** A thin progress bar. */
    private static final class Bar extends JComponent {
        private static final long serialVersionUID = 1L;
        private double value;

        Bar() {
            setPreferredSize(new Dimension(10, 6));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));
        }

        void set(double v) {
            if (v != value) {
                value = v;
                repaint();
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Theme.color(Token.TRACK));
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), getHeight(), getHeight()));
                g2.setColor(Theme.color(Token.ACCENT));
                float w = (float) Math.max(getHeight(), getWidth() * value);
                g2.fill(new RoundRectangle2D.Float(0, 0, w, getHeight(), getHeight(), getHeight()));
            } finally {
                g2.dispose();
            }
        }
    }
}

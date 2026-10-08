package se.karnerfors.mandelbrot.ui;

import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

import se.karnerfors.mandelbrot.render.RenderAlgorithm;
import se.karnerfors.mandelbrot.ui.Theme.Token;

/**
 * The slim bar above the canvas, grouped as in the design: View | Fractal | Look | Quality,
 * then Library and Output on the right. When the window gets too narrow for the labels,
 * buttons drop their text and keep their icons.
 */
public final class TopBar extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final int HEIGHT = 48;
    private static final int MEMORY_NOTE_WIDTH = 64;
    private static final int MEMORY_NOTE_SPACE = MEMORY_NOTE_WIDTH + 8;

    /** The memory note only appears when settling would use more than this share of the Java heap. */
    private static final double MEMORY_NOTE_HEAP_SHARE = 0.35;

    private static final String SETTLE_TOOLTIP = "<html>Quality to settle at. Moving around draws a quick 1x preview,<br>"
            + "then the view refines to this factor once you stop.<br>"
            + "<i>Samples per pixel along each axis: 4x means 16 samples.</i></html>";

    public final AppButton home = AppButton.iconButton(Icons.Glyph.HOME, "Home", AppButton.Style.GHOST, false);
    public final AppButton back = AppButton.iconButton(Icons.Glyph.BACK, "Back", AppButton.Style.GHOST, false);
    public final SegmentedControl<RenderAlgorithm> fractal;
    /** Julia mode: the constant c, which opens the presets. */
    public final CChip cChip = new CChip();
    /** Mandelbrot mode: start a Julia view from the point the view is centred on. */
    public final AppButton juliaFromHere = new AppButton("Julia from here", Icons.Glyph.TARGET, AppButton.Style.OUTLINE, false);
    public final AppButton palette = new AppButton("Classic", null, AppButton.Style.OUTLINE, false);
    public final SegmentedControl<Integer> supersample;
    public final AppButton bookmarks = new AppButton("Bookmarks", Icons.Glyph.STAR, AppButton.Style.OUTLINE, false);
    public final AppButton render = new AppButton("Render…", Icons.Glyph.RENDER, AppButton.Style.PRIMARY, false);

    private final ThemedLabel supersampleLabel = new ThemedLabel("SETTLE AT", Token.MUTED, Theme::groupLabelFont);
    private final ThemedLabel memoryNote = new ThemedLabel("", Token.WARN, () -> Theme.monoMedium(12f));
    private final Component memoryNoteGap = Box.createHorizontalStrut(8);
    private final JPanel cSlot = new JPanel(new CardLayout()) {
        private static final long serialVersionUID = 1L;

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }
    };
    private final List<AppButton> labelled = new ArrayList<>();
    private int fullWidth = -1;
    private boolean compact;

    public TopBar(RenderAlgorithm algorithm, int supersampleFactor) {
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        setBorder(new EmptyBorder(0, 12, 0, 12));
        setOpaque(true);

        fractal = new SegmentedControl<>(List.of(RenderAlgorithm.values()), algorithm, RenderAlgorithm::toString);
        fractal.setToolTipText("Fractal");
        supersample = new SegmentedControl<>(List.of(1, 2, 4, 9), supersampleFactor, factor -> factor + "x");
        supersample.setToolTipText(SETTLE_TOOLTIP);
        Dimension noteSize = new Dimension(MEMORY_NOTE_WIDTH, 22);
        memoryNote.setPreferredSize(noteSize);
        memoryNote.setMinimumSize(noteSize);
        memoryNote.setMaximumSize(noteSize);
        memoryNote.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        memoryNote.setVisible(false); // takes no room until there is something to warn about
        memoryNoteGap.setVisible(false);
        juliaFromHere.setToolTipText("<html>Julia set for the point at the centre of this view.<br>"
                + "<i>Pick the place first, then look at its Julia set.</i></html>");
        cSlot.setOpaque(false);
        JPanel fromHereHolder = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        fromHereHolder.setOpaque(false);
        fromHereHolder.add(juliaFromHere);
        cSlot.add(fromHereHolder, RenderAlgorithm.MANDELBROT.name());
        cSlot.add(cChip, RenderAlgorithm.JULIA.name());
        showFractal(algorithm);
        back.setEnabled(false);
        palette.setToolTipText("Palettes");
        bookmarks.setToolTipText("Bookmarks");
        labelled.add(juliaFromHere);
        labelled.add(palette);
        labelled.add(bookmarks);
        labelled.add(render);

        add(center(home));
        add(Box.createHorizontalStrut(2));
        add(center(back));
        add(separator());
        add(center(fractal));
        add(Box.createHorizontalStrut(8));
        add(center(cSlot));
        add(separator());
        add(center(palette));
        add(separator());
        add(center(supersampleLabel));
        add(Box.createHorizontalStrut(8));
        add(center(supersample));
        add(memoryNoteGap);
        add(center(memoryNote));
        add(Box.createHorizontalGlue());
        add(center(bookmarks));
        add(Box.createHorizontalStrut(8));
        add(center(render));

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (fullWidth < 0 && !compact) {
                    fullWidth = TopBar.super.getPreferredSize().width - (memoryNote.isVisible() ? MEMORY_NOTE_SPACE : 0);
                }
                updateCompact();
            }
        });
    }

    /** Shows the c chip in Julia mode and "Julia from here" in Mandelbrot mode. */
    public void showFractal(RenderAlgorithm algorithm) {
        ((CardLayout) cSlot.getLayout()).show(cSlot, algorithm.name());
    }

    /**
     * Shows "≈ 1.2 GB" next to the settle control when drawing the current window at the chosen factor
     * would take a large share of the Java heap, so the cost of 9x is visible before it bites.
     */
    public void setMemoryEstimate(long bytes, long maxHeapBytes) {
        boolean warn = maxHeapBytes > 0 && bytes > maxHeapBytes * MEMORY_NOTE_HEAP_SHARE;
        double gib = bytes / (1024.0 * 1024.0 * 1024.0);
        String text = warn ? String.format(java.util.Locale.ROOT, "≈ %.1f GB", gib) : "";
        if (text.equals(memoryNote.getText())) {
            return; // called on every status update; only touch the label when it actually changes
        }
        memoryNote.setText(text);
        updateCompact();
        memoryNote.setToolTipText(warn
                ? String.format(java.util.Locale.ROOT,
                        "<html>Settling at this quality needs about %.1f GB for this window.<br>"
                        + "Java may use up to %.1f GB. If memory runs out, the 1x preview stays on screen.</html>",
                        gib, maxHeapBytes / (1024.0 * 1024.0 * 1024.0))
                : null);
    }

    /** Compact when the bar is narrower than it needs to be; the memory note, when shown, needs a little more room. */
    private void updateCompact() {
        boolean noteWanted = !memoryNote.getText().isEmpty();
        int needed = fullWidth + (noteWanted ? MEMORY_NOTE_SPACE : 0);
        setCompact(fullWidth > 0 && getWidth() < needed);
        boolean showNote = noteWanted && !compact;
        memoryNote.setVisible(showNote);
        memoryNoteGap.setVisible(showNote);
        revalidate();
    }

    private void setCompact(boolean value) {
        if (value == compact) {
            return;
        }
        compact = value;
        supersampleLabel.setVisible(!value);
        for (AppButton button : labelled) {
            button.setCompact(value);
        }
        revalidate();
    }

    private static JComponent center(JComponent component) {
        component.setAlignmentY(CENTER_ALIGNMENT);
        return component;
    }

    private static JComponent separator() {
        JComponent line = new JComponent() {
            private static final long serialVersionUID = 1L;

            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Theme.color(Token.BORDER));
                g.fillRect(getWidth() / 2, 0, 1, getHeight());
            }
        };
        Dimension size = new Dimension(17, 22);
        line.setPreferredSize(size);
        line.setMinimumSize(size);
        line.setMaximumSize(size);
        line.setAlignmentY(CENTER_ALIGNMENT);
        return line;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(super.getPreferredSize().width, HEIGHT);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, HEIGHT);
    }

    @Override
    protected void paintComponent(Graphics g) {
        g.setColor(Theme.color(Token.BAR));
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setColor(Theme.color(Token.BORDER));
        g.fillRect(0, getHeight() - 1, getWidth(), 1);
    }
}

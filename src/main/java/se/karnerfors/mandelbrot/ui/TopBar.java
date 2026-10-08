package se.karnerfors.mandelbrot.ui;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
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

    public final AppButton home = AppButton.iconButton(Icons.Glyph.HOME, "Home", AppButton.Style.GHOST, false);
    public final AppButton back = AppButton.iconButton(Icons.Glyph.BACK, "Back", AppButton.Style.GHOST, false);
    public final SegmentedControl<RenderAlgorithm> fractal;
    public final JComboBox<Object> juliaC = new JComboBox<>();
    public final AppButton palette = new AppButton("Palette…", null, AppButton.Style.OUTLINE, false);
    public final SegmentedControl<Integer> supersample;
    public final AppButton save = new AppButton("Save…", null, AppButton.Style.OUTLINE, false);
    public final AppButton load = new AppButton("Load…", null, AppButton.Style.OUTLINE, false);
    public final AppButton render = new AppButton("Render…", Icons.Glyph.RENDER, AppButton.Style.PRIMARY, false);

    private final ThemedLabel supersampleLabel = new ThemedLabel("SUPERSAMPLE", Token.MUTED, Theme::groupLabelFont);
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
        supersample.setToolTipText("Samples per pixel along each axis");
        juliaC.setToolTipText("Julia constant c");
        back.setEnabled(false);
        palette.setToolTipText("Edit the palette");
        labelled.add(palette);
        labelled.add(render);
        applyJuliaComboWidth();

        add(center(home));
        add(Box.createHorizontalStrut(2));
        add(center(back));
        add(separator());
        add(center(fractal));
        add(Box.createHorizontalStrut(8));
        add(center(juliaC));
        add(separator());
        add(center(palette));
        add(separator());
        add(center(supersampleLabel));
        add(Box.createHorizontalStrut(8));
        add(center(supersample));
        add(Box.createHorizontalGlue());
        add(separator());
        add(center(save));
        add(Box.createHorizontalStrut(6));
        add(center(load));
        add(Box.createHorizontalStrut(8));
        add(center(render));

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (fullWidth < 0 && !compact) {
                    fullWidth = TopBar.super.getPreferredSize().width;
                }
                setCompact(fullWidth > 0 && getWidth() < fullWidth);
            }
        });
    }

    /** A fixed width keeps the long "Mandelbrot position (...)" entry from stretching the bar. */
    private void applyJuliaComboWidth() {
        Dimension size = new Dimension(compact ? 150 : 260, 32);
        juliaC.setPreferredSize(size);
        juliaC.setMinimumSize(size);
        juliaC.setMaximumSize(size);
    }

    private void setCompact(boolean value) {
        if (value == compact) {
            return;
        }
        compact = value;
        applyJuliaComboWidth();
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

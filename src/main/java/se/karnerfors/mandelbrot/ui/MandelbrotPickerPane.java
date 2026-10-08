package se.karnerfors.mandelbrot.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;

import javax.swing.JPanel;

import se.karnerfors.mandelbrot.ui.Theme.Token;

/** The left pane of the split view: the Mandelbrot set, where the pointer chooses c for the Julia set next to it. */
public final class MandelbrotPickerPane extends JPanel {

    private static final long serialVersionUID = 1L;

    private final FractalPanel picker;
    private final ThemedLabel hint = new ThemedLabel("", Token.MUTED, () -> Theme.sans(12f));
    private final AppButton home = AppButton.iconButton(Icons.Glyph.HOME, "Show the whole Mandelbrot set",
            AppButton.Style.GHOST, true);

    public MandelbrotPickerPane(FractalPanel picker) {
        super(new BorderLayout());
        this.picker = picker;
        PaneHeader header = new PaneHeader("Mandelbrot");
        header.addItem(hint, 10);
        header.addGlue();
        header.addItem(home, 0);
        home.addActionListener(_ -> picker.goHome());
        add(header, BorderLayout.NORTH);
        add(picker, BorderLayout.CENTER);
        setMinimumSize(new Dimension(160, 120));
        hint.setText("Click or drag to set c · wheel to zoom");
    }

    public FractalPanel panel() {
        return picker;
    }

}

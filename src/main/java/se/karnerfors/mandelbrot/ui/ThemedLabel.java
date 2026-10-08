package se.karnerfors.mandelbrot.ui;

import java.awt.Color;
import java.awt.Font;
import java.util.function.Supplier;

import javax.swing.JLabel;

import se.karnerfors.mandelbrot.ui.Theme.Token;

/** A label whose colour follows a theme token and whose font survives theme switches. */
public class ThemedLabel extends JLabel {

    private static final long serialVersionUID = 1L;

    private final Token token;
    private final Supplier<Font> fontSupplier;

    public ThemedLabel(String text, Token token, Supplier<Font> fontSupplier) {
        super(text);
        this.token = token;
        this.fontSupplier = fontSupplier;
        setFont(fontSupplier.get());
    }

    @Override
    public Color getForeground() {
        return token == null ? super.getForeground() : Theme.color(token);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (fontSupplier != null) {
            setFont(fontSupplier.get());
        }
    }
}

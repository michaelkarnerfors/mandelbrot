package se.karnerfors.mandelbrot.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import se.karnerfors.mandelbrot.math.Complex;
import se.karnerfors.mandelbrot.math.Viewport;
import se.karnerfors.mandelbrot.ui.Theme.Token;

/**
 * "Go to coordinates": Re, Im and Zoom fields in a small modal dialog. Pasting a coordinate
 * such as {@code -0.7436 + 0.1318i} into the Re field fills both Re and Im.
 */
public final class GoToDialog {

    /** Receives the centre and zoom the user confirmed. */
    public interface Target {
        void goTo(Complex center, double zoom);
    }

    private static final Pattern COMPLEX = Pattern.compile(
            "^\\s*([+-]?\\d*[.,]?\\d+(?:[eE][+-]?\\d+)?)\\s*([+-])\\s*(\\d*[.,]?\\d*(?:[eE][+-]?\\d+)?)\\s*[iI]\\s*$");

    private GoToDialog() {
    }

    /**
     * Parses a complex number written as {@code a + bi} or {@code a - bi} (also with a
     * Unicode minus or a decimal comma). Returns null when the text is not in that form.
     */
    public static Complex parseComplex(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = COMPLEX.matcher(text.replace('−', '-'));
        if (!m.matches()) {
            return null;
        }
        try {
            double real = Double.parseDouble(m.group(1).replace(',', '.'));
            String imagText = m.group(3).replace(',', '.');
            double imag = imagText.isEmpty() ? 1.0 : Double.parseDouble(imagText);
            if ("-".equals(m.group(2))) {
                imag = -imag;
            }
            return Double.isFinite(real) && Double.isFinite(imag) ? Complex.fromCartesian(real, imag) : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Parses a plain decimal number, accepting a decimal comma and a Unicode minus. */
    public static double parseNumber(String text) {
        double value = Double.parseDouble(text.trim().replace('−', '-').replace(',', '.'));
        if (!Double.isFinite(value)) {
            throw new NumberFormatException("not finite: " + text);
        }
        return value;
    }

    public static void show(Window owner, Complex center, double zoom, Target target) {
        JDialog dialog = new JDialog(owner, "Go to coordinates", JDialog.ModalityType.APPLICATION_MODAL);

        JTextField re = field(Double.toString(center.real));
        JTextField im = field(Double.toString(center.imaginary));
        JTextField zm = field(Double.toString(zoom));
        ThemedLabel error = new ThemedLabel(" ", Token.ERROR, () -> Theme.sans(12f));

        re.getDocument().addDocumentListener(new DocumentListener() {
            private void check() {
                Complex pasted = parseComplex(re.getText());
                if (pasted != null) {
                    SwingUtilities.invokeLater(() -> {
                        re.setText(Double.toString(pasted.real));
                        im.setText(Double.toString(pasted.imaginary));
                    });
                }
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                check();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
            }
        });

        AppButton cancel = new AppButton("Cancel", null, AppButton.Style.OUTLINE, false);
        AppButton go = new AppButton("Go", null, AppButton.Style.PRIMARY, false);

        Runnable confirm = () -> {
            try {
                double r = parseNumber(re.getText());
                double i = parseNumber(im.getText());
                double z = parseNumber(zm.getText());
                if (z < Viewport.MIN_USER_ZOOM) {
                    error.setText("Zoom must be at least " + (int) Viewport.MIN_USER_ZOOM + ".");
                    return;
                }
                dialog.dispose();
                target.goTo(Complex.fromCartesian(r, i), z);
            } catch (NumberFormatException ex) {
                error.setText("Enter Re, Im and Zoom as decimal numbers.");
            }
        };
        go.addActionListener(_ -> confirm.run());
        cancel.addActionListener(_ -> dialog.dispose());

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setOpaque(false);
        JPanel reIm = new JPanel(new java.awt.GridLayout(1, 2, 12, 0));
        reIm.setOpaque(false);
        reIm.add(labelled("Real (Re)", re));
        reIm.add(labelled("Imaginary (Im)", im));
        form.add(reIm);
        form.add(Box.createVerticalStrut(12));
        JComponent zoomRow = labelled("Zoom, in pixels per unit", zm);
        form.add(zoomRow);
        form.add(Box.createVerticalStrut(8));
        ThemedLabel hint = new ThemedLabel("Tip: paste “−0.7436 + 0.1318i” into Re to fill both fields.", Token.MUTED,
                () -> Theme.sans(12f));
        form.add(hint);
        form.add(Box.createVerticalStrut(4));
        form.add(error);
        for (java.awt.Component child : form.getComponents()) {
            if (child instanceof JComponent component) {
                component.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            }
        }

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(cancel);
        buttons.add(go);

        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        content.add(form, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        content.registerKeyboardAction(_ -> confirm.run(), KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        content.registerKeyboardAction(_ -> dialog.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        dialog.setMinimumSize(new Dimension(440, 0));
        dialog.pack();
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(owner);
        SwingUtilities.invokeLater(() -> {
            re.requestFocusInWindow();
            re.selectAll();
        });
        dialog.setVisible(true);
    }

    private static JTextField field(String text) {
        JTextField field = new JTextField(text, 14);
        field.setFont(Theme.mono(13f));
        return field;
    }

    private static JComponent labelled(String caption, JTextField field) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setOpaque(false);
        JLabel label = new ThemedLabel(caption, Token.MUTED, () -> Theme.sansMedium(12f));
        panel.add(label, BorderLayout.NORTH);
        panel.add(field, BorderLayout.CENTER);
        return panel;
    }
}

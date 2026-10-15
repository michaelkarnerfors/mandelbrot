package se.karnerfors.mandelbrot.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.util.Hashtable;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;

import se.karnerfors.mandelbrot.render.RenderSettings;
import se.karnerfors.mandelbrot.ui.Theme.Token;

/**
 * The editor for the iteration limit, opened from the {@link IterationsChip}: a logarithmic slider from 10 to
 * 100000 and a field for an exact number. They follow each other. Moving the slider redraws the view as it goes;
 * a number typed in is applied with Enter, or when the field or the popover is left, and a number outside the
 * range is moved into it. Whole numbers only.
 */
public final class IterationsPopover {

    /** What the editor needs from the application. */
    public interface Host {
        /** The limit in use now. */
        int current();

        /** Use this limit (already inside the allowed range): the view is drawn again with it. */
        void apply(int iterations);
    }

    private static final int WIDTH = 340;
    private static final int PAD = 14;
    private static final int MAX_DIGITS = Integer.toString(RenderSettings.MAX_ITERATIONS).length();

    /** The slider has this many steps; each takes the limit a little under one per cent higher. */
    static final int SLIDER_STEPS = 1000;

    private IterationsPopover() {
    }

    /** Opens the editor above {@code invoker}. */
    public static void show(IterationsChip invoker, Host host) {
        new Session(invoker, host).show();
    }

    /**
     * The limit at a slider position: logarithmic from the lowest to the highest, rounded to three significant
     * digits so dragging gives tidy numbers (1020, not 1023). The field is for exact ones.
     */
    static int valueAt(int position) {
        double ratio = RenderSettings.MAX_ITERATIONS / (double) RenderSettings.MIN_ITERATIONS;
        double raw = RenderSettings.MIN_ITERATIONS * Math.pow(ratio, position / (double) SLIDER_STEPS);
        int value = (int) Math.round(raw);
        int digits = Integer.toString(value).length();
        if (digits > 3) {
            int scale = (int) Math.pow(10, digits - 3);
            value = (int) Math.round(value / (double) scale) * scale;
        }
        return RenderSettings.validMaxIterations(value);
    }

    /** The slider position closest to {@code iterations}. */
    static int positionOf(int iterations) {
        double ratio = RenderSettings.MAX_ITERATIONS / (double) RenderSettings.MIN_ITERATIONS;
        double fraction = Math.log(RenderSettings.validMaxIterations(iterations) / (double) RenderSettings.MIN_ITERATIONS)
                / Math.log(ratio);
        return (int) Math.max(0, Math.min(SLIDER_STEPS, Math.round(fraction * SLIDER_STEPS)));
    }

    /** What is in the field as a number, or null when it is empty. */
    private static Integer parse(String text) {
        String digits = text.trim();
        return digits.isEmpty() ? null : Integer.valueOf(digits); // the field only takes up to six digits
    }

    /** One opening of the editor. */
    private static final class Session {
        private final IterationsChip invoker;
        private final Host host;
        private final JPopupMenu popup = new JPopupMenu();
        private final JSlider slider = new JSlider(0, SLIDER_STEPS);
        private final JTextField field = new JTextField(6);
        /** The limit last handed to the application. */
        private int value;
        /** True while one control is being set from the other, so they do not answer each other. */
        private boolean syncing;

        Session(IterationsChip invoker, Host host) {
            this.invoker = invoker;
            this.host = host;
        }

        void show() {
            value = RenderSettings.validMaxIterations(host.current());
            popup.setBorder(new LineBorder(Theme.color(Token.BORDER)));
            popup.setBackground(Theme.color(Token.SURFACE));
            popup.setLightWeightPopupEnabled(true);
            invoker.setOpen(true);
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
            content.add(description());
            content.add(sliderRow());
            content.setPreferredSize(new Dimension(WIDTH, content.getPreferredSize().height));
            popup.add(content);

            setField(value);
            setSlider(value);
            // The editor opens above the status bar.
            popup.show(invoker, 0, -popup.getPreferredSize().height - 4);
            SwingUtilities.invokeLater(() -> {
                field.requestFocusInWindow();
                field.selectAll();
            });
        }

        private void closed() {
            commit();
            invoker.setOpen(false);
        }

        private JComponent header() {
            JPanel row = new JPanel();
            row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
            row.setOpaque(false);
            row.setBorder(new EmptyBorder(PAD - 2, PAD, 4, PAD));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));

            ThemedLabel title = new ThemedLabel("Max iterations", Token.TEXT, () -> Theme.sansSemiBold(14f));
            title.setAlignmentY(Component.CENTER_ALIGNMENT);
            row.add(title);
            row.add(Box.createHorizontalGlue());

            field.setHorizontalAlignment(JTextField.RIGHT);
            field.getAccessibleContext().setAccessibleName("Max iterations, exact value");
            field.setMaximumSize(field.getPreferredSize());
            field.setAlignmentY(Component.CENTER_ALIGNMENT);
            ((AbstractDocument) field.getDocument()).setDocumentFilter(new DigitsOnly());
            field.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    typed();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    typed();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    typed();
                }
            });
            field.addActionListener(_ -> {
                commit();
                field.selectAll();
            });
            field.registerKeyboardAction(_ -> {
                setField(value); // Escape: forget what was typed
                popup.setVisible(false);
            }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_FOCUSED);
            field.addFocusListener(new FocusAdapter() {
                @Override
                public void focusLost(FocusEvent e) {
                    commit();
                }
            });
            row.add(field);
            return row;
        }

        private JComponent description() {
            // Explicit line breaks, as in the top bar's tooltips: a fixed-width HTML body clipped the text.
            ThemedLabel text = new ThemedLabel("<html>How many steps a point gets before it counts<br>"
                    + "as inside the set. More iterations show finer<br>"
                    + "detail, but draw slower. From " + RenderSettings.MIN_ITERATIONS + " to "
                    + RenderSettings.MAX_ITERATIONS + ".</html>", Token.MUTED, () -> Theme.sans(12f));
            text.setBorder(new EmptyBorder(0, PAD, 6, PAD));
            text.setAlignmentX(Component.LEFT_ALIGNMENT);
            return text;
        }

        private JComponent sliderRow() {
            slider.setOpaque(false);
            slider.getAccessibleContext().setAccessibleName("Max iterations");
            slider.setMajorTickSpacing(SLIDER_STEPS / 4);
            slider.setPaintTicks(true);
            slider.setPaintLabels(true);
            Hashtable<Integer, JLabel> labels = new Hashtable<>();
            String[] names = { "10", "100", "1k", "10k", "100k" };
            for (int i = 0; i < names.length; i++) {
                ThemedLabel label = new ThemedLabel(names[i], Token.MUTED, () -> Theme.sans(11f));
                labels.put(i * SLIDER_STEPS / 4, label);
            }
            slider.setLabelTable(labels);
            slider.addChangeListener(_ -> {
                if (syncing) {
                    return;
                }
                int next = valueAt(slider.getValue());
                setField(next);
                if (next != value) {
                    value = next;
                    host.apply(next);
                }
            });

            JPanel row = new JPanel(new BorderLayout());
            row.setOpaque(false);
            row.setBorder(new EmptyBorder(0, PAD - 2, PAD - 4, PAD - 2));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.add(slider, BorderLayout.CENTER);
            return row;
        }

        /** The field changed: when it holds a valid number the slider follows (nothing is drawn until it is committed). */
        private void typed() {
            if (syncing) {
                return;
            }
            Integer number = parse(field.getText());
            boolean valid = number != null && number >= RenderSettings.MIN_ITERATIONS
                    && number <= RenderSettings.MAX_ITERATIONS;
            field.putClientProperty("JComponent.outline", valid ? null : "error");
            if (valid) {
                setSlider(number);
            }
        }

        /** Applies what is in the field: an empty field goes back to the current limit, other numbers are moved into range. */
        private void commit() {
            Integer number = parse(field.getText());
            int next = number == null ? value : RenderSettings.validMaxIterations(number);
            setField(next);
            if (valueAt(slider.getValue()) != next) {
                setSlider(next); // otherwise the slider already stands for it; moving it would make the thumb jump
            }
            if (next != value) {
                value = next;
                host.apply(next);
            }
        }

        private void setField(int iterations) {
            syncing = true;
            try {
                field.setText(Integer.toString(iterations));
                field.putClientProperty("JComponent.outline", null);
            } finally {
                syncing = false;
            }
        }

        private void setSlider(int iterations) {
            syncing = true;
            try {
                slider.setValue(positionOf(iterations));
            } finally {
                syncing = false;
            }
        }
    }

    /** Lets only digits into the field, up to the length of the highest limit; pasted text such as "1,000" is cleaned. */
    private static final class DigitsOnly extends DocumentFilter {
        @Override
        public void insertString(FilterBypass bypass, int offset, String text, AttributeSet attributes)
                throws BadLocationException {
            replace(bypass, offset, 0, text, attributes);
        }

        @Override
        public void replace(FilterBypass bypass, int offset, int length, String text, AttributeSet attributes)
                throws BadLocationException {
            String digits = text == null ? "" : text.replaceAll("\\D", "");
            if (text != null && !text.isEmpty() && digits.isEmpty()) {
                return; // nothing usable was typed
            }
            int room = MAX_DIGITS - (bypass.getDocument().getLength() - length);
            if (digits.length() > room) {
                digits = digits.substring(0, Math.max(0, room));
            }
            super.replace(bypass, offset, length, digits, attributes);
        }
    }
}

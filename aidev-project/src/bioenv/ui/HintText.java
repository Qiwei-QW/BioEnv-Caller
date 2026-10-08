package bioenv.ui;

import java.awt.Color;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;

import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;

/**
 * Shows hint text inside an empty text field without that text counting as
 * input.
 *
 * <p>This exists because of DESIGN.md P12: a parameter's help-stated default
 * must be visible as guidance but must never reach the command line unless the
 * user enters something. Showing the value in grey, rather than filling the
 * field with it, makes the distinction obvious to the user as well.
 *
 * <p>The hint is removed the moment the field gains real content, and restored
 * when the field is emptied again.
 */
final class HintText {

    /** Colour of the hint text; also marks the field as "showing a hint". */
    private static final Color HINT_COLOR = new Color(0x99, 0x99, 0x99);
    private static final String CLIENT_PROPERTY = "bioenv.hint";

    private HintText() {
    }

    /**
     * Installs a hint on a text field.
     *
     * @param field the field to decorate
     * @param hint  text to show while the field is empty
     */
    static void install(final JTextField field, final String hint) {
        if (field == null || hint == null || hint.isEmpty()) {
            return;
        }
        field.putClientProperty(CLIENT_PROPERTY, hint);
        field.setForeground(HINT_COLOR);
        field.setText(hint);
        // Treat a field showing only its hint as untouched.
        field.putClientProperty("bioenv.showingHint", Boolean.TRUE);

        field.addFocusListener(new FocusListener() {
            @Override
            public void focusGained(FocusEvent e) {
                if (isShowingHint(field)) {
                    field.setForeground(Color.BLACK);
                    field.putClientProperty("bioenv.showingHint", Boolean.FALSE);
                    field.setText("");
                }
            }

            @Override
            public void focusLost(FocusEvent e) {
                if (field.getText().isEmpty()) {
                    restore(field, hint);
                }
            }
        });

        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                markReal(field);
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                // Emptied by the user: the field is blank, not hint-showing.
                if (field.getText().isEmpty()) {
                    field.putClientProperty("bioenv.showingHint", Boolean.FALSE);
                }
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                markReal(field);
            }
        });
    }

    /** True when the field is currently displaying only its hint. */
    static boolean isShowingHint(JTextComponent field) {
        Object v = field.getClientProperty("bioenv.showingHint");
        return Boolean.TRUE.equals(v);
    }

    /**
     * The real text a user typed, or empty when the field shows only a hint.
     *
     * <p>Callers use this instead of {@code getText()} so hint text can never be
     * mistaken for input.
     */
    static String realText(JTextComponent field) {
        if (field == null || isShowingHint(field)) {
            return "";
        }
        return field.getText().trim();
    }

    /** Clears the hint so the field can accept real input. */
    static void clear(JTextField field) {
        if (field == null) {
            return;
        }
        field.putClientProperty("bioenv.showingHint", Boolean.FALSE);
        field.setForeground(Color.BLACK);
        if (field.getText().equals(field.getClientProperty(CLIENT_PROPERTY))) {
            field.setText("");
        }
    }

    private static void markReal(JTextField field) {
        Object hint = field.getClientProperty(CLIENT_PROPERTY);
        String text = field.getText();
        if (hint != null && text.equals(hint) && isShowingHint(field)) {
            return; // still the hint
        }
        if (isShowingHint(field) && !text.equals(hint)) {
            field.putClientProperty("bioenv.showingHint", Boolean.FALSE);
            field.setForeground(Color.BLACK);
        }
    }

    private static void restore(JTextField field, String hint) {
        field.putClientProperty("bioenv.showingHint", Boolean.TRUE);
        field.setForeground(HINT_COLOR);
        field.setText(hint);
    }
}

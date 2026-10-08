package bioenv.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes terminal control sequences from text a tool printed.
 *
 * <h2>Why this is needed</h2>
 *
 * Programs colour their output when they believe they are writing to a terminal,
 * and some of them embed clickable links. Those are escape sequences: control
 * bytes followed by parameters, meaningless anywhere but in a terminal that
 * understands them. The plugin shows the text in a Swing area, which does not, so
 * the sequences arrive as visible rubbish — {@code [39;49mCITATION:[0m} instead of
 * a heading — and they also end up in the help text the parser reads.
 *
 * <h2>Handling a lost escape byte</h2>
 *
 * The sequence's own control byte (ESC, 0x1B) does not always survive the trip
 * out of WSL and through {@code conda run}. What reaches the plugin is then the
 * parameters alone, looking like ordinary text. Both forms are therefore
 * recognised: with the control byte, and without it.
 *
 * <p>Dropping the orphaned form is a judgement, not a certainty: {@code [0m} could
 * in principle be something a program meant to print. In practice the shapes are
 * unmistakable — they are the exact grammars the terminal standards define — and
 * leaving them in means every reader of that output has to skip over them
 * (DESIGN.md P1: the rules describe terminal syntax, not any particular tool).
 */
public final class AnsiText {

    /**
     * An escape sequence with its control byte intact.
     *
     * <p>Covers the two families that appear in practice:
     * <ul>
     *   <li>CSI — {@code ESC [ params letter}, the colour and cursor codes.</li>
     *   <li>OSC — {@code ESC ] params terminator}, which is how a terminal
     *       hyperlink is written. Terminated by BEL or by {@code ESC \}.</li>
     * </ul>
     */
    private static final Pattern WITH_ESC = Pattern.compile(
            "\u001B\\[[0-9;?]*[ -/]*[@-~]"      // CSI: ESC [ ... final byte
            + "|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)?"  // OSC
            + "|\u001B[@-Z\\\\-_]"              // any other two-byte escape
    );

    /**
     * The same sequences with the control byte missing.
     *
     * <p>Stricter than the form above, because without the control byte there is
     * nothing but the shape to go on. Only parameter lists that plausibly came
     * from a real code are matched: digits and semicolons, or a hyperlink's own
     * {@code ]8;...} form.
     */
    private static final Pattern ORPHANED = Pattern.compile(
            "\\[[0-9]{1,3}(?:;[0-9]{1,3})*m"    // SGR: [0m, [1;36;49m, [38;5;136;49m
            + "|\\]8;[^\\\\\u0007\r\n]*(?:\\\\|\u0007)?"   // OSC 8 hyperlink, bare
    );

    /** Remaining stray control characters that carry no meaning in a text area. */
    private static final Pattern STRAY_CONTROL = Pattern.compile(
            "[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]");

    private AnsiText() {
    }

    /**
     * Returns the text with terminal control sequences removed.
     *
     * @param s the text, may be null
     * @return the text as it would appear in a plain terminal, never null
     */
    public static String strip(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        // A fast path for the common case: most output has no control bytes at all,
        // and running two regexes over every line of a large help text is wasteful.
        if (s.indexOf('\u001B') < 0 && s.indexOf('[') < 0 && s.indexOf(']') < 0) {
            return s;
        }
        String out = WITH_ESC.matcher(s).replaceAll("");
        out = ORPHANED.matcher(out).replaceAll("");
        out = STRAY_CONTROL.matcher(out).replaceAll("");
        return out;
    }

    /** True when the text contains a terminal control sequence. */
    public static boolean containsControl(String s) {
        if (s == null || s.isEmpty()) {
            return s != null && s.indexOf('\u001B') >= 0;
        }
        return s.indexOf('\u001B') >= 0
                || ORPHANED.matcher(s).find();
    }
}

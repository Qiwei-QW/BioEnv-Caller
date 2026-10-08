package bioenv.test;

import bioenv.core.AnsiText;
import bioenv.core.HelpParser;
import bioenv.core.Schema;

/**
 * Verifies that terminal control sequences are removed from tool output.
 *
 * <p>Programs colour their output when they think a terminal is watching, and some
 * embed hyperlinks. Those are escape sequences: control bytes plus parameters,
 * which a Swing text area cannot interpret. Left in, they appear as visible
 * rubbish in the log ({@code [39;49mCITATION:[0m}) and in the help viewer, and they
 * also reach the help parser.
 *
 * <p>The escape byte itself does not always survive the trip out of WSL, so the
 * parameters can arrive alone. Both forms are covered below.
 *
 * <p>Run: {@code java -cp &lt;classes&gt; bioenv.test.AnsiTextTest}
 */
public final class AnsiTextTest {

    private static int pass;
    private static int fail;

    /** The literal ESC byte, for the intact form. */
    private static final char ESC = '\u001B';

    public static void main(String[] args) {
        line("=== Terminal codes in tool output ===");
        line("");

        line("[the orphaned form seen in real output]");
        // Exactly the shape that reached the log: the escape byte is gone and the
        // parameters are left as ordinary-looking text. The codes go; the text
        // they were decorating stays.
        String[][] orphans = {
                {"[39;49mCITATION:[0m", "CITATION:"},
                {"[38;5;136;49mOrthoFinder[0m", "OrthoFinder"},
                {"[1;36;49m2025[0m", "2025"},
                {"[1;92;49m20:238[0m", "20:238"},
                {"[0m", ""},
                {"[1;39;49m", ""},
        };
        for (String[] pair : orphans) {
            check("cleaned: " + pair[0] + " -> '" + pair[1] + "'",
                    AnsiText.strip(pair[0]).equals(pair[1]));
        }
        check("a heading is left readable",
                AnsiText.strip("[39;49mCITATION:[0m").equals("CITATION:"));
        check("text between codes survives",
                AnsiText.strip("[39;49m When publishing work that uses [0m"
                        + "[38;5;136;49mOrthoFinder[0m[39;49m please cite:[0m")
                        .equals(" When publishing work that uses OrthoFinder please cite:"));
        line("");

        line("[the hyperlink sequence]");
        // OSC 8: a terminal hyperlink, terminated by a backslash or BEL.
        String link = "]8;id=12498278;https://doi.org/10.1101/2025.07.15.664860\\"
                + "[4;38;5;36;49mhttps://doi.org/10.1101/2025.07.15.664860[0m]8;;\\";
        String cleanedLink = AnsiText.strip(link);
        check("the link opener is gone", !cleanedLink.contains("id=12498278"));
        check("the URL is kept as visible text",
                cleanedLink.contains("https://doi.org/10.1101/2025.07.15.664860"));
        check("no stray backslash is left",
                !cleanedLink.contains("\\"));
        line("");

        line("[the intact form, with its escape byte]");
        check("a colour code is removed",
                AnsiText.strip(ESC + "[31mred" + ESC + "[0m").equals("red"));
        check("a cursor move is removed",
                AnsiText.strip(ESC + "[2Jtext").equals("text"));
        check("an OSC with BEL is removed",
                AnsiText.strip(ESC + "]0;title\u0007text").equals("text"));
        check("an OSC with a backslash terminator is removed",
                AnsiText.strip(ESC + "]8;;http://x" + ESC + "\\text").equals("text"));
        line("");

        line("[ordinary text is left alone]");
        String plain = "-t <int>   Number of parallel sequence search threads [Default = 16]";
        check("a normal help line is untouched", AnsiText.strip(plain).equals(plain));
        check("bracketed options survive", AnsiText.strip("[-d] [-o out]").equals("[-d] [-o out]"));
        check("a range in brackets survives",
                AnsiText.strip("count [0..100]").equals("count [0..100]"));
        check("an array-looking value survives",
                AnsiText.strip("int x[10];").equals("int x[10];"));
        check("a field name survives", AnsiText.strip("use [infile] here").equals("use [infile] here"));
        check("text with no brackets is returned as is",
                AnsiText.strip("plain text").equals("plain text"));
        line("");

        line("[mixed content keeps what matters]");
        String mixed = "OPTIONS:\n"
                + " -t <int>   threads [Default = 16]\n"
                + "[39;49mCITATION:[0m\n"
                + " Emms (2025), bioRxiv\n";
        String cleaned = AnsiText.strip(mixed);
        check("the options survive", cleaned.contains("-t <int>"));
        check("the default survives", cleaned.contains("[Default = 16]"));
        check("the heading is readable", cleaned.contains("CITATION:"));
        check("the code itself is gone", !cleaned.contains("[39;49m"));
        line("");

        line("[null and empty are safe]");
        check("null yields empty", AnsiText.strip(null).isEmpty());
        check("empty yields empty", AnsiText.strip("").isEmpty());
        line("");

        line("[a help text is parsed cleanly end to end]");
        // The parser reads ordinary help, so a coloured document must not produce
        // options carrying escape codes in their names or descriptions.
        String coloured = "SIMPLE USAGE:\n"
                + "\torthofinder [options] -f <dir>\n\n"
                + "OPTIONS:\n"
                + " -t <int>" + ESC + "[0m    Number of threads [Default = 16]\n"
                + " -M <txt>   Method" + ESC + "[0m for gene tree inference\n"
                + "[39;49m -v[0m    Show the current version number\n";
        Schema s = HelpParser.parse(coloured, "orthofinder");
        check("the options were read", s.options.size() >= 3);
        boolean anyCode = false;
        for (Schema.Option o : s.options) {
            if (o.flag.indexOf(ESC) >= 0 || o.description.indexOf(ESC) >= 0
                    || o.flag.contains("[0m") || o.description.contains("[39;49m")) {
                anyCode = true;
            }
        }
        check("no option carries an escape code", !anyCode);
        check("-t was read", s.option("-t") != null);
        check("-M was read", s.option("-M") != null);
        check("-v was read from the coloured line", s.option("-v") != null);
        line("");

        line("=== results ===");
        line("  passed: " + pass);
        line("  failed: " + fail);
        if (fail > 0) {
            line("");
            line("FAILURES PRESENT");
            System.exit(1);
        }
        line("");
        line("Tool output reaches the user and the parser as plain text.");
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            pass++;
        } else {
            fail++;
        }
        line("  " + (ok ? "PASS" : "FAIL") + "  " + label);
    }

    private static void line(String s) {
        System.out.println(s);
    }
}

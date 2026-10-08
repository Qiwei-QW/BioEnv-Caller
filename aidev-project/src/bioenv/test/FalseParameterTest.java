package bioenv.test;

import bioenv.core.HelpParser;
import bioenv.core.Schema;

/**
 * Verifies that prose is never read as parameters.
 *
 * <p>This is the general form of a defect first seen on one tool. Its cause is
 * general, and so is the exposure: measured across the sample, 71.7% of help
 * documents contain a hyphenated compound, and 1574 lines are prose containing a
 * hyphen. Any of those lines can be mistaken for a synopsis, after which the
 * hyphen inside a word is read as a flag.
 *
 * <pre>
 *   A user-friendly toolkit for whole-genome duplications
 *            └──────┬──┘         └────┬─────┘
 *             "-friendly toolkit"  "-genome duplications"
 * </pre>
 *
 * <p>The cases below are written as shapes rather than as any real tool's text, so
 * what is tested is the discriminator and not the sample (DESIGN.md P1).
 *
 * <p>Run: {@code java -cp &lt;classes&gt; bioenv.test.FalseParameterTest}
 */
public final class FalseParameterTest {

    private static int passed;
    private static int failed;

    /** Words that must never appear as parameters, whatever the document says. */
    private static final String[] NEVER_PARAMETERS = {
            "-friendly", "-genome", "-toolkit", "-duplications", "-art",
            "-quality", "-analysis", "-evolutionary", "-ancestral",
            "-Integrated", "-Whole", "-user", "-analyses",
    };

    public static void main(String[] args) {
        line("=== Prose must never become a parameter ===");
        line("");

        line("[a sentence about the program is not a synopsis]");
        // The shape that caused the original defect: the program's name, then
        // punctuation, then prose containing hyphenated compounds.
        Schema intro = HelpParser.parse(
                "usage: prog [options]\n"
                + "\n"
                + "PROG(Whole-Genome Integrated): A user-friendly toolkit for "
                + "evolutionary analyses of whole-genome duplications and "
                + "ancestral karyotypes.\n"
                + "\n"
                + "options:\n"
                + "  -h, --help            show this help message and exit\n"
                + "  -d DOTPLOT            show a dotplot\n",
                "prog");
        assertNoFalseParameters(intro, "an introductory sentence");
        check("the real options survive",
                intro.option("--help") != null && intro.option("-d") != null);
        line("");

        line("[other prose shapes that contain hyphens]");
        String[] prose = {
                "This is a state-of-the-art method for high-quality analysis.",
                "The read-only mode prevents well-known problems.",
                "Results are long-term and case-sensitive by default.",
                "A command-line tool for genome-wide studies.",
                "prog: an end-to-end solution for large-scale work.",
                "prog(x) implements a memory-efficient, cache-friendly design.",
        };
        for (String p : prose) {
            Schema s = HelpParser.parse(
                    "usage: prog [options]\n\n" + p + "\n\n"
                    + "options:\n  -a            first option\n"
                    + "  -b            second option\n",
                    "prog");
            assertNoFalseParameters(s, shortText(p));
        }
        line("");

        line("[a real synopsis is still recognised]");
        Schema syn = HelpParser.parse(
                "usage: prog [options]\n"
                + "\n"
                + "Usage: prog -query <file> -db <name> [-out <file>] [-evalue <n>]\n"
                + "\n"
                + "Options:\n"
                + "  -out FILE     output file\n",
                "prog");
        check("-query is captured", syn.option("-query") != null);
        check("-query is marked as synopsis-only",
                syn.option("-query") != null && syn.option("-query").fromUsageLine);
        check("-db is captured", syn.option("-db") != null);
        check("-out is captured from the option list",
                syn.option("-out") != null && !syn.option("-out").fromUsageLine);
        check("-evalue is captured", syn.option("-evalue") != null);
        line("");

        line("[an unlabelled synopsis beginning with the program name]");
        // No "usage:" label at all; the program's name followed by a separator is
        // what identifies it.
        Schema bare = HelpParser.parse(
                "prog -f <dir> [-o <file>]\n"
                + "\n"
                + "Options:\n"
                + "  -o FILE   output\n",
                "prog");
        check("-f is captured", bare.option("-f") != null);
        check("-f comes from the synopsis",
                bare.option("-f") != null && bare.option("-f").fromUsageLine);
        line("");

        line("[a hyphen inside a description is not a flag]");
        // The option is real; its description contains a compound. The compound
        // must not become a second parameter.
        Schema desc = HelpParser.parse(
                "Usage: prog [options]\n"
                + "\n"
                + "Options:\n"
                + "  -r            read-only mode\n"
                + "  -q            high-quality output\n",
                "prog");
        assertNoFalseParameters(desc, "hyphens in option descriptions");
        check("both real options survive",
                desc.option("-r") != null && desc.option("-q") != null);
        line("");

        line("[the program name followed by punctuation is not a synopsis]");
        check("'prog(x) ...' is rejected as a synopsis start",
                !startsAsProg("prog(x) does something -x", "prog"));
        check("'prog: ...' is rejected",
                !startsAsProg("prog: does something -x", "prog"));
        check("'prog -x' is accepted",
                startsAsProg("prog -x <file>", "prog"));
        check("'prog <file>' is accepted",
                startsAsProg("prog <file>", "prog"));
        line("");

        line("=== results ===");
        line("  passed: " + passed);
        line("  failed: " + failed);
        if (failed > 0) {
            line("");
            line("FAILURES PRESENT");
            System.exit(1);
        }
        line("");
        line("No hyphen inside a word becomes a parameter.");
    }

    /** True when the line is accepted as beginning with the program's name. */
    private static boolean startsAsProg(String line, String command) {
        return HelpParser.startsWithWord(line, command);
    }

    private static void assertNoFalseParameters(Schema s, String what) {
        int bad = 0;
        StringBuilder found = new StringBuilder();
        for (Schema.Option o : s.options) {
            for (String never : NEVER_PARAMETERS) {
                if (o.flag.equalsIgnoreCase(never)) {
                    bad++;
                    if (found.length() < 80) {
                        found.append(o.flag).append(' ');
                    }
                }
            }
        }
        check("no prose became a parameter (" + what + ")"
                        + (bad == 0 ? "" : "  found: " + found),
                bad == 0);
    }

    private static String shortText(String s) {
        return s.length() <= 46 ? s : s.substring(0, 46) + "...";
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
        }
        line("  " + (ok ? "PASS" : "FAIL") + "  " + label);
    }

    private static void line(String s) {
        System.out.println(s);
    }
}

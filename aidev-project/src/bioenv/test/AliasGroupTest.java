package bioenv.test;

import bioenv.core.HelpParser;
import bioenv.core.Schema;

/**
 * Verifies alias-group handling: one option written several ways on one line.
 *
 * <p>This shape is the most common in real help output — far more common than any
 * other — so getting it wrong means every parameter name is wrong. The rule is
 * that the <em>longest</em> spelling becomes the primary name: it is the most
 * descriptive, and the program accepts it just the same, so a command line built
 * from it behaves identically while being readable.
 *
 * <p>Run: {@code java -cp &lt;classes&gt; bioenv.test.AliasGroupTest}
 */
public final class AliasGroupTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        line("=== Alias group normalisation ===");
        line("");

        line("[the long form becomes the primary name]");
        // Constructed samples. No real tool is named, and none needs to be: the
        // shape is what is being tested (DESIGN.md P1).
        expectPrimary("-h, --help            show this help message and exit",
                "--help", new String[]{"-h"});
        expectPrimary("--tree, -t            path to a tree file",
                "--tree", new String[]{"-t"});
        expectPrimary("-t, --tree            path to a tree file",
                "--tree", new String[]{"-t"});
        expectPrimary("  -i, --infile FILE   input file",
                "--infile", new String[]{"-i"});
        line("");

        line("[a flag with no alias is left alone]");
        expectPrimary("-v                    verbose output", "-v", new String[]{});
        expectPrimary("--only-long           a long flag alone",
                "--only-long", new String[]{});
        expectPrimary("  --threads N         number of threads",
                "--threads", new String[]{});
        line("");

        line("[pipe-separated groups are handled too]");
        expectPrimary("-a|--alpha            the alpha option",
                "--alpha", new String[]{"-a"});
        line("");

        line("[three spellings keep both as aliases]");
        expectPrimary("-q, --quiet, --silent   suppress output",
                "--silent", new String[]{"-q", "--quiet"});
        line("");

        line("[equal-length spellings keep the first]");
        // Two forms of equal length: no reason to prefer either, so the first
        // written wins and the result is still deterministic.
        Schema.Option eq = parseOne("-abcd, --xyz         something");
        check("equal length keeps the first (" + eq.flag + ")",
                eq != null && "-abcd".equals(eq.flag)
                        && eq.aliases.contains("--xyz"));
        line("");

        line("[the value position still parses after the group]");
        Schema.Option withValue = parseOne("-i, --infile FILE     input file name");
        check("primary is the long form",
                withValue != null && "--infile".equals(withValue.flag));
        check("the value position is preserved",
                withValue != null && "FILE".equals(withValue.arg));
        check("the description is preserved",
                withValue != null && withValue.description.contains("input file"));
        line("");

        line("[an equals group still takes a value]");
        Schema.Option eqForm = parseOne("-O, --outfile=         output file name");
        check("the long form is primary",
                eqForm != null && "--outfile".equals(eqForm.flag));
        check("it is not a switch", eqForm != null && !eqForm.isSwitch);
        check("takesValue is recorded", eqForm != null && eqForm.takesValue);
        line("");

        line("[duplicate spellings are not repeated]");
        Schema.Option dup = parseOne("-x, -x, --xxx          repeated spelling");
        check("no duplicate alias", dup != null && dup.aliases.size() == 1);
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
        line("Alias groups resolve to the longest spelling, deterministically.");
    }

    private static Schema.Option parseOne(String optionLine) {
        String doc = "Usage: demo [options]\n\nOptions:\n" + optionLine + "\n";
        Schema s = HelpParser.parse(doc, "demo");
        return s.options.isEmpty() ? null : s.options.get(0);
    }

    private static void expectPrimary(String optionLine, String wantFlag,
                                      String[] wantAliases) {
        Schema.Option o = parseOne(optionLine);
        if (o == null) {
            check("parsed: " + trim(optionLine), false);
            return;
        }
        boolean ok = wantFlag.equals(o.flag);
        boolean aliasesOk = o.aliases.size() == wantAliases.length;
        if (aliasesOk) {
            for (String a : wantAliases) {
                if (!o.aliases.contains(a)) {
                    aliasesOk = false;
                }
            }
        }
        check(trim(optionLine) + "  ->  " + o.flag
                        + (o.aliases.isEmpty() ? "" : " (aliases " + o.aliases + ")"),
                ok && aliasesOk);
    }

    private static String trim(String s) {
        String t = s.trim();
        return t.length() <= 40 ? t : t.substring(0, 40) + "...";
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

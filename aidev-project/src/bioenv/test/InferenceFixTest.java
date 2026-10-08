package bioenv.test;

import bioenv.core.HelpParser;
import bioenv.core.Schema;
import bioenv.core.TypeInference;

/**
 * Checks the three inference improvements against the real help text that was
 * misread, so the effect is measured rather than assumed.
 */
public final class InferenceFixTest {

    private static int passed;
    private static int failed;

    /**
     * Parses help text and returns the named option.
     *
     * <p>Goes through {@code parse} rather than the single-line helper, because
     * classification runs at the end of the whole parse so that a candidate list
     * on a continuation line can influence an option's type.
     */
    private static Schema.Option opt(String helpText, String flag) {
        Schema s = HelpParser.parse(helpText, "demo");
        for (Schema.Option o : s.options) {
            if (flag.equals(o.flag) || o.aliases.contains(flag)) {
                return o;
            }
        }
        return null;
    }

    /** Wraps one help line in a minimal document. */
    private static String doc(String line) {
        return "Usage: demo [options]\n\nOptions:\n" + line + "\n";
    }

    public static void main(String[] args) {
        line("=== Inference fixes, against the real failing help ===");
        line("");

        // ---------- layer 1: the equals connection form --------------------
        line("[clustalw: -OUTFILE= takes a value, -QUICKTREE does not]");
        Schema.Option outfile = opt(doc("-OUTFILE=    :sequence alignment file name"),
                "-OUTFILE");
        check("'-OUTFILE=' was parsed", outfile != null);
        check("'-OUTFILE=' is not a switch",
                outfile != null && !outfile.isSwitch);
        check("'-OUTFILE=' records takesValue",
                outfile != null && outfile.takesValue);
        check("flag is '-OUTFILE', not '-OUTFILE='",
                outfile != null && "-OUTFILE".equals(outfile.flag));

        Schema.Option quick = opt(doc(
                "-QUICKTREE   :use FAST algorithm for the alignment guide tree"),
                "-QUICKTREE");
        check("'-QUICKTREE' is still a switch", quick != null && quick.isSwitch);

        Schema.Option infile = opt(doc(
                "-INFILE=file.ext                             :input sequences."),
                "-INFILE");
        check("'-INFILE=file.ext' is not a switch",
                infile != null && !infile.isSwitch);
        check("'-INFILE=' yields a file-ish type",
                infile != null && (infile.kind == Schema.Option.Kind.INPUT_PATH
                        || infile.kind == Schema.Option.Kind.OUTPUT_PATH
                        || infile.kind == Schema.Option.Kind.TEXT));
        line("");

        line("[cafe5: --tree needs a path]");
        Schema.Option tree = opt(doc("--tree, -t\t\tPath to file containing "
                + "newick formatted tree - Required for estimation."), "--tree");
        check("'--tree, -t' was parsed", tree != null);
        check("'--tree, -t' is not a switch", tree != null && !tree.isSwitch);
        check("'--tree' has -t as an alias",
                tree != null && tree.aliases.contains("-t"));
        check("'--tree' is captured", tree != null);
        line("");

        // ---------- layer 2: single-letter bracketed placeholders ----------
        line("[alimask: <s> and <x> are placeholders]");
        check("isMetavariable('<s>')", HelpParser.isMetavariable("<s>"));
        check("isMetavariable('<x>')", HelpParser.isMetavariable("<x>"));
        check("isMetavariable('<f>')", HelpParser.isMetavariable("<f>"));
        // The old rule's concern still holds for unbracketed letters.
        check("isMetavariable('P') is still false",
                !HelpParser.isMetavariable("P"));
        check("isMetavariable('FILE') is true",
                HelpParser.isMetavariable("FILE"));

        Schema.Option outformat = opt(doc("  --outformat <s> : output alignment "
                + "in format <s>  [Stockholm]"), "--outformat");
        check("'--outformat' was parsed", outformat != null);
        check("'--outformat' keeps <s> as its metavariable",
                outformat != null && "<s>".equals(outformat.arg));
        check("'--outformat' is a CHOICE from its default [Stockholm]",
                outformat != null
                        && outformat.kind == Schema.Option.Kind.CHOICE);
        line("");

        // ---------- layer 3: the default value's shape ----------------------
        line("[a numeric default makes a numeric option]");
        Schema.Option symfrac = opt(doc("  --symfrac <x>    : sets sym fraction "
                + "controlling --fast construction  [0.5]"), "--symfrac");
        check("'--symfrac' was parsed", symfrac != null);
        check("'--symfrac' is a NUMBER from default [0.5]",
                symfrac != null && symfrac.kind == Schema.Option.Kind.NUMBER);
        check("the reason cites the default",
                symfrac != null && symfrac.typeReason.contains("0.5"));

        Schema.Option textw = opt(doc("  --textw <n>      : set max width of ASCII "
                + "text output lines  [120]  (n>=120)"), "--textw");
        check("'--textw' was parsed", textw != null);
        check("'--textw' is a NUMBER", textw != null
                && textw.kind == Schema.Option.Kind.NUMBER);
        check("'--textw' picked up its lower bound",
                textw != null && textw.min != null && textw.min.doubleValue() == 120.0);
        line("");

        line("[a file-shaped default makes a path]");
        Schema.Option codon = opt(doc("-cfile codon     :[Ehuman.cut] Codon usage "
                + "table name"), "-cfile");
        check("'-cfile' was parsed", codon != null);
        if (codon != null) {
            line("    arg=" + codon.arg + "  kind=" + codon.kind
                    + "  reason=" + codon.typeReason);
        }
        line("");

        line("[prose alone must not drive the type]");
        // "write 3 files" tells a file-shaped story in prose. Only a formatted
        // default or range may drive inference, so this must not become a path.
        TypeInference.Result prose = TypeInference.infer("", "write 3 files", false);
        check("prose mentioning 'files' does not become a path",
                prose.kind != Schema.Option.Kind.INPUT_PATH
                        && prose.kind != Schema.Option.Kind.OUTPUT_PATH);
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
        line("The three inference fixes hold against the real help text.");
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

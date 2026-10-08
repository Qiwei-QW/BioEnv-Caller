package bioenv.test;

import bioenv.core.HelpParser;
import bioenv.core.Schema;

/**
 * Verifies that every conclusion the parser reaches can be explained, and that a
 * document it cannot read is reported as such rather than presented as an empty
 * form.
 *
 * <p>Run: {@code java -cp &lt;classes&gt; bioenv.test.EvidenceTest}
 */
public final class EvidenceTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        line("=== Evidence and degradation ===");
        line("");

        line("[each option records the signal that decided it]");
        Schema s = HelpParser.parse(
                "Usage: demo -i FILE [--mode M] [-n N] [--loud]\n"
                + "\n"
                + "Options:\n"
                + "  -i FILE      input file\n"
                + "  --mode M     one of: fast, slow, auto\n"
                + "  -n N         number of items  [4]\n"
                + "  --depth      recursion depth  [3]\n"
                + "  --loud       be noisy\n",
                "demo");

        hasEvidence(s, "-i", Schema.Option.Evidence.PLACEHOLDER);
        hasEvidence(s, "--mode", Schema.Option.Evidence.CHOICES);
        // A placeholder outranks a default: the placeholder is the author saying
        // what kind of value belongs there, while a default is merely a value
        // they chose.
        hasEvidence(s, "-n", Schema.Option.Evidence.PLACEHOLDER);
        // With no placeholder, the default's shape is the strongest thing left.
        hasEvidence(s, "--depth", Schema.Option.Evidence.DEFAULT_SHAPE);
        // A switch records none: "takes no value" is the absence of a value
        // position, not a statement about a type.
        hasEvidence(s, "--loud", Schema.Option.Evidence.NONE);
        line("");

        line("[a declared type is recorded as declared]");
        Schema declared = HelpParser.parse(
                "Qualifiers:\n"
                + "  [-infile]            infile     input file\n"
                + "  [-width]             integer    display width\n",
                "demo");
        hasEvidence(declared, "-infile", Schema.Option.Evidence.TYPE_COLUMN);
        hasEvidence(declared, "-width", Schema.Option.Evidence.TYPE_COLUMN);
        line("");

        line("[the document's structure is recorded alongside it]");
        check("a document with a synopsis records one",
                s.format != null && s.format.hasUsageLine);
        check("a document with a type column records one",
                declared.format != null && declared.format.hasTypeColumn);
        check("the structure summary is readable",
                declared.format.describe().startsWith("TYPE_COLUMN"));
        check("structure names the option count",
                s.format != null && s.format.optionLineCount >= 4);
        line("");

        line("[a document with no usable structure is reported as such]");
        // A program that takes a control file prints nothing about options. That
        // is a fact about the program, and saying so is more useful than showing
        // an empty form with no explanation.
        Schema none = HelpParser.parse("Error: File control.ctl not opened\n", "demo");
        check("no options were invented", none.options.isEmpty());
        check("the structure says it is unusable",
                none.format != null && none.format.isUnusable());
        line("");

        line("[a structured document that yields nothing is distinguishable]");
        // The help is organised but produced no options: a reading limitation,
        // not a property of the tool. The two cases must not be conflated.
        Schema odd = HelpParser.parse(
                "Usage: demo [options]\n"
                + "\n"
                + "Options:\n"
                + "  (none documented)\n",
                "demo");
        check("no options were invented here either", odd.options.isEmpty());
        check("but the structure was found",
                odd.format != null && odd.format.hasUsageLine);
        line("");

        line("[the plugin does not decide what is mandatory]");
        // Whether an option must be supplied is the program's determination. A
        // plugin that guesses gets it wrong in both directions at once: it
        // invents requirements the program lacks, and it blocks calls the program
        // would accept — a help invocation being the clearest case.
        Schema req = HelpParser.parse(
                "Usage: demo -i FILE [-n N]\n"
                + "\n"
                + "Options:\n"
                + "  -i FILE    input\n"
                + "  -n N       count\n",
                "demo");
        check("both parameters were read",
                req.option("-i") != null && req.option("-n") != null);
        check("the option list entry is kept for -i",
                req.option("-i") != null && !req.option("-i").fromUsageLine);
        check("its description survives",
                req.option("-i") != null
                        && req.option("-i").description.contains("input"));
        line("");

        line("[the summary of a real-shaped document reads sensibly]");
        line("    " + s.format.describe());
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
        line("Every type is explained by a named signal, and unreadable help says so.");
    }

    private static void hasEvidence(Schema s, String flag,
                                    Schema.Option.Evidence want) {
        Schema.Option o = s.option(flag);
        boolean ok = o != null && o.evidence == want;
        check(flag + " <- " + want + (ok ? "" : " (got "
                + (o == null ? "absent" : o.evidence + " / " + o.typeReason) + ")"),
                ok);
        if (o != null) {
            check(flag + " carries a reason", !o.typeReason.isEmpty()
                    || o.evidence == Schema.Option.Evidence.NONE);
        }
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

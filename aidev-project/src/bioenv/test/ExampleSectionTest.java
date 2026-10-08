package bioenv.test;

import bioenv.core.HelpParser;
import bioenv.core.Schema;

/**
 * Verifies that worked examples are never read as declarations, and that genuine
 * declarations are never lost.
 *
 * <p>Two failures are being prevented at once, and they pull in opposite
 * directions:
 *
 * <ul>
 *   <li>An example read as a declaration invents parameters. They arrive with no
 *       description and no type, because no part of the help defines them.</li>
 *   <li>A declaration discarded along with the examples loses parameters. Some
 *       tools name parameters <em>only</em> in a usage synopsis.</li>
 * </ul>
 *
 * <p>So the tests below check both: that demonstrations contribute nothing, and
 * that declarations — including synopsis-only ones — all survive.
 *
 * <p>Samples are written to express document shapes rather than to reproduce any
 * real tool's text, so what is tested is the discriminator (DESIGN.md P1, P32).
 *
 * <p>Run: {@code java -cp &lt;classes&gt; bioenv.test.ExampleSectionTest}
 */
public final class ExampleSectionTest {

    private static int passed;
    private static int failed;

    /** Flags that must never appear: they exist only in sample commands. */
    private static final String[] DEMO_ONLY = {
            "-out", "-nt", "-gtr", "-gappyout", "--gene", "--lineage",
            "-fields", "-resoverlap", "-selectcols", "-automated1",
    };

    public static void main(String[] args) {
        line("=== Examples are not declarations ===");
        line("");

        line("[a titled examples section contributes nothing]");
        Schema titled = HelpParser.parse(
                "Usage: prog [options]\n"
                + "\n"
                + "Some Examples:\n"
                + "\n"
                + "1) Trim an alignment, keeping 60% of the original positions.\n"
                + "\n"
                + "   prog -in alignment.fa -out trimmed.fa -gappyout\n"
                + "\n"
                + "2) The same, averaged over a window.\n"
                + "\n"
                + "   prog -in alignment.fa -out trimmed.fa -resoverlap 0.8\n"
                + "\n"
                + "Options:\n"
                + "  -in FILE      input alignment\n"
                + "  -out FILE     output alignment\n"
                + "  -gappyout     automatic gap-based trimming\n",
                "prog");
        check("the declared options survive",
                titled.option("-in") != null && titled.option("-out") != null
                        && titled.option("-gappyout") != null);
        check("every kept option comes from the option list",
                fromOptionList(titled, "-in") && fromOptionList(titled, "-out")
                        && fromOptionList(titled, "-gappyout"));
        check("nothing extra was invented from the examples",
                !hasAny(titled, DEMO_ONLY, "-in", "-out", "-gappyout"));
        line("");

        line("[an untitled demonstration is recognised by its concrete values]");
        // No heading at all. The line shows real values where a declaration would
        // show placeholders, which is what identifies it.
        Schema untitled = HelpParser.parse(
                "Unknown or incorrect use of option -h\n"
                + "  prog -out tree.bin alignment.fa\n"
                + "  prog -nt alignment.fa > tree.nwk\n"
                + "\n"
                + "Common options (must precede the alignment):\n"
                + "  -out FILE     write the tree to a file\n"
                + "  -nt SEQ       nucleotide alignment\n",
                "prog");
        check("the declared options survive",
                untitled.option("-out") != null && untitled.option("-nt") != null);
        check("both come from the option list",
                fromOptionList(untitled, "-out") && fromOptionList(untitled, "-nt"));
        check("the redirection was not read as a placeholder",
                !untitled.options.isEmpty());
        line("");

        line("[every spelling of the heading is recognised]");
        for (String heading : new String[]{
                "Examples:", "Some Examples:", "Example:", "EXAMPLES",
                "Usage examples:", "Examples", "More examples:"}) {
            Schema s = HelpParser.parse(
                    "Usage: prog [options]\n"
                    + "\n"
                    + heading + "\n"
                    + "\n"
                    + "  prog -in data.txt -out result.txt\n"
                    + "\n"
                    + "Options:\n"
                    + "  -in FILE     input\n"
                    + "  -out FILE    output\n",
                    "prog");
            boolean clean = s.option("-in") != null && s.option("-out") != null
                    && fromOptionList(s, "-in") && fromOptionList(s, "-out");
            check("heading '" + heading + "' excludes the sample", clean);
        }
        line("");

        line("[a declaration is not mistaken for an example]");
        // A synopsis writes placeholders, so it is a declaration even though it
        // names the program and carries flags.
        Schema decl = HelpParser.parse(
                "Basic usage\n"
                + "\tprog -in <inputfile> -out <outputfile> -(other options).\n"
                + "\n"
                + "  -compareset <inputfile>   compare a list of alignments\n",
                "prog");
        check("the angle placeholders mark it as a declaration",
                decl.option("-in") != null && decl.option("-out") != null);
        check("an all-capitals placeholder does the same",
                keep(
                        "Usage: prog\n"
                        + "\n"
                        + "Usage: prog -out TREE -in ALN\n"));
        line("");

        line("[a synopsis-only parameter is kept]");
        // Some tools declare parameters only here. Dropping the synopsis with the
        // examples would lose them.
        Schema syn = HelpParser.parse(
                "corelist --utils [-d] <UtilityName> [<UtilityName>] ...\n"
                + "corelist --feature <FeatureName> [<FeatureName>] ...\n",
                "corelist");
        check("--utils is kept", syn.option("--utils") != null);
        check("--feature is kept", syn.option("--feature") != null);
        check("they are marked as synopsis-only",
                syn.option("--utils") != null && syn.option("--utils").fromUsageLine);
        line("");

        line("[a real example is still not read, even with a placeholder in it]");
        // A demonstration may contain a placeholder for the input and concrete
        // values elsewhere; the concrete values identify it.
        Schema mixed = HelpParser.parse(
                "Usage: prog [options]\n"
                + "\n"
                + "Examples:\n"
                + "\n"
                + "  prog -in <inputfile> -out result.fa -selectcols 0,2,3\n"
                + "\n"
                + "Options:\n"
                + "  -in FILE     input\n"
                + "  -out FILE    output\n",
                "prog");
        check("the option list is the source",
                fromOptionList(mixed, "-in") && fromOptionList(mixed, "-out"));
        check("-selectcols was not invented",
                mixed.option("-selectcols") == null);
        line("");

        line("[the option list still ends a headingless examples run]");
        // Examples followed directly by the option list, with no heading between.
        Schema noHeading = HelpParser.parse(
                "Usage: prog [options]\n"
                + "\n"
                + "  prog -alpha data.txt\n"
                + "  prog -beta data.txt\n"
                + "  -alpha        first real option\n"
                + "  -beta         second real option\n",
                "prog");
        check("the options after the samples are read",
                noHeading.option("-alpha") != null
                        && noHeading.option("-beta") != null);
        check("and taken from the option list",
                fromOptionList(noHeading, "-alpha")
                        && fromOptionList(noHeading, "-beta"));
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
        line("Demonstrations contribute nothing; declarations are all kept.");
    }

    private static boolean keep(String doc) {
        Schema s = HelpParser.parse(doc, "prog");
        return s.option("-out") != null && s.option("-in") != null;
    }

    private static boolean fromOptionList(Schema s, String flag) {
        Schema.Option o = s.option(flag);
        return o != null && !o.fromUsageLine;
    }

    /** True when any listed flag is present other than the allowed ones. */
    private static boolean hasAny(Schema s, String[] flags, String... allowed) {
        for (String f : flags) {
            boolean ok = false;
            for (String a : allowed) {
                if (a.equals(f)) {
                    ok = true;
                }
            }
            if (!ok && s.option(f) != null) {
                return true;
            }
        }
        return false;
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

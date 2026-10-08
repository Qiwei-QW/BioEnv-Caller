package bioenv.test;

import bioenv.core.HelpFormat;

/**
 * Verifies the structural recogniser against constructed documents.
 *
 * <p>Samples are written here rather than copied from real tools, so that what is
 * being tested is the shape of the text and not a particular program
 * (DESIGN.md P1). Each case states one feature and checks it is found — and,
 * where it matters, that a similar-looking but different shape is not.
 *
 * <p>Run: {@code java -cp &lt;classes&gt; bioenv.test.HelpFormatTest}
 */
public final class HelpFormatTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        line("=== Help structure recognition ===");
        line("");

        line("[a generated-parser document is recognised]");
        HelpFormat.Report ap = HelpFormat.analyse(
                "usage: demo [-h] --infile FILE [--n N]\n"
                + "\n"
                + "positional arguments:\n"
                + "  input                 input file\n"
                + "\n"
                + "optional arguments:\n"
                + "  -h, --help            show this help message and exit\n"
                + "  --infile FILE         input file path\n"
                + "  --n N                 number of things\n");
        check("generated sections found", ap.hasArgparseSections);
        check("sections are named", ap.sectionNames.contains("positional arguments")
                && ap.sectionNames.contains("optional arguments"));
        check("alias groups found", ap.hasAliasGroups);
        check("option lines counted", ap.optionLineCount >= 3);
        check("dominant is ARGPARSE", ap.dominant == HelpFormat.Family.ARGPARSE);
        line("");

        line("[a type-column document is recognised]");
        // The shape that declares types in a dedicated column.
        HelpFormat.Report tc = HelpFormat.analyse(
                "Extract data from a database\n"
                + "Version: 6.6.0\n"
                + "\n"
                + "   Standard (Mandatory) qualifiers:\n"
                + "  [-infile]            infile     database file\n"
                + "  [-outfile]           outfile    output file name\n"
                + "\n"
                + "   Advanced (Unprompted) qualifiers:\n"
                + "   -width              integer    width of the display\n"
                + "   -verbose            boolean    report extra detail\n");
        check("type column found", tc.hasTypeColumn);
        check("type-column lines counted", tc.typeColumnLineCount >= 3);
        check("dominant is TYPE_COLUMN", tc.dominant == HelpFormat.Family.TYPE_COLUMN);
        check("named sections also found", tc.hasNamedSections);
        line("");

        line("[a document with only two options still counts]");
        // The simplest tools document two options in total. A bare minimum-count
        // threshold rejected these, which was wrong: both lines carry a type.
        HelpFormat.Report small = HelpFormat.analyse(
                "Do the thing\n"
                + "   Qualifiers:\n"
                + "  [-infile]            infile     input file\n"
                + "   -help               boolean    report options\n");
        check("a two-option document has a type column", small.hasTypeColumn);
        line("");

        line("[a description starting with a type-shaped word is not a column]");
        // One space, not two: this is a description, not an aligned field.
        HelpFormat.Report notCol = HelpFormat.analyse(
                "Usage: demo [options]\n"
                + "\n"
                + "Options:\n"
                + "  -x boolean value to set\n"
                + "  -y integer count to use\n");
        check("no type column claimed", !notCol.hasTypeColumn);
        line("");

        line("[the usage synopsis is found whatever its case]");
        // The capitalised spelling is at least as common; matching only the
        // lower-case form left the synopsis unrecognised in half the documents.
        for (String spelling : new String[]{"Usage:", "usage:", "USAGE:",
                "Synopsis:", "usage =", "Usage :"}) {
            HelpFormat.Report u = HelpFormat.analyse(
                    spelling + " demo [options] -i FILE\n\nOptions:\n  -i FILE  input\n");
            check("found: " + spelling, u.hasUsageLine);
        }
        line("");

        line("[a synopsis naming parameters is distinguished]");
        HelpFormat.Report withParams = HelpFormat.analyse(
                "Usage: demo -i FILE [-n N]\n\nOptions:\n  -i FILE  input\n");
        check("synopsis names parameters", withParams.usageNamesParams);
        HelpFormat.Report bare = HelpFormat.analyse(
                "Usage: demo infile outfile\n\nOptions:\n  -v  verbose\n");
        check("a positional-only synopsis does not",
                !bare.usageNamesParams);
        check("but the synopsis is still recorded", bare.hasUsageLine);
        line("");

        line("[named sections without generated names]");
        HelpFormat.Report ns = HelpFormat.analyse(
                "Program description\n"
                + "\n"
                + "Input options:\n"
                + "  -i FILE   input file\n"
                + "  -f FMT    input format\n"
                + "\n"
                + "Output options:\n"
                + "  -o FILE   output file\n");
        check("named sections found", ns.hasNamedSections);
        check("section names captured",
                ns.sectionNames.contains("Input options")
                        && ns.sectionNames.contains("Output options"));
        check("dominant is NAMED_SECTIONS",
                ns.dominant == HelpFormat.Family.NAMED_SECTIONS);
        line("");

        line("[a flat list with nothing else]");
        HelpFormat.Report flat = HelpFormat.analyse(
                "-a   first thing\n"
                + "-b   second thing\n"
                + "-c   third thing\n");
        check("option list found", flat.hasOptionList);
        check("no elements claimed that are absent",
                !flat.hasUsageLine && !flat.hasArgparseSections
                        && !flat.hasNamedSections);
        check("dominant is FLAT_LIST", flat.dominant == HelpFormat.Family.FLAT_LIST);
        line("");

        line("[a document with no option list is unusable]");
        // Some programs take a control file instead of options and print nothing
        // useful. Reporting that honestly is the point; guessing is not.
        HelpFormat.Report none = HelpFormat.analyse(
                "Error: File control.ctl not opened\n");
        check("reported unusable", none.isUnusable());
        check("dominant is EMPTY", none.dominant == HelpFormat.Family.EMPTY);
        HelpFormat.Report blank = HelpFormat.analyse("   \n  \n");
        check("blank input is unusable", blank.isUnusable());
        check("null input is unusable", HelpFormat.analyse(null).isUnusable());
        line("");

        line("[components combine rather than compete]");
        // A single-family classifier would have to discard all but one of these.
        HelpFormat.Report combo = HelpFormat.analyse(
                "Usage: demo -i FILE\n"
                + "\n"
                + "Input options:\n"
                + "  -i, --infile FILE   input file\n"
                + "  -f, --format FMT    input format\n");
        check("synopsis found", combo.hasUsageLine);
        check("sections found", combo.hasNamedSections);
        check("alias groups found", combo.hasAliasGroups);
        check("all three reported together",
                combo.hasUsageLine && combo.hasNamedSections && combo.hasAliasGroups);
        line("");

        line("[the summary is readable]");
        line("    " + combo.describe());
        check("summary names the dominant form",
                combo.describe().startsWith(combo.dominant.name()));
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
        line("Structural features are detected independently and reported together.");
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

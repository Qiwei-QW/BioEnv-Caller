package bioenv.test;

import bioenv.core.HelpParser;
import bioenv.core.Schema;

/**
 * Verifies that a value position is read whole, whatever the layout does to it.
 *
 * <p>Help output aligns descriptions in a column, and the column is only a layout
 * hint. Two layouts break a naive cut at that column, and both occur widely
 * because they are what an author gets when a value name is long:
 *
 * <pre>
 *   -icl IMPROVEDCOLLINEARITY          the value overflows past the column,
 *                        ^column        so cutting there splits it in half
 *
 *   -km KARYOTYPE_MAPPING              the description went to the next line,
 *                        Mapping...     so the line ends before the column
 * </pre>
 *
 * <p>In the first, the value was truncated to {@code IMPROVEDCOLLINEAR}; in the
 * second it was read as part of the description. Both left the option looking as
 * though it took no value at all.
 *
 * <p>Run: {@code java -cp &lt;classes&gt; bioenv.test.ValuePositionTest}
 */
public final class ValuePositionTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        line("=== A value position is read whole ===");
        line("");

        line("[a value longer than the description column]");
        // The column is at 24; this value runs past it.
        Schema longVal = HelpParser.parse(
                "Usage: prog [options]\n"
                + "\n"
                + "Options:\n"
                + "  -h                    show this help message and exit\n"
                + "  -icl IMPROVEDCOLLINEARITY\n"
                + "                        improved version of the scan\n",
                "prog");
        Schema.Option icl = longVal.option("-icl");
        check("-icl was parsed", icl != null);
        check("its value is not truncated",
                icl != null && "IMPROVEDCOLLINEARITY".equals(icl.arg));
        check("it is not read as a switch", icl != null && !icl.isSwitch);
        check("its description came from the next line",
                icl != null && icl.description.contains("improved version"));
        line("");

        line("[a value whose description is on the following line]");
        Schema nextLine = HelpParser.parse(
                "Usage: prog [options]\n"
                + "\n"
                + "Options:\n"
                + "  -h                    show this help message and exit\n"
                + "  -km KARYOTYPE_MAPPING\n"
                + "                        mapping from a known result\n",
                "prog");
        Schema.Option km = nextLine.option("-km");
        check("-km was parsed", km != null);
        check("its value is captured",
                km != null && "KARYOTYPE_MAPPING".equals(km.arg));
        check("it is not read as a switch", km != null && !km.isSwitch);
        check("its description came from the next line",
                km != null && km.description.contains("mapping from"));
        line("");

        line("[several such values in one document]");
        Schema many = HelpParser.parse(
                "Usage: prog [options]\n"
                + "\n"
                + "Options:\n"
                + "  -a                    first option\n"
                + "  -pc POLYPLOIDY_CLASSIFICATION\n"
                + "                        distinguish subgenomes\n"
                + "  -fpd FUSION_POSITIONS_DATABASE\n"
                + "                        extract fusion positions\n"
                + "  -akr ANCESTRAL_KARYOTYPE_REPERTOIRE\n"
                + "                        incorporate genes\n",
                "prog");
        expectArg(many, "-pc", "POLYPLOIDY_CLASSIFICATION");
        expectArg(many, "-fpd", "FUSION_POSITIONS_DATABASE");
        expectArg(many, "-akr", "ANCESTRAL_KARYOTYPE_REPERTOIRE");
        line("");

        line("[an ordinary aligned line is unaffected]");
        Schema normal = HelpParser.parse(
                "Usage: prog [options]\n"
                + "\n"
                + "Options:\n"
                + "  -i FILE               input file\n"
                + "  -n NUM                number of items\n",
                "prog");
        expectArg(normal, "-i", "FILE");
        expectArg(normal, "-n", "NUM");
        check("-i describes an input",
                normal.option("-i") != null
                        && normal.option("-i").kind == Schema.Option.Kind.INPUT_PATH);
        line("");

        line("[a value followed by a description on the same line]");
        Schema same = HelpParser.parse(
                "Usage: prog [options]\n"
                + "\n"
                + "Options:\n"
                + "  -h                    show this help message and exit\n"
                + "  -d DOTPLOT            show a dotplot\n",
                "prog");
        check("the value is separated from the description",
                same.option("-d") != null
                        && "DOTPLOT".equals(same.option("-d").arg)
                        && same.option("-d").description.contains("show a dotplot"));
        line("");

        line("[a short option with no value is still a switch]");
        Schema sw = HelpParser.parse(
                "Usage: prog [options]\n"
                + "\n"
                + "Options:\n"
                + "  -h                    show this help message and exit\n"
                + "  -q                    quiet mode\n",
                "prog");
        check("-q is a switch", sw.option("-q") != null && sw.option("-q").isSwitch);
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
        line("Value positions survive both overflowing and wrapped layouts.");
    }

    private static void expectArg(Schema s, String flag, String want) {
        Schema.Option o = s.option(flag);
        boolean ok = o != null && want.equals(o.arg);
        check(flag + " -> [" + want + "]" + (ok ? "" : " (got "
                + (o == null ? "absent" : "[" + o.arg + "]")) + ")",
                ok);
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

package bioenv.test;

import bioenv.core.HelpParser;
import bioenv.core.Schema;

/**
 * Verifies recognition of a declared type column.
 *
 * <p>Some documents print the option's type in a field of its own, between the
 * name and its description. That is the author stating the type rather than
 * leaving it to be inferred, so it must be taken as final — no reading of the
 * description may overrule it.
 *
 * <p>Run: {@code java -cp &lt;classes&gt; bioenv.test.TypeColumnTest}
 */
public final class TypeColumnTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        line("=== Declared type column ===");
        line("");

        line("[each type word maps to the right control]");
        Schema s = HelpParser.parse(
                "Do the thing\n"
                + "Version: 1.0\n"
                + "\n"
                + "   Standard (Mandatory) qualifiers:\n"
                + "  [-infile]            infile     input data file\n"
                + "  [-outfile]           outfile    output file name\n"
                + "   -width              integer    width of the display\n"
                + "   -factor             float      scale factor to apply\n"
                + "   -label              string     text to draw\n"
                + "   -verbose            boolean    report extra detail\n"
                + "   -mode               list       one of several modes\n",
                "demo");

        expect(s, "-infile", Schema.Option.Kind.INPUT_PATH);
        expect(s, "-outfile", Schema.Option.Kind.OUTPUT_PATH);
        expect(s, "-width", Schema.Option.Kind.NUMBER);
        expect(s, "-factor", Schema.Option.Kind.NUMBER);
        expect(s, "-label", Schema.Option.Kind.TEXT);
        expect(s, "-verbose", Schema.Option.Kind.SWITCH);
        expect(s, "-mode", Schema.Option.Kind.CHOICE);
        line("");

        line("[all of them are marked as declared, not inferred]");
        for (Schema.Option o : s.options) {
            boolean declared = o.evidence == Schema.Option.Evidence.TYPE_COLUMN
                    && o.declaredKind != null
                    && o.confidence == TypeInferenceConfidence.HIGH;
            check(o.flag + " is declared (" + o.declaredWord + ")", declared);
        }
        line("");

        line("[the described type wins over any reading of the description]");
        // The description reads like an output path, but the author declared it
        // an input. The declaration is the statement; the prose is not.
        Schema overrule = HelpParser.parse(
                "Qualifiers:\n"
                + "  [-infile]            infile     write the output file name here\n"
                + "  [-outfile]           outfile    read the input from here\n",
                "demo");
        Schema.Option in = overrule.option("-infile");
        check("declared input survives an output-sounding description",
                in != null && in.kind == Schema.Option.Kind.INPUT_PATH);
        check("the reason names the declared word",
                in != null && in.typeReason.contains("infile"));
        line("");

        line("[a switch declared as boolean takes no value]");
        Schema.Option verb = s.option("-verbose");
        check("declared boolean is a switch",
                verb != null && verb.kind == Schema.Option.Kind.SWITCH);
        check("and is marked as taking no value", verb != null && verb.isSwitch);
        line("");

        line("[bracketed names are unwrapped]");
        check("the bracket is not part of the name",
                s.option("-infile") != null && s.option("[-infile]") == null);
        check("the description is still read",
                s.option("-infile") != null
                        && s.option("-infile").description.contains("input data file"));
        line("");

        line("[a boolean written as both of its forms]");
        Schema both = HelpParser.parse(
                "Qualifiers:\n"
                + "   -[no]sequence       boolean    [Y] display the sequence\n"
                + "   -[no]quality        boolean    [Y] show quality scores\n",
                "demo");
        check("read under its positive name", both.option("-sequence") != null);
        check("it is a switch",
                both.option("-sequence") != null
                        && both.option("-sequence").kind == Schema.Option.Kind.SWITCH);
        check("the default is still captured",
                both.option("-sequence") != null
                        && "Y".equals(both.option("-sequence").defaultValue));
        line("");

        line("[words that are not types are not treated as one]");
        // "or" and a concrete example value both land in the same position by
        // accident. The list is closed precisely so that they do not pass.
        Schema noise = HelpParser.parse(
                "Qualifiers:\n"
                + "   -alpha              or         first alternative\n"
                + "   -beta               dNTP_conc  concentration to use\n"
                + "   -gamma              somefile   a file to read\n",
                "demo");
        for (Schema.Option o : noise.options) {
            check(o.flag + " was not declared a type",
                    o.declaredKind == null
                            && o.evidence != Schema.Option.Evidence.TYPE_COLUMN);
        }
        line("");

        line("[an ordinary document is unaffected]");
        Schema plain = HelpParser.parse(
                "Usage: demo [options]\n"
                + "\n"
                + "Options:\n"
                + "  -i, --infile FILE   input file name\n"
                + "  -n, --num N         number of items\n",
                "demo");
        for (Schema.Option o : plain.options) {
            check(o.flag + " has no declared type", o.declaredKind == null);
        }
        check("its type still comes from the placeholder",
                plain.option("--infile") != null
                        && plain.option("--infile").evidence
                            == Schema.Option.Evidence.PLACEHOLDER);
        line("");

        line("[a single aligned line does not make a column]");
        // The shape must repeat. One line that happens to align is far more
        // likely to be an ordinary description.
        Schema one = HelpParser.parse(
                "Usage: demo [options]\n"
                + "\n"
                + "Options:\n"
                + "  -x            integer    the only aligned line\n"
                + "  -y  something else entirely\n",
                "demo");
        Schema.Option x = one.option("-x");
        check("no declaration claimed from one line",
                x == null || x.declaredKind == null);
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
        line("A declared type is read and taken as final.");
    }

    private static final class TypeInferenceConfidence {
        static final bioenv.core.TypeInference.Confidence HIGH =
                bioenv.core.TypeInference.Confidence.HIGH;
    }

    private static void expect(Schema s, String flag, Schema.Option.Kind want) {
        Schema.Option o = s.option(flag);
        boolean ok = o != null && o.kind == want;
        check(flag + " -> " + want + (ok ? "" : " (got "
                + (o == null ? "absent" : o.kind + "/" + o.typeReason) + ")"), ok);
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

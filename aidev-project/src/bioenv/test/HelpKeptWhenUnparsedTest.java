package bioenv.test;

import java.util.ArrayList;
import java.util.List;

import bioenv.core.HelpParser;
import bioenv.core.Schema;
import bioenv.core.Tool;
import bioenv.ui.SchemaFormPanel;

/**
 * Verifies that a tool which prints help the parser cannot read still keeps that
 * help, so "view raw help" has something to show.
 *
 * <p>Reported as: a tool has documentation, but the structure is unusual enough
 * that nothing is inferred from it, and the documented text cannot be displayed
 * either. The second half was the defect - a document is worth reading precisely
 * when it could not be parsed, because reading it by eye is how the user fills in
 * the parameters the form could not offer.
 *
 * <p>Cause: bestOf() ranked candidates purely on option count and started from an
 * empty schema. When every candidate parsed to zero options, nothing ever beat
 * that empty schema, so the loop returned it - and its rawHelp was blank. Every
 * unreadable document was thrown away, whatever text it held.
 */
public final class HelpKeptWhenUnparsedTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        line("=== A tool with help that does not parse ===");
        line("");

        line("[a document with no options at all]");
        // Prose, a synopsis, an example - nothing in the shape of an option list.
        String prose = "myprog - does a thing\n"
                + "\n"
                + "This tool takes an input file and writes a report.\n"
                + "Usage: myprog <input> <output>\n"
                + "Example: myprog data.txt report.txt\n";
        Schema s = bestOfOne(prose, "myprog");
        check("no options are inferred", s.options.isEmpty());
        check("the document is kept", !s.rawHelp.trim().isEmpty());
        check("and it is the whole document",
                s.rawHelp.contains("This tool takes an input file")
                        && s.rawHelp.contains("Example: myprog data.txt"));
        line("");

        line("[the EMBOSS qualifier-block shape]");
        // The reported case: qualifier sections with bracketed names, which this
        // parser does read - kept here so the shape that prompted the report is
        // covered either way round.
        String emboss = "Extract amino acid property data from AAINDEX\n"
                + "Version: EMBOSS:6.6.0.0\n"
                + "\n"
                + "   Standard (Mandatory) qualifiers:\n"
                + "  [-infile]            infile     AAINDEX database file\n"
                + "\n"
                + "   General qualifiers:\n"
                + "   -help               boolean    Report command line options and exit.\n";
        Schema e = bestOfOne(emboss, "aaindexextract");
        check("the EMBOSS document parses", !e.options.isEmpty());
        check("and its text is kept too", !e.rawHelp.trim().isEmpty());
        line("");

        line("[an error, which is not help but is still what the tool said]");
        // A broken wrapper script: no help, but the message explains why. Showing
        // it is more useful than showing nothing.
        String broken = "dirname: missing operand\n"
                + "Try 'dirname --help' for more information.\n"
                + "/envs/x/bin/aaindexextract: 7: _aaindexextract: not found\n";
        Schema b = bestOfOne(broken, "aaindexextract");
        check("nothing is inferred from an error", b.options.isEmpty());
        check("the message is kept, so the user can see why",
                b.rawHelp.contains("not found"));
        line("");

        line("[a document that does parse is unaffected]");
        String good = "Usage: myprog [options]\n\nOptions:\n"
                + "  -i <file>   input file\n"
                + "  -o <file>   output file\n";
        Schema g = bestOfOne(good, "myprog");
        check("its options are found", g.options.size() == 2);
        check("its text is the text that parsed", g.rawHelp.contains("-i <file>"));
        line("");

        line("[the richest candidate still wins]");
        // The new condition must not let a smaller document displace a better one.
        List<String> several = new ArrayList<String>();
        several.add("no options here, just prose\n");
        several.add(good);
        Schema picked = HelpParser.bestOf(several, "myprog");
        check("the candidate with options is chosen", picked.options.size() == 2);
        line("");

        line("[the form can show it]");
        Tool t = new Tool("myprog", "env", "/envs/x");
        t.schema = s;
        SchemaFormPanel form = new SchemaFormPanel();
        form.showTool(t);
        form.setSize(900, 600);
        check("the form still builds with no options", true);
        check("the tool is still opaque, so raw command mode applies", t.isOpaque());
        check("the schema the form holds carries the text",
                form.currentSchema() != null
                        && !form.currentSchema().rawHelp.trim().isEmpty());
        line("");

        line("  passed: " + pass + "   failed: " + fail);
        if (fail > 0) {
            line("");
            line("FAILURES PRESENT");
            System.exit(1);
        }
    }

    private static Schema bestOfOne(String text, String command) {
        List<String> outs = new ArrayList<String>();
        outs.add(text);
        return HelpParser.bestOf(HelpParser.dedupe(outs), command);
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

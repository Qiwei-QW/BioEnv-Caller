package bioenv.test;

import bioenv.core.CommandBuilder;
import bioenv.core.CorrectionStore;
import bioenv.core.DiscoveryCache;
import bioenv.core.EnvFingerprint;
import bioenv.core.EnvRunner;
import bioenv.core.EnvScanner;
import bioenv.core.HelpParser;
import bioenv.core.NoiseFilter;
import bioenv.core.ParamValue;
import bioenv.core.Runner;
import bioenv.core.Schema;
import bioenv.core.SchemaBuilder;
import bioenv.core.Tool;
import bioenv.core.ToolRegistry;
import bioenv.core.TypeInference;
import bioenv.core.VersionParser;
import bioenv.core.WslBridge;
import bioenv.ui.MainPanel;
import bioenv.ui.SchemaFormPanel;
/**
 * Verifies parameter extraction from usage synopses.
 *
 * <p>A tool's required inputs and outputs are frequently declared only in its
 * synopsis, never repeated in the option listing. Ignoring the synopsis
 * therefore hides exactly the parameters a user must fill in — the earlier
 * parser dropped such lines entirely, so an input flag declared only there was
 * invisible (DESIGN.md P18).
 *
 * <p>The samples are constructed to cover the sub-forms and the ambiguities,
 * rather than taken from any one tool, so the rules are exercised against the
 * format rather than against a particular program's help text.
 *
 * <p>Run: {@code java -cp <classes> bioenv.core.UsageLineTest}
 */
public final class UsageLineTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        line("=== Usage synopsis extraction ===");
        line("");

        // ---------- the three sub-forms ------------------------------------
        line("[bare flag with an angle placeholder]");
        Schema s1 = parse("usage: mytool [options] -f <dir>\n"
                + "  -t <int>   Number of threads\n", "mytool");
        expectOption(s1, "-f", "<dir>", Schema.Option.Kind.INPUT_PATH);
        check("documented option still present", s1.option("-t") != null);
        line("");

        line("[each parameter in its own brackets]");
        Schema s2 = parse("USAGE\n"
                + "  mytool [-query input_file] [-out output_file]\n"
                + "    [-num_threads int_value] [-ungapped]\n", "mytool");
        expectOption(s2, "-query", "input_file", Schema.Option.Kind.INPUT_PATH);
        expectOption(s2, "-out", "output_file", Schema.Option.Kind.OUTPUT_PATH);
        expectOption(s2, "-num_threads", "int_value", Schema.Option.Kind.NUMBER);
        check("bracketed switch captured", s2.option("-ungapped") != null);
        line("");

        line("[several parameters on one synopsis line]");
        Schema s3 = parse("Basic usage\n"
                + "\ttool -in <inputfile> -out <outputfile> -(other options).\n", "tool");
        expectOption(s3, "-in", "<inputfile>", Schema.Option.Kind.INPUT_PATH);
        expectOption(s3, "-out", "<outputfile>", Schema.Option.Kind.OUTPUT_PATH);
        line("");

        // ---------- the bracket ambiguity ----------------------------------
        line("[brackets meaning \"other options\" are skipped]");
        Schema s4 = parse("usage: mytool [options] -f <dir>\n"
                + "usage: other [args] --input <file>\n", "mytool");
        check("'[options]' produced no option named 'options'",
                s4.option("options") == null && s4.option("-options") == null);
        check("the real flag was still extracted", s4.option("-f") != null);
        line("");

        // ---------- a flag that is not a flag ------------------------------
        line("[things that look like flags but are not parameters]");
        Schema s5 = parse("usage: mytool [options] -f <dir>\n"
                + "   -5 is a value, not a flag\n"
                + "   -- is the separator\n", "mytool");
        check("lone '--' not captured", s5.option("--") == null);
        check("negative number not captured as a flag", s5.option("-5") == null);
        line("");

        // ---------- the option section wins --------------------------------
        line("[a documented option is not duplicated by the synopsis]");
        Schema s6 = parse("usage: mytool -t <int> -f <dir>\n"
                + "OPTIONS:\n"
                + "  -t <int>   Number of threads\n"
                + "  -f <dir>   Input directory\n", "mytool");
        int count = 0;
        for (Schema.Option o : s6.options) {
            if ("-t".equals(o.flag)) {
                count++;
            }
        }
        check("'-t' appears exactly once", count == 1);
        Schema.Option t = s6.option("-t");
        check("the documented description was kept",
                t != null && t.description.contains("Number of threads"));
        check("the documented entry is not marked as usage-only",
                t != null && !t.fromUsageLine);
        line("");

        // ---------- provenance is recorded ---------------------------------
        line("[synopsis-only options are flagged]");
        Schema s7 = parse("usage: mytool -f <dir>\n"
                + "OPTIONS:\n"
                + "  -t <int>   Threads\n", "mytool");
        Schema.Option f = s7.option("-f");
        check("'-f' marked as coming from the synopsis",
                f != null && f.fromUsageLine);
        check("'-f' has no description", f != null && f.description.isEmpty());
        Schema.Option t2 = s7.option("-t");
        check("'-t' is not marked", t2 != null && !t2.fromUsageLine);
        line("");

        // ---------- numbered placeholders ----------------------------------
        line("[numbered placeholders carry no type meaning]");
        Schema s8 = parse("usage: mytool -f <dir1> --core <dir2> -o <file1>\n", "mytool");
        expectOption(s8, "-f", "<dir1>", Schema.Option.Kind.INPUT_PATH);
        expectOption(s8, "--core", "<dir2>", Schema.Option.Kind.INPUT_PATH);
        expectOption(s8, "-o", "<file1>", Schema.Option.Kind.INPUT_PATH);
        line("");

        // ---------- prose in a synopsis must not become options -------------
        line("[help text with no synopsis is unaffected]");
        Schema s9 = parse("OPTIONS:\n"
                + "  -a <int>   First thing\n"
                + "  -b <int>   Second thing\n", "mytool");
        check("exactly two options", s9.options.size() == 2);
        check("none marked as usage-only",
                !s9.options.get(0).fromUsageLine && !s9.options.get(1).fromUsageLine);
        line("");

        // ---------- tables must not become subcommands ---------------------
        // Help often opens with a small aligned table. Its rows look exactly
        // like a command listing — a short token then text — so a table that is
        // mistaken for one produces subcommands that do not exist.
        line("[aligned tables are not command listings]");
        Schema t1 = parse(
                "Compression level:\n"
                + "  format   range   default  comment\n"
                + "  gzip     1-9     5        https://example.org/pgzip\n"
                + "  xz       NA      NA       does not support\n"
                + "\n"
                + "Usage:\n"
                + "  mytool [command]\n"
                + "\n"
                + "Commands for Basic Operation:\n"
                + "  alpha           do the first thing\n"
                + "  beta            do the second thing\n"
                + "  gamma           do the third thing\n", "mytool");
        boolean hasGzip = false;
        boolean hasFormat = false;
        for (Schema.Subcommand sc : t1.subcommands) {
            if (sc.name.equals("gzip")) {
                hasGzip = true;
            }
            if (sc.name.equals("format")) {
                hasFormat = true;
            }
        }
        check("table row 'gzip' is not a subcommand", !hasGzip);
        check("table header 'format' is not a subcommand", !hasFormat);
        check("real subcommands were still found",
                t1.sub("alpha") != null && t1.sub("beta") != null
                        && t1.sub("gamma") != null);
        line("");

        line("[the synopsis contributes parameters the option list does not]");
        // Some tools name parameters only in the synopsis. Those must be kept:
        // dropping the synopsis along with the examples would lose them.
        // Measured, 100 such parameters occur across 34 of 107 documents.
        Schema only = parse(
                "Usage: demo -f <dir> [-o <file>]\n", "demo");
        check("-f was captured", only.option("-f") != null);
        check("-f is marked as coming from the synopsis",
                only.option("-f") != null && only.option("-f").fromUsageLine);
        check("-o was captured", only.option("-o") != null);
        line("");

        line("[the option list wins when both name a parameter]");
        // Merging must not duplicate, and the entry that carries a description
        // and a type is the better one to keep.
        Schema merged = parse(
                "Usage: demo -i FILE\n"
                + "\n"
                + "Options:\n"
                + "  -i FILE     input file\n", "demo");
        check("-i appears exactly once", merged.options.size() == 1);
        check("-i keeps the option list entry",
                merged.option("-i") != null && !merged.option("-i").fromUsageLine);
        check("-i keeps its description",
                merged.option("-i") != null
                        && merged.option("-i").description.contains("input file"));
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
        line("Synopses contribute their parameters without polluting the schema.");
    }

    private static Schema parse(String text, String cmd) {
        return HelpParser.parse(text, cmd);
    }

    private static void expectOption(Schema s, String flag, String arg,
                                     Schema.Option.Kind kind) {
        Schema.Option o = s.option(flag);
        if (o == null) {
            fail(flag + " not found at all");
            return;
        }
        boolean ok = kind == o.kind && (arg == null || arg.equals(o.arg));
        if (ok) {
            passed++;
            line("  PASS  " + flag + " -> " + o.kind + " (" + o.arg + ")");
        } else {
            failed++;
            line("  FAIL  " + flag + "  expected " + kind + " (" + arg
                    + "), got " + o.kind + " (" + o.arg + ")");
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

    private static void fail(String why) {
        failed++;
        line("  FAIL  " + why);
    }

    private static void line(String s) {
        System.out.println(s);
    }
}

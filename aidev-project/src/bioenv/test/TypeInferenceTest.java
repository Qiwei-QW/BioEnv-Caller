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
 * Verifies type inference against help texts whose real formats are known.
 *
 * <p>The fixtures below are shortened but structurally faithful copies of help
 * output observed in the wild, covering the format families that matter:
 *
 * <ul>
 *   <li><b>hand-written C++</b> — no placeholder at all, the type exists only
 *       in the description's leading noun.</li>
 *   <li><b>NCBI style</b> — macro-expanded placeholders that name their own type
 *       ({@code input_file}, {@code int_value}, {@code float_value}).</li>
 *   <li><b>shell wrapper</b> — {@code #} for numbers and {@code default: X} in
 *       prose.</li>
 *   <li><b>enumerated candidates</b> — braces, pipes and "one of:".</li>
 * </ul>
 *
 * <p>Run: {@code java -cp <classes> bioenv.core.TypeInferenceTest}
 */
public final class TypeInferenceTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        line("=== Type inference test ===");
        line("");

        // ---------- no placeholder: type only in the description -----------
        line("[hand-written help with no metavariables]");
        expect("--infile is a path",
                "--infile, -i            Path to tab delimited gene families file to be analyzed - Required for estimation.",
                Schema.Option.Kind.INPUT_PATH);
        expect("--tree is a path",
                "--tree, -t              Path to file containing newick formatted tree - Required for estimation.",
                Schema.Option.Kind.INPUT_PATH);
        expect("--cores is a number",
                "--cores, -c             Number of processing cores to use, requires an integer argument. Default=All available cores.",
                Schema.Option.Kind.NUMBER);
        expect("--output_prefix is an output path",
                "--output_prefix, -o     Output directory - Name of directory automatically created for output. Default=results.",
                Schema.Option.Kind.OUTPUT_PATH);
        expect("--pvalue is a number",
                "--pvalue, -P            P-value to use for determining significance of family size change, Default=0.05.",
                Schema.Option.Kind.NUMBER);
        expect("--help is a switch",
                "--help, -h              This help menu.",
                Schema.Option.Kind.SWITCH);
        expect("--zero_root is a switch",
                "--zero_root, -z         Include gene families that don't exist at the root, not recommended.",
                Schema.Option.Kind.SWITCH);
        check("--fixed_lambda gets a range",
                ranges("--fixed_lambda, -l      Value (between 0 and 1) for a single user provided lambda value."));
        line("");

        // ---------- placeholders that carry the type ------------------------
        line("[placeholders that name their own type]");
        expect("-query is an input path",
                "-query input_file        Input query sequence",
                Schema.Option.Kind.INPUT_PATH);
        expect("-out is an output path",
                "-out output_file         Output file name",
                Schema.Option.Kind.OUTPUT_PATH);
        expect("-word_size is a number",
                "-word_size int_value     Word size for wordfinder algorithm",
                Schema.Option.Kind.NUMBER);
        expect("-perc_identity is a number",
                "-perc_identity float_value  Percent identity cutoff",
                Schema.Option.Kind.NUMBER);
        expect("-num_threads is a number",
                "-num_threads int_value   Number of threads",
                Schema.Option.Kind.NUMBER);
        expect("-ungapped is a switch",
                "-ungapped                Perform ungapped alignment",
                Schema.Option.Kind.SWITCH);
        line("");

        // ---------- shell wrapper conventions -------------------------------
        line("[shell wrapper: '#' means number, defaults in prose]");
        expect("--op is a number",
                "--op # :         Gap opening penalty, default: 1.53",
                Schema.Option.Kind.NUMBER);
        expect("--thread is a number",
                "--thread # :     Number of threads (if unsure, --thread -1)",
                Schema.Option.Kind.NUMBER);
        expect("--quiet is a switch",
                "--quiet :        Do not report progress",
                Schema.Option.Kind.SWITCH);
        expect("--clustalout is a switch",
                "--clustalout :   Output: clustal format, default: fasta",
                Schema.Option.Kind.SWITCH);
        line("");

        // ---------- enumerated candidates -----------------------------------
        line("[enumerated candidates]");
        expect("brace list becomes a choice",
                "--simulate {space,linear}   Simulation mode",
                Schema.Option.Kind.CHOICE);
        expect("pipe list becomes a choice",
                "--mode dna|rna|protein       Sequence type",
                Schema.Option.Kind.CHOICE);
        expect("'one of:' becomes a choice",
                "--strand one of: plus, minus, both",
                Schema.Option.Kind.CHOICE);
        check("choices are captured",
                choicesOf("--simulate {space,linear}").contains("space")
                && choicesOf("--simulate {space,linear}").contains("linear"));
        line("");

        // ---------- defaults: strong evidence only --------------------------
        line("[defaults must not be invented]");
        check("explicit 'Default=0.05' is taken",
                "0.05".equals(defaultOf("--pvalue   P-value, Default=0.05.")));
        check("narrative 'default: 1.53' is taken",
                "1.53".equals(defaultOf("--op # : Gap opening penalty, default: 1.53")));
        check("prose bracket [specified] is NOT taken",
                defaultOf("--n_gamma_cats   Number of gamma rate categories [specified].").isEmpty());
        check("prose bracket [estimated] is NOT taken",
                defaultOf("--n_gamma_cats   Number of categories [estimated].").isEmpty());
        check("numeric bracket [120] IS taken",
                "120".equals(defaultOf("--textw <n>  max width of output lines [120] (n>=120)")));
        check("'Default=All available cores' is NOT taken",
                defaultOf("--cores  Number of cores. Default=All available cores.").isEmpty());
        line("");

        // ---------- end-to-end over a multi-line help block -----------------
        line("[end to end: hand-written C++ help block]");
        Schema s = HelpParser.parse(
                "OPTIONS:\n"
                + "  Required Options:\n"
                + "   --infile, -i\t\t\tPath to tab delimited gene families file to be analyzed - Required for estimation.\n"
                + "   --tree, -t\t\t\tPath to file containing newick formatted tree - Required for estimation.\n"
                + "  Common Options:\n"
                + "   --cores, -c\t\t\tNumber of processing cores to use, requires an integer argument.\n"
                + "   --help, -h\t\t\tThis help menu.\n",
                "fixture");
        check("four options parsed", s.options.size() == 4);
        Schema.Option infile = findFlag(s, "--infile");
        check("--infile exists", infile != null);
        if (infile != null) {
            check("--infile is a path, not a switch",
                    infile.kind == Schema.Option.Kind.INPUT_PATH);
            check("--infile keeps its alias -i", infile.aliases.contains("-i"));
                check("--infile has no invented default", infile.defaultValue.isEmpty());
        }
        Schema.Option help = findFlag(s, "--help");
        check("--help is still a switch",
                help != null && help.kind == Schema.Option.Kind.SWITCH);
        line("");

        // ---------- candidates listed beneath the option -------------------
        line("[candidates listed on their own lines]");
        Schema indented = HelpParser.parse(
                "OPTIONS:\n"
                + "  -f DIR           Input directory\n"
                + "  -M STR           Method to use\n"
                + "                   msa          use MSA\n"
                + "                   dendroblast  use dendroblast\n"
                + "                   orthoxml     use orthoxml\n"
                + "  -t NUM           Number of threads\n",
                "fixture");
        check("three options parsed", indented.options.size() == 3);
        Schema.Option m = indented.option("-M");
        check("-M became a choice",
                m != null && m.kind == Schema.Option.Kind.CHOICE);
        check("-M collected every candidate",
                m != null && m.choices.contains("msa")
                        && m.choices.contains("dendroblast")
                        && m.choices.contains("orthoxml"));
        check("-M is high confidence",
                m != null && m.confidence == TypeInference.Confidence.HIGH);
        // Neighbouring options must not absorb the candidate list.
        Schema.Option f = indented.option("-f");
        Schema.Option t = indented.option("-t");
        check("-f keeps its own type",
                f != null && f.kind == Schema.Option.Kind.INPUT_PATH);
        check("-f got no candidates", f != null && f.choices.isEmpty());
        check("-t stays numeric", t != null && t.kind == Schema.Option.Kind.NUMBER);
        check("-t got no candidates", t != null && t.choices.isEmpty());
        line("");

        // A single indented row is a wrapped sentence, not a value list.
        line("[a lone indented row is not a candidate list]");
        Schema single = HelpParser.parse(
                "  -q STR     Query sequence\n"
                + "             may also be a file of sequences\n",
                "fixture");
        Schema.Option q = single.option("-q");
        check("lone row did not create a choice",
                q != null && q.kind != Schema.Option.Kind.CHOICE);
        line("");

        // ---------- confidence reporting ------------------------------------
        line("[confidence reporting]");
        checkConfidence("placeholder match is HIGH",
                "-query input_file   Input query", TypeInference.Confidence.HIGH);
        checkConfidence("leading-noun match is MEDIUM",
                "--cores, -c   Number of processing cores", TypeInference.Confidence.MEDIUM);
        checkConfidence("switch with a description is MEDIUM",
                "--verbose   Print more messages", TypeInference.Confidence.MEDIUM);
        checkConfidence("switch with no description is LOW",
                "--zzz", TypeInference.Confidence.LOW);
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
        line("Inference behaves as designed on every known format family.");
    }

    // ------------------------------------------------------------------
    // Fixture helpers
    // ------------------------------------------------------------------

    private static Schema.Option parseOne(String helpLine) {
        Schema s = HelpParser.parse(helpLine, "fixture");
        return s.options.isEmpty() ? null : s.options.get(0);
    }

    private static String defaultOf(String helpLine) {
        Schema.Option o = parseOne(helpLine);
        return o == null || o.defaultValue == null ? "" : o.defaultValue;
    }

    private static java.util.List<String> choicesOf(String helpLine) {
        Schema.Option o = parseOne(helpLine);
        return o == null ? new java.util.ArrayList<String>() : o.choices;
    }

    private static boolean ranges(String helpLine) {
        Schema.Option o = parseOne(helpLine);
        return o != null && (o.min != null || o.max != null);
    }

    private static Schema.Option findFlag(Schema s, String flag) {
        for (Schema.Option o : s.options) {
            if (flag.equals(o.flag)) {
                return o;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Assertions
    // ------------------------------------------------------------------

    private static void expect(String label, String helpLine, Schema.Option.Kind want) {
        Schema.Option o = parseOne(helpLine);
        Schema.Option.Kind got = o == null ? null : o.kind;
        boolean ok = want == got;
        if (ok) {
            passed++;
        } else {
            failed++;
        }
        line("  " + (ok ? "PASS" : "FAIL") + "  " + label
                + (ok ? "" : "   expected=" + want + "  actual=" + got
                        + "  reason=" + (o == null ? "n/a" : o.typeReason)));
    }

    private static void checkConfidence(String label, String helpLine,
                                        TypeInference.Confidence want) {
        Schema.Option o = parseOne(helpLine);
        TypeInference.Confidence got = o == null ? null : o.confidence;
        boolean ok = want == got;
        if (ok) {
            passed++;
        } else {
            failed++;
        }
        line("  " + (ok ? "PASS" : "FAIL") + "  " + label
                + (ok ? "" : "   expected=" + want + "  actual=" + got));
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

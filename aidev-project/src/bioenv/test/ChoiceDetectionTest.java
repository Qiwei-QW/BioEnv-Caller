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
 * Verifies candidate-value recognition against many wordings, deliberately
 * avoiding any real tool.
 *
 * <p>The point of this test is generality: the recogniser must not depend on
 * the author choosing a particular English word (DESIGN.md P16). Each case
 * below uses a different label — or none at all — and the same structure must
 * still be recognised. A test written around one real tool would only prove the
 * code works for that tool.
 *
 * <p>Run: {@code java -cp <classes> bioenv.core.ChoiceDetectionTest}
 */
public final class ChoiceDetectionTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        line("=== Candidate value detection (structure, not wording) ===");
        line("");

        // ---------- must be recognised, whatever the label ---------------
        line("[recognised regardless of surrounding wording]");
        // Same structure, nine different labels, plus one with no label.
        String[] labels = {
                "Options:", "Choices:", "Available:", "Permitted:", "Supported:",
                "Values:", "Valid:", "Modes:", "One of:", "Use one of",
                "May be:", "Allowed:", "Set to", "Either",
        };
        for (String label : labels) {
            String desc = "Something or other " + label + " alpha, beta, gamma"
                    + " [Default = alpha]";
            expectChoice("label \"" + label + "\"", "<txt>", desc,
                    new String[]{"alpha", "beta", "gamma"});
        }
        // No label at all: plain sentence, then the list, corroborated by the
        // default being one of the values.
        expectChoice("no label, default corroborates", "<txt>",
                "Does a thing. alpha, beta, gamma [Default = beta]",
                new String[]{"alpha", "beta", "gamma"});
        line("");

        // ---------- syntax forms ------------------------------------------
        line("[syntax forms need no corroboration]");
        expectChoice("braces", "<txt>", "Mode of operation {alpha,beta,gamma}",
                new String[]{"alpha", "beta", "gamma"});
        expectChoice("pipes", "<txt>", "Mode: alpha|beta|gamma",
                new String[]{"alpha", "beta", "gamma"});
        expectChoice("ampersand", "<txt>", "Method: \"alpha\" & \"beta\"",
                new String[]{"alpha", "beta"});
        expectChoice("quoted with and", "<txt>", "Method: \"alpha\" and \"beta\"",
                new String[]{"alpha", "beta"});
        // A brace list in the placeholder itself.
        expectChoice("braces in placeholder", "{alpha,beta,gamma}",
                "Mode of operation", new String[]{"alpha", "beta", "gamma"});
        line("");

        // ---------- prose must NOT be mistaken for a value list -----------
        line("[ordinary prose is not a value list]");
        expectNoChoice("sentence with commas", "<txt>",
                "Reads the file, checks the format, then writes the result");
        expectNoChoice("two words, no corroboration", "<txt>",
                "Something about alpha, beta happening here");
        expectNoChoice("long phrases", "<txt>",
                "Sets the mode of operation, the format of output, the level");
        expectNoChoice("numeric range", "<n>",
                "Number of threads, between 1 and 512");
        expectNoChoice("no list at all", "<txt>", "Print this help text");
        line("");

        // ---------- defaults are still read ------------------------------
        line("[defaults]");
        expectDefault("square bracket", "<n>", "Number of threads [16]", "16");
        expectDefault("explicit equals", "<txt>", "Method, Default = alpha", "alpha");
        expectDefault("explicit colon", "<txt>", "Method, default: alpha", "alpha");
        expectDefault("prose bracket rejected", "<txt>",
                "Number of categories [specified]", "");
        line("");

        // ---------- the whole pipeline on a multi-line option ------------
        // Structure recognition only works if the description is assembled
        // before classification (DESIGN.md P17).
        line("[classification waits for a complete description]");
        Schema s = HelpParser.parse(
                "  -S <txt>   Sequence search program [Default = diamond]\n"
                + "             Options: diamond, diamond_ultra_sens, blastp\n"
                + "  -t <int>   Number of threads [Default = 4]\n",
                "fixture");
        Schema.Option opt = s.option("-S");
        check("continuation-line list was seen",
                opt != null && opt.choices.contains("blastp")
                        && opt.choices.contains("diamond_ultra_sens"));
        check("type became a choice",
                opt != null && opt.kind == Schema.Option.Kind.CHOICE);
        Schema.Option threads = s.option("-t");
        check("numeric option unaffected",
                threads != null && threads.kind == Schema.Option.Kind.NUMBER);
        check("its default was still read",
                threads != null && "4".equals(threads.defaultValue));
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
        line("Recognition follows structure and content, not wording.");
    }

    // ------------------------------------------------------------------

    private static void expectChoice(String label, String arg, String desc,
                                     String[] want) {
        Schema.Option o = parseOne(arg, desc);
        if (o == null) {
            fail(label, "no option parsed");
            return;
        }
        boolean ok = o.kind == Schema.Option.Kind.CHOICE;
        for (String w : want) {
            ok = ok && o.choices.contains(w);
        }
        if (ok) {
            passed++;
            line("  PASS  " + label + "  -> " + o.choices);
        } else {
            failed++;
            line("  FAIL  " + label + "  expected " + java.util.Arrays.toString(want)
                    + " as CHOICE, got kind=" + o.kind + " choices=" + o.choices);
        }
    }

    private static void expectNoChoice(String label, String arg, String desc) {
        Schema.Option o = parseOne(arg, desc);
        if (o == null) {
            fail(label, "no option parsed");
            return;
        }
        if (o.choices.isEmpty()) {
            passed++;
            line("  PASS  " + label + "  -> no choices (kind=" + o.kind + ")");
        } else {
            failed++;
            line("  FAIL  " + label + "  wrongly produced choices: " + o.choices);
        }
    }

    private static void expectDefault(String label, String arg, String desc,
                                      String want) {
        Schema.Option o = parseOne(arg, desc);
        if (o == null) {
            fail(label, "no option parsed");
            return;
        }
        if (want.equals(o.defaultValue)) {
            passed++;
            line("  PASS  " + label + "  -> \"" + o.defaultValue + "\"");
        } else {
            failed++;
            line("  FAIL  " + label + "  expected \"" + want + "\", got \""
                    + o.defaultValue + "\"");
        }
    }

    /** Parses a single option line so the real pipeline is exercised. */
    private static Schema.Option parseOne(String arg, String desc) {
        String placeholder = arg == null || arg.isEmpty() ? "" : " " + arg;
        Schema s = HelpParser.parse("  -x" + placeholder + "   " + desc, "fixture");
        return s.options.isEmpty() ? null : s.options.get(0);
    }

    private static void fail(String label, String why) {
        failed++;
        line("  FAIL  " + label + "  " + why);
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

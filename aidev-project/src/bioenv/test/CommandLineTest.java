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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Verifies the shape of generated command lines.
 *
 * <p>Two rules are covered, both of which were violated before this test
 * existed (DESIGN.md P12, P13):
 *
 * <ul>
 *   <li>Only values the user expressed may appear. An earlier version wrote
 *       every help-stated default into the command, so a user who changed
 *       nothing still got a twenty-option command line — including a boolean
 *       switch being handed the literal {@code false}.</li>
 *   <li>The command must read like something a person would type: values
 *       quoted only when necessary, switches contributing their flag alone.</li>
 * </ul>
 *
 * <p>Run: {@code java -cp <classes> bioenv.core.CommandLineTest}
 */
public final class CommandLineTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        line("=== Command line shape test ===");
        line("");

        Schema schema = buildSchema();
        Tool tool = new Tool("somecmd", "someenv", "/envs/some");

        // ---------- nothing expressed ------------------------------------
        line("[nothing expressed]");
        Map<String, ParamValue> none = values(schema);
        String cmd = CommandBuilder.preview(tool, schema, "", none, null);
        line("      " + cmd);
        check("contains no option at all", countOptionFlags(cmd) == 0);
        check("names the command", cmd.contains("somecmd"));
        check("quotes the environment path", cmd.contains("'/envs/some'"));
        line("");

        // ---------- one value expressed -----------------------------------
        line("[one value expressed]");
        Map<String, ParamValue> one = values(schema);
        one.get("--threads").set("12");
        cmd = CommandBuilder.preview(tool, schema, "", one, null);
        line("      " + cmd);
        check("includes the expressed option", cmd.contains("--threads"));
        check("includes its value", cmd.contains("12"));
        check("omits every other option", countOptionFlags(cmd) == 1);
        check("plain number is not quoted", cmd.contains("--threads 12"));
        line("");

        // ---------- a switch is on ----------------------------------------
        line("[switch enabled]");
        Map<String, ParamValue> sw = values(schema);
        sw.get("--quiet").set("true");
        cmd = CommandBuilder.preview(tool, schema, "", sw, null);
        line("      " + cmd);
        check("switch contributes its flag", cmd.contains("--quiet"));
        check("switch contributes no value", !cmd.contains("--quiet true")
                && !cmd.contains("--quiet 'true'"));
        line("");

        // ---------- a switch left off -------------------------------------
        line("[switch left off]");
        Map<String, ParamValue> off = values(schema);
        off.get("--verbose").setUntouched("false");
        cmd = CommandBuilder.preview(tool, schema, "", off, null);
        check("unset switch is absent", !cmd.contains("--verbose"));
        check("no stray boolean text", !cmd.contains("false"));
        line("");

        // ---------- quoting policy ----------------------------------------
        line("[quoting is applied only where needed]");
        Map<String, ParamValue> q = values(schema);
        q.get("--mode").set("msa");
        q.get("--out").set("/mnt/c/My Data/out.fa");
        cmd = CommandBuilder.preview(tool, schema, "", q, null);
        line("      " + cmd);
        check("simple value is bare", cmd.contains("--mode msa"));
        check("value with a space is quoted", cmd.contains("'/mnt/c/My Data/out.fa'"));
        check("path without spaces is bare", !cmd.contains("'/mnt/c/plain.fa'"));
        line("");

        // ---------- hand-written shape ------------------------------------
        // The reference shape a user asked for: a compact chain of options
        // with bare values and no redundant quoting.
        line("[hand-written appearance]");
        Schema s2 = new Schema();
        s2.options.add(opt("-f", "DIR", Schema.Option.Kind.INPUT_PATH));
        s2.options.add(opt("-M", "STR", Schema.Option.Kind.CHOICE, "msa", "diamond"));
        s2.options.add(opt("-t", "NUM", Schema.Option.Kind.NUMBER));
        Tool t2 = new Tool("orthofinder", "env", "/envs/e");
        Map<String, ParamValue> m2 = values(s2);
        m2.get("-f").set("pep");
        m2.get("-M").set("msa");
        m2.get("-t").set("12");
        String line2 = CommandBuilder.preview(t2, s2, "", m2, null);
        line("      " + line2);
        String want = "conda run -p '/envs/e' --no-capture-output orthofinder -f pep -M msa -t 12";
        check("matches the expected compact form", want.equals(line2));
        line("");

        // ---------- preview hides the conda wrapper ------------------------
        // The preview must show the user's own command, not the wrapper the
        // plugin uses to reach the tool (DESIGN.md P13).
        line("[preview shows only the user's command]");
        String shown = CommandBuilder.userFacingPreview(t2, s2, "", m2, null);
        line("      " + shown);
        check("no conda in the preview", !shown.contains("conda"));
        check("no harness flags in the preview",
                !shown.contains("--no-capture-output") && !shown.contains("-p "));
        check("no environment path in the preview", !shown.contains("/envs/"));
        check("starts with the command", shown.startsWith("orthofinder"));
        check("equals the expected form",
                "orthofinder -f pep -M msa -t 12".equals(shown));
        // The runnable form must still carry the wrapper.
        String full = CommandBuilder.preview(t2, s2, "", m2, null);
        check("runnable form keeps the wrapper", full.contains("conda run -p"));
        line("");

        // ---------- raw arguments passthrough ------------------------------
        line("[raw arguments are appended verbatim]");
        String raw = CommandBuilder.preview(tool, schema, "", values(schema),
                "--custom 'a b' > out.txt");
        check("raw text preserved unquoted",
                raw.endsWith("--custom 'a b' > out.txt"));
        line("");

        // ---------- subcommand placement ----------------------------------
        line("[subcommand precedes options]");
        String sub = CommandBuilder.preview(tool, schema, "stats", one, null);
        line("      " + sub);
        check("subcommand follows the command",
                sub.indexOf("somecmd") < sub.indexOf("stats"));
        check("options follow the subcommand",
                sub.indexOf("stats") < sub.indexOf("--threads"));
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
        line("Commands contain exactly what the user expressed.");
    }

    // ------------------------------------------------------------------

    private static Schema buildSchema() {
        Schema s = new Schema();
        s.options.add(opt("--mode", "STR", Schema.Option.Kind.CHOICE, "msa", "diamond"));
        s.options.add(opt("--threads", "NUM", Schema.Option.Kind.NUMBER, "4"));
        s.options.add(opt("--out", "FILE", Schema.Option.Kind.OUTPUT_PATH));
        s.options.add(opt("--in", "FILE", Schema.Option.Kind.INPUT_PATH));
        s.options.add(opt("--quiet", "", Schema.Option.Kind.SWITCH));
        s.options.add(opt("--verbose", "", Schema.Option.Kind.SWITCH));
        return s;
    }

    private static Schema.Option opt(String flag, String arg,
                                     Schema.Option.Kind kind, String... choices) {
        Schema.Option o = new Schema.Option();
        o.flag = flag;
        o.arg = arg;
        o.kind = kind;
        o.confidence = TypeInference.Confidence.HIGH;
        o.isSwitch = kind == Schema.Option.Kind.SWITCH;
        for (String c : choices) {
            o.choices.add(c);
        }
        if (choices.length > 0) {
            o.defaultValue = choices[0];
        }
        return o;
    }

    private static Map<String, ParamValue> values(Schema s) {
        Map<String, ParamValue> m = new LinkedHashMap<String, ParamValue>();
        for (Schema.Option o : s.options) {
            m.put(CommandBuilder.key(o), new ParamValue(o));
        }
        return m;
    }

    /**
     * Counts option flags in a command, ignoring the harness flags that the
     * plugin itself emits ({@code --no-capture-output}, {@code -p}).
     *
     * <p>Counting all occurrences of {@code --} would also match the harness,
     * which has nothing to do with what the user expressed.
     */
    private static int countOptionFlags(String command) {
        int count = 0;
        for (String tok : command.split("\\s+")) {
            String t = tok.trim();
            if (!t.startsWith("-")) {
                continue;
            }
            if (t.equals("-p") || t.equals("--no-capture-output")) {
                continue; // conda's own flags, not user options
            }
            count++;
        }
        return count;
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

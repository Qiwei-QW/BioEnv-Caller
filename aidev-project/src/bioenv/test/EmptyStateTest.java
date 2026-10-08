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
import java.util.ArrayList;
import java.util.List;

/**
 * Verifies the framework's behaviour when nothing is installed.
 *
 * <p>The point of this test is that a generic framework must fail gracefully:
 * on a machine with no WSL, or WSL but no conda, or conda but no environments,
 * it should say something useful rather than throw or show an empty window with
 * no explanation (DESIGN.md P4, P6, P8).
 *
 * <p>It cannot uninstall anything, so it simulates the conditions by pointing
 * the scanner at nonexistent roots and by calling the parser with empty and
 * malformed input. What it proves is that every code path is null-safe and that
 * the empty case produces guidance rather than a stack trace.
 *
 * <p>Usage: {@code java -cp <classes> bioenv.core.EmptyStateTest}
 */
public final class EmptyStateTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        line("=== Empty-state and robustness test ===");
        line("");

        // --- discovery when conda is absent ---
        line("[1] Discovery against a bridge that points nowhere");
        WslBridge dead = new WslBridge("definitely-not-a-distro-xyz");
        EnvScanner scanner = new EnvScanner(dead);
        EnvScanner.Report rep = scanner.discover();
        check("report is non-null", rep != null);
        check("reports wsl unreachable", rep != null && !rep.wslOk);
        check("provides a remediation hint", rep != null && !rep.hints.isEmpty());
        check("environment map is empty", scanner.getEnvs().isEmpty());
        check("conda root is null", scanner.getCondaRoot() == null);
        if (rep != null && !rep.hints.isEmpty()) {
            line("      hint shown to user: " + rep.hints.get(0));
        }
        line("");

        // --- registry with no environments ---
        line("[2] Registry over zero environments");
        EnvRunner runner = new EnvRunner(dead, null);
        ToolRegistry registry = new ToolRegistry(scanner, runner);
        ToolRegistry.Report scan = registry.scan();
        check("scan report is non-null", scan != null);
        check("zero environments scanned", scan != null && scan.envsScanned == 0);
        check("no tools offered", registry.getTools().isEmpty());
        check("sorted() is empty, not null", registry.sorted() != null
                && registry.sorted().isEmpty());
        check("search() is empty, not null", registry.search("anything") != null
                && registry.search("anything").isEmpty());
        check("introspect(null) does not throw", safeIntrospect(registry));
        line("");

        // --- parser against degenerate input ---
        line("[3] Parser against empty and malformed help");
        Schema s0 = HelpParser.parse(null, "x");
        check("null input yields empty schema", s0 != null && !s0.hasOptions());
        Schema s1 = HelpParser.parse("", "x");
        check("empty input yields empty schema", s1 != null && !s1.hasOptions());
        Schema s2 = HelpParser.parse("   \n\n \t \n", "x");
        check("whitespace input yields empty schema", s2 != null && !s2.hasOptions());
        Schema s3 = HelpParser.parse("just some prose with no options at all", "x");
        check("prose yields empty schema", s3 != null && !s3.hasOptions());
        Schema s4 = HelpParser.parse("\u0000\u0001binary\u0002garbage", "x");
        check("binary garbage does not throw", s4 != null);
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            huge.append("  -opt").append(i).append("   description ").append(i).append('\n');
        }
        Schema s5 = HelpParser.parse(huge.toString(), "x");
        check("very large help parses", s5 != null && s5.options.size() >= 1000);
        line("      parsed " + s5.options.size() + " options from 5000 lines");
        line("");

        // --- command builder with nothing filled in ---
        line("[4] Command builder with an empty schema and no values");
        Tool t = new Tool("somecmd", "someenv", "/envs/some");
        t.schema = new Schema();
        String cmd = CommandBuilder.preview(t, t.schema, "", null, null);
        check("preview is non-null", cmd != null);
        check("preview names the environment", cmd != null && cmd.contains("/envs/some"));
        check("no stray separators", cmd != null && !cmd.contains("  "));
        // With nothing expressed, no option may appear. The harness flags the
        // plugin emits itself (-p, --no-capture-output) do not count.
        int options = 0;
        for (String tok : (cmd == null ? "" : cmd).split("\\s+")) {
            if (tok.startsWith("-") && !tok.equals("-p")
                    && !tok.equals("--no-capture-output")) {
                options++;
            }
        }
        check("no options leak in", options == 0);
        line("      " + cmd);
        line("");

        // --- path translation edge cases ---
        line("[5] Path translation edge cases");
        check("empty string", "".equals(WslBridge.toWslPath("")));
        check("null", WslBridge.toWslPath(null) == null);
        check("posix passthrough", "/home/x".equals(WslBridge.toWslPath("/home/x")));
        check("drive root", "/mnt/c".equals(WslBridge.toWslPath("C:\\")));
        check("unmapped wsl path",
                "\\\\wsl$\\Ubuntu\\home\\q\\a".equals(
                        WslBridge.toWindowsPath("/home/q/a", "Ubuntu")));
        check("quote empty", "''".equals(WslBridge.quote("")));
        check("quote with quote", "'a'\\''b'".equals(WslBridge.quote("a'b")));
        line("");

        // --- noise filter does not over-accept ---
        line("[6] Noise filter behaviour");
        check("rejects empty", !NoiseFilter.isPlausibleTool(""));
        check("rejects null", !NoiseFilter.isPlausibleTool(null));
        check("rejects single char", !NoiseFilter.isPlausibleTool("x"));
        check("rejects shell builtin", !NoiseFilter.isPlausibleTool("tput"));
        check("accepts an ordinary command", NoiseFilter.isPlausibleTool("myanalysis"));
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
        line("The framework degrades to clear guidance rather than crashing.");
    }

    private static boolean safeIntrospect(ToolRegistry r) {
        try {
            r.introspect(null);
            return true;
        } catch (Exception e) {
            return false;
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

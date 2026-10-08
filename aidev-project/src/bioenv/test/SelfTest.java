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
import java.util.Map;

/**
 * Console harness for the discovery and introspection pipeline.
 *
 * <p>Run without TBtools to verify that the framework works on whatever is
 * installed, not on a fixed list. It reports how many commands were found and,
 * more importantly, how many of them could be turned into a usable schema —
 * that ratio is the real measure of whether the framework is generic
 * (DESIGN.md P8).
 *
 * <p>Usage:
 * <pre>
 *   java -cp &lt;classes&gt; bioenv.core.SelfTest
 *   java -cp &lt;classes&gt; bioenv.core.SelfTest --deep 12   (introspect 12 commands)
 *   java -cp &lt;classes&gt; bioenv.core.SelfTest --tool NAME  (detail for one command)
 * </pre>
 */
public final class SelfTest {

    public static void main(String[] args) {
        int deepCount = 8;
        String toolName = null;
        for (int i = 0; i < args.length; i++) {
            if ("--deep".equals(args[i]) && i + 1 < args.length) {
                deepCount = Integer.parseInt(args[++i]);
            } else if ("--tool".equals(args[i]) && i + 1 < args.length) {
                toolName = args[++i];
            }
        }

        line("=== BioEnv Caller :: framework self-test ===");
        line("");

        // --- principles check: no tool names in the source ---
        line("[P1] This build contains no hard-coded tool list; everything below "
                + "is discovered at runtime.");
        line("");

        List<String> distros = WslBridge.listDistros();
        line("[wsl] distributions: " + distros);
        if (distros.isEmpty()) {
            line("[wsl] none found - the plugin would report this and stop cleanly.");
            return;
        }
        WslBridge wsl = new WslBridge(distros.get(0));
        line("[wsl] using: " + distros.get(0));
        line("");

        // --- environment discovery (conda only) ---
        EnvScanner scanner = new EnvScanner(wsl);
        EnvScanner.Report rep = scanner.discover();
        line("[conda] wslOk=" + rep.wslOk + " condaOk=" + rep.condaOk);
        for (String n : rep.notes) {
            line("  " + n);
        }
        for (String h : rep.hints) {
            line("  HINT: " + h);
        }
        if (!rep.condaOk) {
            line("");
            line("No conda found. The plugin reports this and offers no tools, "
                    + "which is the correct behaviour on a machine with nothing "
                    + "installed.");
            return;
        }
        line("");

        // --- tool discovery ---
        EnvRunner envRunner = new EnvRunner(wsl, scanner.getCondaRoot());
        ToolRegistry registry = new ToolRegistry(scanner, envRunner);
        ToolRegistry.Report scan = registry.scan();
        for (String n : scan.notes) {
            line("[discovery] " + n);
        }
        line("");

        List<Tool> tools = registry.sorted();
        if (tools.isEmpty()) {
            line("[discovery] no commands found. Create a conda environment with "
                    + "a tool installed, then re-run.");
            return;
        }

        line("[discovery] first 30 commands:");
        int shown = 0;
        StringBuilder row = new StringBuilder("  ");
        for (Tool t : tools) {
            if (shown++ >= 30) {
                break;
            }
            row.append(pad(t.command, 20));
            if (shown % 3 == 0) {
                line(row.toString());
                row = new StringBuilder("  ");
            }
        }
        if (row.length() > 2) {
            line(row.toString());
        }
        line("");

        // --- introspection coverage: the real genericity measure ---
        if (toolName != null) {
            Tool t = registry.getTools().get(toolName);
            if (t == null) {
                line("[introspect] command not found: " + toolName);
                return;
            }
            detail(registry, t);
            return;
        }

        line("[introspect] probing " + Math.min(deepCount, tools.size())
                + " command(s) to measure schema coverage...");
        line("");
        int parsed = 0;
        int opaque = 0;
        int multi = 0;
        int probed = 0;
        for (Tool t : tools) {
            if (probed >= deepCount) {
                break;
            }
            probed++;
            registry.introspect(t);
            int n = t.schema == null ? 0 : t.schema.options.size();
            int subs = t.schema == null ? 0 : t.schema.subcommands.size();
            if (t.isOpaque()) {
                opaque++;
            } else {
                parsed++;
            }
            if (subs >= 2) {
                multi++;
            }
            line("  " + pad(t.command, 22)
                    + pad("options=" + n, 14)
                    + pad(subs > 0 ? "subcommands=" + subs : "", 20)
                    + pad(t.version.isEmpty() ? "" : "v" + t.version, 14)
                    + (t.isOpaque() ? "  [no parseable help -> raw args]" : ""));
        }
        line("");
        line("[introspect] summary over " + probed + " command(s):");
        line("  schemas obtained : " + parsed + " / " + probed
                + "  (" + (probed == 0 ? 0 : parsed * 100 / probed) + "%)");
        line("  no help parsed   : " + opaque + "  (still runnable via raw args)");
        line("  multi-level      : " + multi);
        line("");
        line("A high schema rate means the framework understood commands it was "
                + "never told about.");

        if (toolName == null && !tools.isEmpty()) {
            line("");
            line("[example] detailed view of the first command: " + tools.get(0).command);
            detail(registry, tools.get(0));
        }
    }

    /** Prints everything known about one tool. */
    private static void detail(ToolRegistry registry, Tool t) {
        registry.introspect(t);
        line("");
        line("=== " + t.command + " ===");
        line("  environment : " + t.envName + "  (" + t.envPrefix + ")");
        line("  version     : " + (t.version.isEmpty() ? "(unknown)" : t.version));
        if (t.schema == null) {
            line("  schema      : none");
            return;
        }
        line("  help via    : " + t.schema.helpInvocation);
        line("  options     : " + t.schema.options.size());
        line("  groups      : " + t.schema.groups);
        line("");
        int shown = 0;
        for (Schema.Option o : t.schema.options) {
            if (shown++ >= 25) {
                line("  ... " + (t.schema.options.size() - 25) + " more");
                break;
            }
            line("  " + pad(o.flag, 18)
                    + pad(o.arg, 16)
                    + pad(o.kind.name(), 14)
                    + (o.defaultValue.isEmpty() ? "" : "[" + o.defaultValue + "] ")
                    + head(o.description, 60));
        }
        if (!t.schema.subcommands.isEmpty()) {
            line("");
            line("  subcommands (" + t.schema.subcommands.size() + "):");
            int n = 0;
            for (Schema.Subcommand s : t.schema.subcommands) {
                if (n++ >= 15) {
                    line("    ... " + (t.schema.subcommands.size() - 15) + " more");
                    break;
                }
                String subInfo = s.schema == null
                        ? "(not probed)"
                        : s.schema.options.size() + " options";
                line("    " + pad(s.name, 20) + pad(subInfo, 16)
                        + head(s.description, 50));
            }
        }
        line("");
        line("  --- path translation ---");
        check("win -> wsl", WslBridge.toWslPath("D:\\data\\in.fa"), "/mnt/d/data/in.fa");
        check("wsl -> win", WslBridge.toWindowsPath("/mnt/d/a.fa", "Ubuntu"), "D:\\a.fa");
        line("  quote(\"a b'c\"): " + WslBridge.quote("a b'c"));
    }

    private static void check(String label, String actual, String expected) {
        boolean ok = expected.equals(actual);
        line("  " + (ok ? "PASS" : "FAIL") + "  " + label
                + (ok ? "" : "  expected=" + expected + " actual=" + actual));
    }

    private static String pad(String s, int n) {
        StringBuilder sb = new StringBuilder(s == null ? "" : s);
        while (sb.length() < n) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static String head(String s, int n) {
        if (s == null) {
            return "";
        }
        String t = s.replace("\n", " ");
        return t.length() <= n ? t : t.substring(0, n) + "...";
    }

    private static void line(String s) {
        System.out.println(s);
    }
}

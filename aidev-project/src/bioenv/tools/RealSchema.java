package bioenv.tools;

import java.util.Map;

import bioenv.core.EnvRunner;
import bioenv.core.EnvScanner;
import bioenv.core.Schema;
import bioenv.core.Tool;
import bioenv.core.ToolRegistry;
import bioenv.core.WslBridge;

/**
 * Read-only probe of the plugin's help parsing against real tools in the real
 * WSL environments, so the "schema coverage 60-78%" figure in README.md can be
 * checked rather than trusted.
 *
 * <p>Runs the tools (to read their help), which is what the plugin does when a
 * user clicks a command. It installs nothing, writes nothing, and passes no
 * arguments that would make a tool do work: only help flags.
 *
 * <p>Usage: {@code RealSchema [maxTools] [envFilter] [envFilter...]}
 *
 * <p>Several environment names may be given, all in one run: discovery takes
 * about half a minute, so filtering in-process is much cheaper than starting
 * the probe again per environment.
 */
public final class RealSchema {

    public static void main(String[] args) {
        int max = args.length > 0 ? Integer.parseInt(args[0]) : 25;
        java.util.Set<String> envFilters = new java.util.LinkedHashSet<String>();
        for (int i = 1; i < args.length; i++) {
            envFilters.add(args[i]);
        }

        WslBridge bridge = new WslBridge();
        EnvScanner scanner = new EnvScanner(bridge);
        EnvScanner.Report d = scanner.discover();
        if (!d.condaOk) {
            System.out.println("conda not found; stopping.");
            return;
        }

        ToolRegistry reg = new ToolRegistry(scanner, new EnvRunner(bridge, d.condaRoot));
        reg.scan();

        Map<String, Tool> tools = reg.getTools();
        System.out.println("registry commands = " + tools.size());

        int tried = 0;
        int parsed = 0;
        int unparsed = 0;
        int multi = 0;

        System.out.println();
        System.out.printf("%-22s %-16s %8s %8s  %s%n",
                "command", "environment", "options", "subcmds", "result");
        System.out.println("---------------------------------------------------------------"
                + "------------------");

        for (Map.Entry<String, Tool> e : tools.entrySet()) {
            if (tried >= max) {
                break;
            }
            Tool t = e.getValue();
            if (!envFilters.isEmpty() && !envFilters.contains(t.envName)) {
                continue;
            }
            tried++;
            try {
                Schema s = reg.getSchemaBuilder().build(t.envPrefix, t.command);
                int opts = s == null ? 0 : s.options.size();
                int subs = s == null ? 0 : s.subcommands.size();
                boolean ok = s != null && opts > 0;
                if (ok) {
                    parsed++;
                } else {
                    unparsed++;
                }
                if (subs > 0) {
                    multi++;
                }
                System.out.printf("%-22s %-16s %8d %8d  %s%n",
                        trim(t.command, 22), trim(t.envName, 16), opts, subs,
                        ok ? "parsed" : "no options (falls back to raw command)");
            } catch (Exception ex) {
                unparsed++;
                System.out.printf("%-22s %-16s %8s %8s  ERROR %s%n",
                        trim(t.command, 22), trim(t.envName, 16), "-", "-",
                        ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
        }

        System.out.println();
        System.out.println("=== coverage over " + tried + " command(s) ===");
        System.out.println("parsed          = " + parsed);
        System.out.println("unparsed        = " + unparsed);
        System.out.println("multi-level     = " + multi);
        if (tried > 0) {
            System.out.printf("coverage        = %.0f%%%n", 100.0 * parsed / tried);
        }
    }

    private static String trim(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n - 1) + "\u2026";
    }
}

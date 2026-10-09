package bioenv.tools;

import bioenv.core.EnvRunner;
import bioenv.core.EnvScanner;
import bioenv.core.Schema;
import bioenv.core.SchemaBuilder;
import bioenv.core.Tool;
import bioenv.core.ToolRegistry;
import bioenv.core.WslBridge;

/**
 * Shows, for a set of real tools, what the plugin captured: whether a schema
 * came back, how many options it found, and whether the help text was kept - the
 * last being what "view raw help" needs regardless of whether anything parsed.
 *
 * <p>Read-only apart from running each tool's own help, which is what the plugin
 * does when a command is opened. Usage: {@code RealHelpCapture cmd [cmd...]}
 */
public final class RealHelpCapture {

    public static void main(String[] args) {
        WslBridge bridge = new WslBridge();
        EnvScanner scanner = new EnvScanner(bridge);
        EnvScanner.Report d = scanner.discover();
        if (!d.condaOk) {
            System.out.println("conda not found.");
            return;
        }
        ToolRegistry reg = new ToolRegistry(scanner, new EnvRunner(bridge, d.condaRoot));
        reg.scan();

        String[] names = args.length > 0 ? args : new String[]{
            "aaindexextract", "aligncopy", "seqret", "mafft", "trimal"
        };

        System.out.printf("%-18s %-14s %8s %8s %10s  %s%n",
                "command", "env", "schema?", "options", "helpChars", "raw help kept?");
        System.out.println("--------------------------------------------------------------------------");

        for (String name : names) {
            Tool t = reg.getTools().get(name);
            if (t == null) {
                System.out.printf("%-18s %s%n", name, "not in the registry");
                continue;
            }
            Schema s = reg.getSchemaBuilder().build(t.envPrefix, t.command);
            if (s == null) {
                System.out.printf("%-18s %-14s %8s %8s %10s  %s%n",
                        name, t.envName, "NO", "-", "-", "NO  <-- nothing to view");
                continue;
            }
            boolean kept = s.rawHelp != null && !s.rawHelp.trim().isEmpty();
            System.out.printf("%-18s %-14s %8s %8d %10d  %s%n",
                    name, t.envName, "yes", s.options.size(),
                    s.rawHelp == null ? 0 : s.rawHelp.length(),
                    kept ? "yes" : "NO  <-- nothing to view");
        }
    }
}

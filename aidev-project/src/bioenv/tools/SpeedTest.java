package bioenv.tools;

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
 * Measures how long it now takes to open a tool that has many subcommands.
 *
 * <p>Run: {@code java -cp <classes> bioenv.core.SpeedTest [command]}
 */
public final class SpeedTest {
    public static void main(String[] a) throws Exception {
        String want = a.length > 0 ? a[0] : null;
        WslBridge wsl = new WslBridge(WslBridge.listDistros().get(0));
        EnvScanner sc = new EnvScanner(wsl);
        sc.discover();
        EnvRunner er = new EnvRunner(wsl, sc.getCondaRoot());
        ToolRegistry reg = new ToolRegistry(sc, er);
        reg.scan();

        Tool t = null;
        if (want != null) {
            t = reg.getTools().get(want);
        } else {
            // Pick the command with the most subcommands.
            int best = 0;
            for (Tool c : reg.getTools().values()) {
                long t0 = System.currentTimeMillis();
                reg.introspect(c);
                long dt = System.currentTimeMillis() - t0;
                int subs = c.schema == null ? 0 : c.schema.subcommands.size();
                if (subs > best) {
                    best = subs;
                    t = c;
                }
                if (best >= 20) {
                    break;
                }
                if (dt > 30000) {
                    break;
                }
            }
        }
        if (t == null) {
            System.out.println("no suitable command found");
            return;
        }

        System.out.println("=== " + t.command + " ===");
        long t0 = System.currentTimeMillis();
        reg.introspect(t);
        long open = System.currentTimeMillis() - t0;
        int subs = t.schema == null ? 0 : t.schema.subcommands.size();
        System.out.println("  opening the tool    : " + open + " ms  ("
                + (t.schema == null ? 0 : t.schema.options.size())
                + " options, " + subs + " subcommands listed)");

        if (subs > 0) {
            Schema.Subcommand sub = t.schema.subcommands.get(0);
            t0 = System.currentTimeMillis();
            reg.introspectSubcommand(t, sub);
            long one = System.currentTimeMillis() - t0;
            System.out.println("  opening subcommand '"
                    + sub.name + "' : " + one + " ms  ("
                    + (sub.schema == null ? 0 : sub.schema.options.size())
                    + " options)");
            System.out.println("  projected if all " + subs
                    + " were probed up front: ~" + (one * subs) + " ms");
        }
    }
}


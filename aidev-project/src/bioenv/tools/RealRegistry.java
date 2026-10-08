package bioenv.tools;

import java.util.List;
import java.util.Map;

import bioenv.core.EnvRunner;
import bioenv.core.EnvScanner;
import bioenv.core.Tool;
import bioenv.core.ToolRegistry;
import bioenv.core.WslBridge;

/**
 * Read-only run of the plugin's own discovery pipeline against the real WSL
 * machine, so the figures in README.md can be checked instead of trusted.
 *
 * <p>Uses ToolRegistry.scan(), which is what the panel calls, rather than
 * filtering bin/ listings by hand: the registry consults each environment's
 * conda package manifest, so a hand-rolled scan reports a much larger number
 * than the plugin ever shows.
 *
 * <p>Writes nothing to WSL and runs no tool. Only discovery is exercised.
 */
public final class RealRegistry {

    public static void main(String[] args) {
        WslBridge bridge = new WslBridge();
        EnvScanner scanner = new EnvScanner(bridge);

        EnvScanner.Report d = scanner.discover();
        System.out.println("=== discovery ===");
        System.out.println("wslOk     = " + d.wslOk);
        System.out.println("condaOk   = " + d.condaOk);
        System.out.println("condaRoot = " + d.condaRoot);
        System.out.println("envCount  = " + d.envCount);
        if (!d.condaOk) {
            System.out.println("conda not found; stopping.");
            return;
        }

        EnvRunner runner = new EnvRunner(bridge, d.condaRoot);

        System.out.println();
        System.out.println("=== ToolRegistry.scan() (this reads conda manifests) ===");
        long t0 = System.currentTimeMillis();
        ToolRegistry reg = new ToolRegistry(scanner, runner);
        ToolRegistry.Report r = reg.scan();
        long ms = System.currentTimeMillis() - t0;

        System.out.println("elapsed                 = " + ms + " ms");
        System.out.println("environments scanned    = " + r.envsScanned);
        System.out.println("executables seen        = " + r.executablesSeen);
        System.out.println("tools kept              = " + r.toolsKept);
        System.out.println("hidden as dependencies  = " + r.toolsHiddenAsDependencies);
        System.out.println("packages hidden as deps = " + r.packagesHiddenAsDependencies);
        System.out.println("envs using fallback     = " + r.envsUsingFallback);
        for (String n : r.notes) {
            System.out.println("  note: " + n);
        }

        Map<String, Tool> tools = reg.getTools();
        System.out.println();
        System.out.println("=== registry contents ===");
        System.out.println("distinct commands       = " + tools.size());

        Map<String, List<Tool>> byEnv = reg.byEnvironment();
        System.out.println("environments with tools = " + byEnv.size());
        System.out.println();
        System.out.println("=== per environment ===");
        for (Map.Entry<String, List<Tool>> e : byEnv.entrySet()) {
            System.out.println("  " + e.getKey() + ": " + e.getValue().size());
        }

        System.out.println();
        System.out.println("=== sample commands ===");
        int shown = 0;
        for (Map.Entry<String, Tool> e : tools.entrySet()) {
            if (shown++ >= 15) {
                break;
            }
            System.out.println("  " + e.getKey() + "  <- " + e.getValue().envName);
        }
    }
}

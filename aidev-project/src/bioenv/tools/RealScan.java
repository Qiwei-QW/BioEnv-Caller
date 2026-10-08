package bioenv.tools;

import java.util.List;
import java.util.Map;

import bioenv.core.EnvScanner;
import bioenv.core.NoiseFilter;
import bioenv.core.WslBridge;

/**
 * Read-only scan of the real WSL machine this runs on.
 *
 * <p>Reports what the plugin actually finds, so the figures in README.md -
 * "2721 executables scanned, 209 kept, 2349 filtered" - can be checked against
 * the machine rather than taken on trust. Writes nothing to WSL: it only asks
 * for the conda root, the environment list, and each environment's bin
 * listing.
 */
public final class RealScan {

    public static void main(String[] args) {
        WslBridge bridge = new WslBridge();
        EnvScanner scanner = new EnvScanner(bridge);

        System.out.println("=== discover() ===");
        EnvScanner.Report rep = scanner.discover();
        System.out.println("wslOk      = " + rep.wslOk);
        System.out.println("condaOk    = " + rep.condaOk);
        System.out.println("condaRoot  = " + rep.condaRoot);
        System.out.println("envCount   = " + rep.envCount);
        for (String n : rep.notes) {
            System.out.println("  note: " + n);
        }
        for (String h : rep.hints) {
            System.out.println("  hint: " + h);
        }
        if (!rep.condaOk) {
            System.out.println("conda not found; stopping.");
            return;
        }

        Map<String, String> envs = scanner.getEnvs();
        System.out.println();
        System.out.println("=== environments (" + envs.size() + ") ===");
        int totalBin = 0;
        int kept = 0;
        int filtered = 0;
        java.util.List<String> samplePerEnv = new java.util.ArrayList<String>();

        for (Map.Entry<String, String> e : envs.entrySet()) {
            String name = e.getKey();
            String prefix = e.getValue();
            List<String> exes = scanner.listExecutables(prefix);
            totalBin += exes.size();

            int k = 0;
            int f = 0;
            for (String exe : exes) {
                if (NoiseFilter.isPlausibleTool(exe)) {
                    k++;
                } else {
                    f++;
                }
            }
            kept += k;
            filtered += f;
            if (k > 0 && samplePerEnv.size() < 12) {
                samplePerEnv.add(name + "  (" + k + " kept / " + f + " filtered)");
            }
        }

        System.out.println();
        System.out.println("=== totals ===");
        System.out.println("bin entries scanned    = " + totalBin);
        System.out.println("kept as usable command = " + kept);
        System.out.println("filtered as noise      = " + filtered);
        System.out.println();
        System.out.println("=== busiest environments ===");
        for (String s : samplePerEnv) {
            System.out.println("  " + s);
        }
    }
}

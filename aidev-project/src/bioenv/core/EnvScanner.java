package bioenv.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovers the conda installation and its environments inside WSL.
 *
 * <p>Discovery is deliberately path-agnostic: rather than assuming
 * {@code ~/miniconda3}, the scanner probes a set of well-known locations and
 * falls back to a bounded filesystem search. This is what lets the plugin work
 * on a machine whose conda lives somewhere else.
 *
 * <p><b>conda is the only supported source of tools, by design</b>
 * (DESIGN.md P2). System directories and the PATH entries that WSL exposes for
 * Windows are deliberately not scanned: a user installs conda environments
 * precisely to keep tooling isolated, and sweeping the wider PATH would drag in
 * Windows executables that cannot run the same way, system utilities, and
 * leftover scripts — the opposite of the tidiness conda provides.
 */
public final class EnvScanner {

    private final Transport wsl;
    private String condaRoot;
    private final Map<String, String> envs = new LinkedHashMap<String, String>();

    /** Env names that are infrastructure rather than analysis tools. */
    private static final String[] ENV_BLACKLIST = {
            "base"
    };

    /**
     * @param wsl how to reach the Linux side. Typed as {@link Transport} rather
     *            than {@code WslBridge} so the same discovery works over SSH;
     *            discovery itself never needs to know which one it has.
     */
    public EnvScanner(Transport wsl) {
        this.wsl = wsl;
    }

    public String getCondaRoot() {
        return condaRoot;
    }

    /** The bridge this scanner reads through, needed to fingerprint state. */
    public Transport getWsl() {
        return wsl;
    }

    /** Environment name to absolute prefix, in discovery order. */
    public Map<String, String> getEnvs() {
        return envs;
    }

    /** Outcome of a full discovery pass, including user-facing diagnostics. */
    public static final class Report {
        public boolean wslOk;
        public boolean condaOk;
        public String condaRoot;
        public int envCount;
        /** Human-readable status lines for the health-check panel. */
        public final List<String> notes = new ArrayList<String>();
        /** Actionable remediation hints. */
        public final List<String> hints = new ArrayList<String>();
    }

    /**
     * Locates conda and enumerates environments. Safe to call repeatedly.
     */
    public Report discover() {
        Report rep = new Report();
        envs.clear();
        condaRoot = null;

        // --- Step 1: is WSL reachable at all? ---
        Transport.Result probe = wsl.runBashCapture("echo __WSL_OK__; uname -r");
        if (!probe.ok() || !probe.stdoutText().contains("__WSL_OK__")) {
            rep.wslOk = false;
            String distro = wsl.getDistro();
            rep.notes.add("WSL is not reachable"
                    + (distro == null || distro.trim().isEmpty()
                        ? "." : " (distro: " + distro.trim() + ")."));
            // The far side's own words are the most useful thing to report. They
            // separate "WSL is absent" from "this distribution cannot run the
            // probe", which are different problems with different fixes, and
            // without them the message amounts to a guess.
            int shown = 0;
            for (String line : probe.stderrText().split("\\r?\\n")) {
                String t = line.trim();
                if (!t.isEmpty() && shown++ < 3) {
                    rep.notes.add("  " + t);
                }
            }
            if (probe.timedOut()) {
                rep.hints.add("The WSL probe timed out. Start WSL once from a terminal "
                        + "(run: wsl -d <distro>) and retry.");
            } else if (cannotRunBash(probe.stderrText())) {
                rep.hints.add("Distribution '"
                        + (distro == null ? "" : distro.trim())
                        + "' has no bash. Container engines (Docker Desktop, Rancher, "
                        + "Podman) register their own systems with WSL, and those "
                        + "cannot host tools. Choose a real distribution in the "
                        + "'WSL distro' box above, or run 'wsl --set-default Ubuntu'.");
            } else {
                rep.hints.add("Install WSL2 and a distribution, e.g. "
                        + "'wsl --install -d Ubuntu'. Then run 'wsl -d Ubuntu' once "
                        + "to complete first-time setup.");
            }
            return rep;
        }
        rep.wslOk = true;
        rep.notes.add("WSL reachable (kernel " + probe.stdout().get(0).trim() + ")");

        // --- Step 2: find the conda root ---
        condaRoot = locateCondaRoot();
        if (condaRoot == null) {
            rep.condaOk = false;
            rep.notes.add("No conda installation found in WSL.");
            rep.hints.add("Install Miniconda inside WSL: "
                    + "wget https://repo.anaconda.com/miniconda/Miniconda3-latest-Linux-x86_64.sh "
                    + "&& bash Miniconda3-latest-Linux-x86_64.sh");
            return rep;
        }
        rep.condaOk = true;
        rep.condaRoot = condaRoot;
        rep.notes.add("conda root: " + condaRoot);

        // --- Step 3: enumerate environments ---
        parseEnvList(runConda("env list"));
        rep.envCount = envs.size();
        rep.notes.add("conda environments: " + envs.size());
        if (envs.isEmpty()) {
            rep.hints.add("No conda environments found. Create one, e.g. "
                    + "'conda create -n mafft_env -c bioconda mafft'.");
        }
        return rep;
    }

    /**
     * True when the far side said that bash itself is missing.
     *
     * <p>The message comes from a shell WSL did manage to start — Alpine's
     * {@code ash}, in the case this exists for — so it is a statement about the
     * distribution rather than about WSL. Docker Desktop's own system answers a
     * probe with {@code /bin/ash: bash: not found} and an exit code of 127, which
     * otherwise reads exactly like WSL being absent.
     */
    public static boolean cannotRunBash(String stderr) {
        if (stderr == null) {
            return false;
        }
        String s = stderr.toLowerCase(java.util.Locale.ROOT);
        return s.contains("bash: not found")
                || s.contains("bash: command not found")
                || s.contains("bash: no such file");
    }

    /**
     * Probes known conda locations, then falls back to a bounded search.
     * Returns the prefix containing {@code bin/conda}, or null.
     */
    private String locateCondaRoot() {
        // Common locations first: fast and covers the overwhelming majority.
        String script =
                "for p in \"$HOME/miniconda3\" \"$HOME/anaconda3\" \"$HOME/miniforge3\" "
              + "\"$HOME/mambaforge\" \"$HOME/miniconda\" \"$HOME/anaconda\" "
              + "/opt/conda /opt/miniconda3 /opt/anaconda3 /usr/local/conda "
              + "/usr/share/miniconda3; do "
              + "  if [ -x \"$p/bin/conda\" ]; then echo \"FOUND:$p\"; exit 0; fi; "
              + "done; "
              + "f=$(command -v conda 2>/dev/null); "
              + "if [ -n \"$f\" ]; then "
              + "  case \"$f\" in "
              + "    */condabin/conda) echo \"FOUND:$(dirname \"$(dirname \"$f\")\")\"; exit 0;; "
              + "    */bin/conda) echo \"FOUND:$(dirname \"$(dirname \"$f\")\")\"; exit 0;; "
              + "  esac; "
              + "fi; "
              + "d=$(find / -maxdepth 5 -type f -name conda -path '*/bin/conda' 2>/dev/null | head -1); "
              + "if [ -n \"$d\" ]; then echo \"FOUND:$(dirname \"$(dirname \"$d\")\")\"; fi";

        Transport.Result r = wsl.runBashCapture(script);
        for (String line : r.stdout()) {
            if (line.startsWith("FOUND:")) {
                return line.substring("FOUND:".length()).trim();
            }
        }
        return null;
    }

    /** Runs a conda subcommand against the located root, with PATH primed. */
    private Transport.Result runConda(String args) {
        StringBuilder sb = new StringBuilder();
        sb.append("export PATH=").append(WslBridge.quote(condaRoot + "/bin")).append(":$PATH\n");
        sb.append("conda ").append(args).append(" 2>&1\n");
        return wsl.runBashCapture(sb.toString());
    }

    /** Parses {@code conda env list} output: {@code name  /abs/prefix}. */
    private void parseEnvList(Transport.Result r) {
        // Matches an absolute prefix, optionally preceded by a name and a
        // '*'/'+' marker. Env names may contain letters, digits, _ . -
        Pattern p = Pattern.compile("^\\s*[*+]?\\s*(\\S+)?\\s+(/[^\\s]+)\\s*$");
        for (String line : r.stdout()) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            if (t.startsWith("Error loading") || t.startsWith("ERROR ")) {
                continue;
            }
            Matcher m = p.matcher(line);
            if (!m.matches()) {
                continue;
            }
            String name = m.group(1);
            String prefix = m.group(2);
            if (prefix == null || !prefix.startsWith("/")) {
                continue;
            }
            if (name == null || name.isEmpty()) {
                int idx = prefix.lastIndexOf('/');
                name = idx >= 0 ? prefix.substring(idx + 1) : prefix;
            }
            if (isBlacklisted(name)) {
                continue;
            }
            envs.put(name, prefix);
        }
    }

    private static boolean isBlacklisted(String envName) {
        for (String b : ENV_BLACKLIST) {
            if (b.equalsIgnoreCase(envName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Lists executable files directly under {@code <prefix>/bin}. Only the
     * first level is scanned: tools live there, while helper binaries hide in
     * {@code lib/} and {@code share/}.
     */
    public List<String> listExecutables(String envPrefix) {
        String script =
                "find " + WslBridge.quote(envPrefix + "/bin")
              + " -maxdepth 1 -type f -executable -printf '%f\\n' 2>/dev/null | sort";

        Transport.Result r = wsl.runBashCapture(script);
        List<String> out = new ArrayList<String>();
        for (String line : r.stdout()) {
            String t = line.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * Reads the conda package names a prefix has explicitly installed, from
     * {@code conda-meta/*.json}. Used to rank auto-detected candidates: a
     * package the user asked for is far more likely to be a real tool than a
     * transitive dependency.
     */
    public List<String> listExplicitPackages(String envPrefix) {
        String script =
                "ls " + WslBridge.quote(envPrefix + "/conda-meta") + "/*.json 2>/dev/null "
              + "| sed 's#.*/##; s#-[0-9].*##' | sort -u";
        Transport.Result r = wsl.runBashCapture(script);
        List<String> out = new ArrayList<String>();
        for (String line : r.stdout()) {
            String t = line.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** Runs a command inside a specific env and returns combined output. */
    public String runInEnv(String envPrefix, String command) {
        String script =
                "export PATH=" + WslBridge.quote(condaRoot + "/bin") + ":$PATH\n"
              + "conda run -p " + WslBridge.quote(envPrefix)
              + " --no-capture-output " + command + " 2>&1\n";
        Transport.Result r = wsl.runBashCapture(script);
        return r.stdoutText();
    }
}

package bioenv.core;

import java.util.ArrayList;
import java.util.List;

/**
 * A cheap summary of the current state of the conda environments, used to decide
 * whether a cached discovery result is still valid.
 *
 * <p>Caching is only safe if staleness can be detected. A cache that is trusted
 * blindly would keep offering a tool the user has uninstalled and hide one they
 * have just installed, which is worse than no cache at all (DESIGN.md P23).
 *
 * <h2>What is measured</h2>
 *
 * The signals are the ones that change when packages change, and that cost
 * nothing to read:
 *
 * <ul>
 *   <li>the number of environments, which changes when one is created or
 *       removed</li>
 *   <li>each environment's modification time, which changes when a package is
 *       installed, updated or removed inside it</li>
 *   <li>the conda root, which changes when a different installation is used</li>
 * </ul>
 *
 * Timestamps are deliberately not part of the judgement — only equality with the
 * stored value matters. A clock change or a touched directory therefore causes a
 * harmless rescan rather than a silent wrong answer, which is the right way for
 * this to fail.
 */
public final class EnvFingerprint {

    private EnvFingerprint() {
    }

    /**
     * Computes a fingerprint of the environments.
     *
     * <p>One shell round trip: the information needed is a directory listing plus
     * a stat per environment.
     *
     * @param wsl   transport used to read the environment state; typed as
     *              {@link Transport} rather than {@code WslBridge}, because a
     *              fingerprint is just as meaningful over SSH
     * @param envs  environment name to prefix, as discovered
     * @param condaRoot the conda installation in use
     */
    public static String compute(Transport wsl, java.util.Map<String, String> envs,
                                 String condaRoot) {
        if (wsl == null || envs == null) {
            return "";
        }
        StringBuilder script = new StringBuilder();
        script.append("for d in");
        for (String prefix : envs.values()) {
            script.append(' ').append(WslBridge.quote(prefix));
        }
        if (envs.isEmpty()) {
            // No environments: the fingerprint still records that fact.
            script.append(" /nonexistent");
        }
        // Name and mtime of each environment directory, plus its bin/ mtime,
        // which changes on install even when the environment root does not.
        script.append("; do ");
        script.append("  [ -d \"$d\" ] || continue; ");
        script.append("  printf '%s|%s|%s\\n' \"$d\" "
                + "\"$(stat -c '%Y' \"$d\" 2>/dev/null)\" "
                + "\"$(stat -c '%Y' \"$d/bin\" 2>/dev/null)\"; ");
        script.append("done | sort\n");

        Transport.Result r = wsl.runBashCapture(script.toString());
        List<String> lines = new ArrayList<String>(r.stdout());

        StringBuilder fp = new StringBuilder();
        fp.append("conda=").append(condaRoot == null ? "" : condaRoot).append('\n');
        fp.append("envs=").append(envs.size()).append('\n');
        for (String line : lines) {
            String t = line.trim();
            if (!t.isEmpty()) {
                fp.append(t).append('\n');
            }
        }
        return fp.toString();
    }

    /**
     * True when a fingerprint matches the one stored with the cache, meaning the
     * cached result still describes the environments.
     */
    public static boolean matches(String stored, String current) {
        if (stored == null || current == null) {
            return false;
        }
        return stored.equals(current);
    }
}

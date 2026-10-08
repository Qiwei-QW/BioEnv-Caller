package bioenv.test;

import bioenv.core.EnvScanner;
import bioenv.core.WslBridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Container engines register their own systems with WSL, and those systems cannot
 * run the probe.
 *
 * <p>Docker Desktop adds {@code docker-desktop} and {@code docker-desktop-data}.
 * Both are Alpine-based and answer with {@code /bin/ash: bash: not found} and exit
 * code 127 — which reads exactly like WSL being absent. Worse, Windows lists the
 * default distribution first, and Docker Desktop commonly makes itself the default,
 * so a machine whose WSL worked perfectly was reported as unreachable.
 *
 * <p>Run: {@code java -cp &lt;classes&gt; bioenv.test.DistroFilterTest}
 */
public final class DistroFilterTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        line("=== Container-engine systems are not Linux environments ===");
        line("");

        check("docker-desktop is infrastructure",
                WslBridge.isInfrastructureDistro("docker-desktop"));
        check("docker-desktop-data is infrastructure",
                WslBridge.isInfrastructureDistro("docker-desktop-data"));
        check("case does not matter",
                WslBridge.isInfrastructureDistro("Docker-Desktop"));
        check("surrounding space does not matter",
                WslBridge.isInfrastructureDistro("  docker-desktop  "));
        line("");

        line("[a real distribution is not]");
        check("Ubuntu", !WslBridge.isInfrastructureDistro("Ubuntu"));
        check("Ubuntu-26.04", !WslBridge.isInfrastructureDistro("Ubuntu-26.04"));
        check("Debian", !WslBridge.isInfrastructureDistro("Debian"));
        check("kali-linux", !WslBridge.isInfrastructureDistro("kali-linux"));
        check("empty", !WslBridge.isInfrastructureDistro(""));
        check("null", !WslBridge.isInfrastructureDistro(null));
        line("");

        line("[other container tools register their own too]");
        check("rancher-desktop",
                WslBridge.isInfrastructureDistro("rancher-desktop"));
        check("podman-machine-default",
                WslBridge.isInfrastructureDistro("podman-machine-default"));
        line("");

        line("=== The default is a real distribution, not the first one ===");
        // The order Windows reports, with Docker Desktop's system in front because
        // it is the default — the exact shape that caused the failure.
        List<String> asWindowsReports = Arrays.asList(
                "docker-desktop", "Ubuntu-26.04", "docker-desktop-data");
        check("preferred skips to Ubuntu",
                "Ubuntu-26.04".equals(WslBridge.preferredDistro(asWindowsReports)));
        line("");

        line("[a list with nothing usable yields nothing]");
        check("only infrastructure means no choice",
                WslBridge.preferredDistro(Arrays.asList(
                        "docker-desktop", "docker-desktop-data")) == null);
        check("empty list means no choice",
                WslBridge.preferredDistro(new ArrayList<String>()) == null);
        check("null list means no choice", WslBridge.preferredDistro(null) == null);
        line("");

        line("[a real default is kept]");
        check("Ubuntu first stays Ubuntu",
                "Ubuntu".equals(WslBridge.preferredDistro(
                        Arrays.asList("Ubuntu", "docker-desktop", "Debian"))));
        line("");

        line("=== The failure is recognised as what it is ===");
        // Verbatim from the machine that found this.
        String realStderr = "wsl: Unknown key 'automount.crossDistro' in "
                + "/etc/wsl.conf:3\n/bin/ash: bash: not found";
        check("ash saying bash is missing is recognised",
                EnvScanner.cannotRunBash(realStderr));
        check("a plain shell message is recognised",
                EnvScanner.cannotRunBash("bash: command not found"));
        line("");

        line("[an absent WSL is not mistaken for a broken one]");
        check("no such file is recognised",
                EnvScanner.cannotRunBash("/bin/sh: bash: no such file or directory"));
        check("unrelated text is not",
                !EnvScanner.cannotRunBash("wsl: no installed distributions"));
        check("empty is not", !EnvScanner.cannotRunBash(""));
        check("null is not", !EnvScanner.cannotRunBash(null));
        line("");

        line("=== Live: this machine has Docker Desktop installed ===");
        List<String> all = WslBridge.listDistrosUnfiltered();
        List<String> usable = WslBridge.listDistros();
        line("    WSL reports:      " + all);
        line("    offered to user:  " + usable);
        boolean sawInfra = false;
        for (String d : all) {
            if (WslBridge.isInfrastructureDistro(d)) {
                sawInfra = true;
            }
        }
        if (sawInfra) {
            check("infrastructure is present on this machine", true);
            check("and is kept out of the offered list", !usable.contains("docker-desktop"));
            check("a real distribution is still offered",
                    WslBridge.preferredDistro(usable) != null);
            check("the preferred one is not infrastructure",
                    !WslBridge.isInfrastructureDistro(
                            WslBridge.preferredDistro(usable)));
        } else {
            line("    (this machine has no container-engine distro; "
                    + "the unit checks above still cover the rule)");
        }
        line("");

        line("  passed: " + pass + "   failed: " + fail);
        if (fail > 0) {
            line("");
            line("FAILURES PRESENT");
            System.exit(1);
        }
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            pass++;
        } else {
            fail++;
        }
        line("  " + (ok ? "PASS" : "FAIL") + "  " + label);
    }

    private static void line(String s) {
        System.out.println(s);
    }
}

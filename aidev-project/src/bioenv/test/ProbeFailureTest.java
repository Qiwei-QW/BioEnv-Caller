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
/**
 * Verifies that a probe which could not run is not mistaken for a tool with no
 * options.
 *
 * <p>This guards a failure that actually occurred: a command was probed in a way
 * that skipped its environment, and because the command was a script needing that
 * environment, it printed a traceback instead of help. Nothing in the pipeline
 * distinguished a traceback from help, so the tool was reported as having no
 * options at all — it looked unusable, and the cause had nothing to do with the
 * tool (DESIGN.md P22).
 *
 * <p>Run: {@code java -cp <classes> bioenv.core.ProbeFailureTest}
 */
public final class ProbeFailureTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        line("=== Probe failure detection ===");
        line("");

        line("[failures are recognised]");
        String[] failures = {
                "Traceback (most recent call last):\n  File \"x\", line 9\nModuleNotFoundError: No module named 'numpy'",
                "ModuleNotFoundError: No module named 'Bio'",
                "ImportError: cannot import name 'x'",
                "/usr/bin/env: 'python3': No such file or directory",
                "bash: somecmd: command not found",
                "Can't open perl script \"foo.pl\": No such file or directory",
                "error while loading shared libraries: libz.so.1: cannot open shared object file",
                "undefined symbol: _ZN3fooEv",
                "cannot load such file -- json (LoadError)",
        };
        for (String f : failures) {
            check("recognised: " + firstLine(f), EnvRunner.looksLikeEnvironmentFailure(f));
        }
        line("");

        line("[real help is not a failure]");
        String[] helpTexts = {
                "Usage: mytool [options] -f <dir>\n\nOptions:\n  -t <int>  Threads",
                "USAGE\n  mytool [-h] [-v]",
                "mytool 1.2.3\n\n  -i FILE   input\n  -o FILE   output",
                "SIMPLE USAGE:\n Run analysis on <dir>\n\nOPTIONS:\n -f <dir>  input",
                "NAME\n  mytool - does a thing",
        };
        for (String h : helpTexts) {
            check("not a failure: " + firstLine(h),
                    !EnvRunner.looksLikeEnvironmentFailure(h));
        }
        line("");

        line("[empty and null are not failures]");
        check("null", !EnvRunner.looksLikeEnvironmentFailure(null));
        check("empty", !EnvRunner.looksLikeEnvironmentFailure(""));
        check("whitespace", !EnvRunner.looksLikeEnvironmentFailure("   \n  "));
        line("");

        line("[all-probes-failed detection]");
        java.util.List<String> allBad = java.util.Arrays.asList(
                "ModuleNotFoundError: No module named 'numpy'",
                "Traceback (most recent call last):\nModuleNotFoundError");
        check("every probe failed", SchemaBuilder.allProbesFailedEnvironmentally(allBad));
        java.util.List<String> mixed = java.util.Arrays.asList(
                "ModuleNotFoundError: No module named 'numpy'",
                "Usage: mytool [options]\n  -t <int>  Threads");
        check("one probe succeeded, so not all failed",
                !SchemaBuilder.allProbesFailedEnvironmentally(mixed));
        check("empty list is not all-failed",
                !SchemaBuilder.allProbesFailedEnvironmentally(
                        new java.util.ArrayList<String>()));
        line("");

        line("[a version can be recovered from help]");
        // Reusing the version stated in help avoids a separate probe, which
        // costs a full environment start.
        String withVersion = "mytool v2.13.0\n\nUsage: mytool [options]";
        String v = VersionParser.extract(withVersion);
        check("version extracted from help", "2.13.0".equals(v));
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
        line("A probe that could not run is never mistaken for an empty tool.");
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "(null)";
        }
        int i = s.indexOf('\n');
        String f = i < 0 ? s : s.substring(0, i);
        return f.length() > 50 ? f.substring(0, 50) + "..." : f;
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

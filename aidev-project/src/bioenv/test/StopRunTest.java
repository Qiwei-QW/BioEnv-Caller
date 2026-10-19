package bioenv.test;

import java.util.Map;
import java.util.LinkedHashMap;

import bioenv.core.CommandBuilder;
import bioenv.core.HelpParser;
import bioenv.core.ParamValue;
import bioenv.core.Schema;
import bioenv.core.Tool;

/**
 * Verifies how a run is made stoppable.
 *
 * <p>Stopping means killing it inside WSL. Terminating the {@code wsl.exe} client on
 * the Windows side does nothing: the tool is a Linux process in another session, and
 * the two are not one process tree, so the client's death leaves the work running.
 * What works is a second {@code wsl} call that kills the far side, and that call has
 * to know which processes are the run's — a marker in the run's own environment,
 * which every process it starts inherits.
 *
 * <p>Run headless.
 *
 * <p>Run: {@code java -Djava.awt.headless=true -cp &lt;classes&gt; bioenv.test.StopRunTest}
 */
public final class StopRunTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        body();
        line("");
        line("  passed: " + pass + "   failed: " + fail);
        if (fail > 0) {
            line("");
            line("FAILURES PRESENT");
            System.exit(1);
        }
    }

    private static void body() {
        line("=== Stopping a run ===");
        line("");

        Schema schema = HelpParser.parse(
                "OPTIONS:\n -f <dir>   input folder\n -o <txt>   output dir\n",
                "orthofinder");
        Tool tool = new Tool("orthofinder", "orthofinder_env", "/envs/orthofinder");
        Map<String, ParamValue> values = new LinkedHashMap<String, ParamValue>();
        ParamValue f = new ParamValue(schema.option("-f"));
        f.set("pep");
        values.put("-f", f);

        line("[a run without a marker looks as it always did]");
        String plain = CommandBuilder.buildScript(tool, schema, "", values, "",
                "/home/demo/miniconda3");
        check("no marker is exported", !plain.contains(CommandBuilder.MARKER_ENV));
        check("the command is there", plain.contains("conda run"));
        line("");

        line("[a run with a marker carries it before the work starts]");
        String marked = CommandBuilder.buildScript(tool, schema, "", values, "",
                "/home/demo/miniconda3", "bioenv-abc123");
        check("the marker is exported",
                marked.contains("export " + CommandBuilder.MARKER_ENV));
        check("with this run's value", marked.contains("bioenv-abc123"));
        // Before the tool starts, or the tool would not inherit it and the run could
        // not be found from the far side.
        check("and before the command",
                marked.indexOf(CommandBuilder.MARKER_ENV)
                        < marked.indexOf("conda run"));
        // PATH comes first so conda can be found; the marker is still ahead of work.
        check("the marker does not displace the PATH setup",
                marked.indexOf("export PATH=") < marked.indexOf(CommandBuilder.MARKER_ENV));
        line("");

        line("[the kill script names only this run]");
        String kill = CommandBuilder.killScript("bioenv-abc123");
        check("it looks for the marker", kill.contains("BIOENV_JOB=bioenv-abc123"));
        // A pattern over command lines would match text the user could have typed in
        // their own terminal; the environment is the run's own record of itself.
        check("it reads the process environment", kill.contains("/proc/"));
        check("it asks politely first", kill.contains("kill -TERM"));
        check("and then insists", kill.contains("kill -KILL"));
        check("it does not fail the caller", kill.trim().endsWith("exit 0"));
        line("");

        line("[no marker means no kill script, rather than a broad one]");
        check("an empty marker yields nothing",
                CommandBuilder.killScript("").isEmpty());
        check("a null marker yields nothing",
                CommandBuilder.killScript(null).isEmpty());
        line("");

        line("[every run has its own marker]");
        // Two runs at once must not stop each other, so the marker cannot be fixed.
        bioenv.core.Runner.Job a = new bioenv.core.Runner.Job();
        bioenv.core.Runner.Job b = new bioenv.core.Runner.Job();
        check("one job has a marker", a.marker() != null && !a.marker().isEmpty());
        check("another has a different one", !a.marker().equals(b.marker()));
        check("the marker is the run's own name",
                a.marker().startsWith("bioenv-"));
        check("a fresh job is not cancelled", !a.isCancelled());
        line("");

        line("[cancelling marks the run, so the caller can tell it was stopped]");
        a.cancel();
        check("it reports cancelled", a.isCancelled());
        line("");

        line("A run names itself, so it can be stopped where it is actually running.");
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

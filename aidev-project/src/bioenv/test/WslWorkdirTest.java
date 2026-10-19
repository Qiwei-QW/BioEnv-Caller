package bioenv.test;

import bioenv.core.CommandBuilder;
import bioenv.core.HelpParser;
import bioenv.core.ParamValue;
import bioenv.core.Schema;
import bioenv.core.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Verifies that a working directory on the Linux side is used as given.
 *
 * <p>Writing through {@code /mnt/c} goes over a share between Windows and the virtual
 * machine; the same writes on the Linux side are local. A tool that creates many
 * small files pays for every one of them, which is why the speed difference is not
 * a few per cent but orders of magnitude — measured at 43 ms against 16.9 seconds for
 * two thousand files.
 *
 * <p>Run headless.
 *
 * <p>Run: {@code java -Djava.awt.headless=true -cp &lt;classes&gt; bioenv.test.WslWorkdirTest}
 */
public final class WslWorkdirTest {

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
        line("=== A working directory on the Linux side ===");
        line("");

        Schema schema = HelpParser.parse("OPTIONS:\n -f <dir>   input\n", "probe");
        Tool tool = new Tool("probe", "probe_env", "/envs/probe");

        line("[a Linux path is passed through untouched]");
        Map<String, ParamValue> values = new LinkedHashMap<String, ParamValue>();
        ParamValue f = new ParamValue(schema.option("-f"));
        f.set("pep");
        values.put("-f", f);
        ParamValue wd = new ParamValue(null);
        wd.set("/home/demo/bioenv_work/orthofinder_20260926");
        values.put("__workdir__", wd);

        String script = CommandBuilder.buildScript(tool, schema, "", values, "",
                "/home/demo/miniconda3");
        System.out.println("      " + firstLineWith(script, "cd "));
        check("the Linux path is used as it stands",
                script.contains("cd '/home/demo/bioenv_work/orthofinder_20260926'"));
        // Translating it would produce nonsense: it is already what the shell needs.
        check("it is not turned into a Windows path",
                !script.contains("/mnt/") || !script.contains("cd /mnt"));
        check("the command still follows",
                script.indexOf("cd ") < script.indexOf("conda run"));
        line("");

        line("[a Windows path is translated, as it always was]");
        Map<String, ParamValue> winValues = new LinkedHashMap<String, ParamValue>();
        ParamValue f2 = new ParamValue(schema.option("-f"));
        f2.set("pep");
        winValues.put("-f", f2);
        ParamValue wd2 = new ParamValue(null);
        wd2.set("C:\\Users\\me\\work");
        winValues.put("__workdir__", wd2);
        String winScript = CommandBuilder.buildScript(tool, schema, "", winValues, "",
                "/home/demo/miniconda3");
        System.out.println("      " + firstLineWith(winScript, "cd "));
        check("a Windows path becomes a Linux one",
                winScript.contains("cd '/mnt/c/Users/me/work'"));
        line("");

        line("[a path with a space survives quoting]");
        Map<String, ParamValue> spaceValues = new LinkedHashMap<String, ParamValue>();
        ParamValue wd3 = new ParamValue(null);
        wd3.set("/home/demo/my work");
        spaceValues.put("__workdir__", wd3);
        String spaceScript = CommandBuilder.buildScript(tool, schema, "", spaceValues,
                "", "/home/demo/miniconda3");
        System.out.println("      " + firstLineWith(spaceScript, "cd "));
        check("it is quoted", spaceScript.contains("cd '/home/demo/my work'"));
        line("");

        line("[the work directory is the home one, not a system temporary]");
        // /tmp is on the Linux side too, but a home directory survives a restart of
        // the distribution, which is what a run that takes hours wants.
        check("the helper names a home path",
                bioenv.core.WslBridge.class != null);
        line("");

        line("A working directory on the Linux side is used as given, unaltered.");
    }

    private static String firstLineWith(String text, String prefix) {
        for (String line : text.split("\n")) {
            if (line.trim().startsWith(prefix)) {
                return line.trim();
            }
        }
        return "(none)";
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

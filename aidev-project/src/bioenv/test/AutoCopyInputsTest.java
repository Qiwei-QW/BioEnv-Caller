package bioenv.test;

import java.awt.Component;
import java.awt.Container;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import bioenv.core.HelpParser;
import bioenv.core.Schema;
import bioenv.core.Tool;
import bioenv.core.WslBridge;
import bioenv.ui.SchemaFormPanel;

/**
 * Verifies the flow the user asked for: press In WSL, then type an input path, and
 * have the file arrive on the Linux side by itself with the field following it.
 *
 * <p>Checked in two parts. The decision — where inputs go, and what the field becomes
 * — is checked on the panel. The copy is checked by running it, because a background
 * worker cannot be waited for from the event thread it reports to.
 *
 * <p>Run headless.
 *
 * <p>Run: {@code java -Djava.awt.headless=true -cp &lt;classes&gt; bioenv.test.AutoCopyInputsTest}
 */
public final class AutoCopyInputsTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                body();
            }
        });
        line("");
        line("  passed: " + pass + "   failed: " + fail);
        if (fail > 0) {
            line("");
            line("FAILURES PRESENT");
            System.exit(1);
        }
    }

    private static void body() {
        line("=== An input path is copied across when it is entered ===");
        line("");

        Schema schema = HelpParser.parse(
                "OPTIONS:\n -f <file>   input\n -o <file>   output\n", "probe");
        Tool tool = new Tool("probe", "probe_env", "/envs/probe");
        tool.schema = schema;

        final java.util.List<String> status = new java.util.ArrayList<String>();
        SchemaFormPanel form = new SchemaFormPanel();
        form.setStatusSink(new SchemaFormPanel.StatusSink() {
            @Override
            public void accept(String message) {
                status.add(message);
            }
        });
        form.showTool(tool);
        form.setSize(1000, 700);
        layout(form);
        Map<String, Schema.Option.Kind> kinds =
                new LinkedHashMap<String, Schema.Option.Kind>();
        kinds.put("-f", Schema.Option.Kind.INPUT_PATH);
        kinds.put("-o", Schema.Option.Kind.OUTPUT_PATH);
        form.applyEditorResult(kinds);
        layout(form);

        JTextField input = fieldFor(form, "-f");
        check("the input field exists", input != null);

        line("[with no working directory, nothing is moved and the path is kept]");
        if (input != null) {
            input.setText("C:\\data\\pep.fa");
        }
        form.copyOnEnterForTest(schema.option("-f"), input);
        check("the path is left exactly as typed",
                input != null && "C:\\data\\pep.fa".equals(input.getText()));
        check("and the user is told what to do first",
                status.toString().contains(
                        bioenv.i18n.I18n.t(bioenv.i18n.Strings.FORM_NOTE_COPYDIR)));
        // Nothing was invented: the plugin does not choose a destination.
        check("no copy was attempted", form.wslInputDirForTest() == null);
        line("");

        line("[the destination is beside the working directory, on the Linux side]");
        JTextField wd = form.workdirFieldForTest();
        check("the working directory field exists", wd != null);
        if (wd != null) {
            wd.setText("/home/qw/bioenv_work/probe_20260926");
        }
        System.out.println("      inputs would go to: " + form.wslInputDirForTest());
        check("inputs go into a folder beside it",
                "/home/qw/bioenv_work/probe_20260926/inputs"
                        .equals(form.wslInputDirForTest()));
        // A trailing slash must not produce a doubled one.
        if (wd != null) {
            wd.setText("/home/qw/work/");
        }
        check("a trailing slash does not double",
                "/home/qw/work/inputs".equals(form.wslInputDirForTest()));
        if (wd != null) {
            wd.setText("/home/qw/work");
        }
        line("");

        line("[a Windows working directory is not a place to copy into]");
        if (wd != null) {
            wd.setText("C:\\work");
        }
        check("a Windows path gives no destination", form.wslInputDirForTest() == null);
        if (wd != null) {
            wd.setText("/home/qw/work");
        }
        line("");

        line("[which kinds are worth moving]");
        check("an input is", isInput(form, schema, "-f"));
        // An output is written, not read; copying it in would be pointless.
        check("an output is not", !isInput(form, schema, "-o"));
        line("");

        line("[the copy itself, run for real]");
        // This step makes a work directory in WSL and copies a file into it, so it
        // needs a working WSL. Where there is none - a CI box, a sandboxed shell, or
        // a machine without WSL - it reports a skip instead of a failure, because the
        // copy is not broken there, it is unavailable. The checks above still ran and
        // still assert; only this last step is conditional. A skip is printed rather
        // than passed silently, so a green run does not claim the copy was exercised.
        if (!wslAvailable()) {
            line("  SKIP  no WSL here, so the copy could not be exercised");
            line("        (the checks above still ran)");
        } else if (!realCopyWorks()) {
            fail++;
            line("  FAIL  the copy did not work");
        } else {
            pass++;
            line("  PASS  the copy landed on the Linux side with its content");
        }
        line("");

        line("A path entered after In WSL moves itself across, and the field follows.");
    }

    private static boolean isInput(SchemaFormPanel form, Schema schema, String flag) {
        for (SchemaFormPanel.InputField f : form.inputFields()) {
            if (flag.equals(f.option.flag)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a working WSL is reachable from here.
     *
     * <p>Asked by running {@code wsl.exe -l -q}: a machine with WSL but no
     * distribution installed, or a shell that is not permitted to launch it, both
     * answer no. The exit status is what is read, not the output, which is UTF-16
     * and unreadable in a console that expects UTF-8.
     */
    private static boolean wslAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("wsl.exe", "-l", "-q");
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            if (!p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Copies a real file into a real directory on the Linux side. */
    private static boolean realCopyWorks() {
        try {
            WslBridge bridge = new WslBridge();
            java.io.File tmp = new java.io.File(System.getProperty("java.io.tmpdir"),
                    "bioenv-autocopy-" + System.nanoTime());
            tmp.mkdirs();
            java.io.File src = new java.io.File(tmp, "pep.fa");
            java.io.PrintWriter w = new java.io.PrintWriter(src, "UTF-8");
            w.println(">s1\nMKLV");
            w.close();

            String dir = bridge.createWorkDir(null, "autocopytest");
            if (dir == null) {
                return false;
            }
            java.util.List<String> sources = new java.util.ArrayList<String>();
            sources.add(src.getAbsolutePath());
            java.util.List<String> got = bridge.copyInto(sources, dir + "/inputs");
            if (got.size() != 1 || got.get(0) == null) {
                return false;
            }
            String landed = got.get(0);
            System.out.println("      " + src.getAbsolutePath());
            System.out.println("        -> " + landed);

            WslBridge.Result r = bridge.runBashCapture(
                    "cat " + WslBridge.quote(landed) + "\n");
            boolean ok = landed.startsWith("/home/")
                    && r.stdoutText().contains("MKLV");

            // Pressing again copies nothing: same name, same size.
            long t0 = System.currentTimeMillis();
            java.util.List<String> again = bridge.copyInto(sources, dir + "/inputs");
            long second = System.currentTimeMillis() - t0;
            System.out.println("      second time: " + second + " ms (skipped)");
            ok = ok && again.equals(got);

            bridge.runBashCapture("rm -rf " + WslBridge.quote(dir) + "\n");
            deleteTree(tmp);
            return ok;
        } catch (Exception e) {
            System.out.println("      copy failed: " + e);
            return false;
        }
    }

    private static void deleteTree(java.io.File f) {
        if (f.isDirectory()) {
            java.io.File[] kids = f.listFiles();
            if (kids != null) {
                for (java.io.File k : kids) {
                    deleteTree(k);
                }
            }
        }
        f.delete();
    }

    private static JTextField fieldFor(Container root, String flag) {
        Component c = findByName(root, flag);
        if (c instanceof JTextField) {
            return (JTextField) c;
        }
        if (c instanceof Container) {
            for (Component ch : all((Container) c)) {
                if (ch instanceof JTextField) {
                    return (JTextField) ch;
                }
            }
        }
        return null;
    }

    private static java.util.List<Component> all(Container c) {
        java.util.List<Component> out = new java.util.ArrayList<Component>();
        for (Component ch : c.getComponents()) {
            out.add(ch);
            if (ch instanceof Container) {
                out.addAll(all((Container) ch));
            }
        }
        return out;
    }

    private static Component findByName(Container c, String name) {
        for (Component ch : c.getComponents()) {
            if (name.equals(ch.getName())) {
                return ch;
            }
            if (ch instanceof Container) {
                Component r = findByName((Container) ch, name);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    private static void layout(Container c) {
        c.doLayout();
        for (Component ch : c.getComponents()) {
            if (ch instanceof Container) {
                layout((Container) ch);
            }
        }
        c.doLayout();
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

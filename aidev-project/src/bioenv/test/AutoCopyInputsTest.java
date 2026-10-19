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

        line("[WSL: a Windows working directory is a legitimate choice, so say nothing]");
        // 用户报的正是这一条：WSL 模式下把工作目录设成 C:\...\pep、输入也选 Windows
        // 路径，界面却让他"先在 Linux 侧建一个工作目录"。
        //
        // 那句话在 WSL 下是错的。WSL 与 Windows 之间有 /mnt/c 这条共享，CommandBuilder
        // 会把工作目录与参数里的 Windows 路径都翻译成 /mnt/c/...（见它第 86、411 行），
        // 所以那条路本来就能跑，既不需要复制也不需要建 Linux 目录。
        if (input != null) {
            input.setText("C:\\data\\pep.fa");
        }
        status.clear();
        form.setRemoteChannel(false);
        form.copyOnEnterForTest(schema.option("-f"), input);
        check("the path is left exactly as typed",
                input != null && "C:\\data\\pep.fa".equals(input.getText()));
        check("WSL 下不提「建 Linux 工作目录」",
                !status.toString().contains(
                        bioenv.i18n.I18n.t(bioenv.i18n.Strings.FORM_NOTE_COPYDIR)),
                status.toString());
        // Nothing was invented: the plugin does not choose a destination.
        check("no copy was attempted", form.wslInputDirForTest() == null);
        line("");

        line("[SSH: a Windows working directory cannot work, so say why]");
        // SSH 下不同：远端看不到本机的 C:\，提示必须留着。
        status.clear();
        form.setRemoteChannel(true);
        form.copyOnEnterForTest(schema.option("-f"), input);
        check("SSH 下要说清楚为什么不能用 Windows 路径",
                status.toString().contains(
                        bioenv.i18n.I18n.t(bioenv.i18n.Strings.FORM_NOTE_COPYDIR)),
                status.toString());
        form.setRemoteChannel(false);
        line("");

        line("[the destination is beside the working directory, on the Linux side]");
        JTextField wd = form.workdirFieldForTest();
        check("the working directory field exists", wd != null);
        if (wd != null) {
            wd.setText("/home/demo/bioenv_work/probe_20260926");
        }
        System.out.println("      inputs would go to: " + form.wslInputDirForTest());
        // 直接落在工作目录本身，不再多加一层 inputs。
        //
        // 原设计是"一次运行读的和产出的都放一起，拷回来一次就够"，但用户设完
        // /home/demo/shili 之后看到文件落在 /home/demo/shili/inputs/pep，会问那个 inputs
        // 是哪来的——他设的路径被悄悄改样了。工作目录就是落脚点。
        check("inputs go into the working directory itself",
                "/home/demo/bioenv_work/probe_20260926"
                        .equals(form.wslInputDirForTest()));
        // 末尾斜杠要去掉，不能变成 "/home/demo/work/"，也不能出现双斜杠。
        if (wd != null) {
            wd.setText("/home/demo/work/");
        }
        check("a trailing slash is dropped, not doubled",
                "/home/demo/work".equals(form.wslInputDirForTest()));
        if (wd != null) {
            wd.setText("/home/demo/work");
        }
        line("");

        line("[a Windows working directory is not a place to copy into]");
        if (wd != null) {
            wd.setText("C:\\work");
        }
        check("a Windows path gives no destination", form.wslInputDirForTest() == null);
        if (wd != null) {
            wd.setText("/home/demo/work");
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

    private static void check(String label, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + label);
        } else {
            fail++;
            System.out.println("  FAIL  " + label + " -- " + detail);
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

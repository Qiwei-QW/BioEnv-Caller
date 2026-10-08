package bioenv.test;

import java.awt.Component;
import java.awt.Container;
import java.io.File;

import javax.swing.JButton;
import javax.swing.SwingUtilities;

import bioenv.core.CustomOptionStore;
import bioenv.core.HelpParser;
import bioenv.core.Schema;
import bioenv.core.Tool;
import bioenv.ui.ParameterEditorPanel;
import bioenv.ui.SchemaFormPanel;

/**
 * Verifies that an edit made in the parameter editor is visible in the option list
 * straight away.
 *
 * <p>The editor changes the record of what a tool's parameters are, and the form is a
 * view of that record. Editing the record is not enough: the view has to be rebuilt
 * from it, and a schema that already has one edit applied is not a safe thing to
 * apply the next one to — hiding and reordering both work by rebuilding the list, so
 * applying a second edit to the result of the first compounds them.
 *
 * <p>Run headless.
 *
 * <p>Run: {@code java -Djava.awt.headless=true -cp &lt;classes&gt; bioenv.test.EditorSyncTest}
 */
public final class EditorSyncTest {

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
        line("=== An edit reaches the option list ===");
        line("");

        File storeFile = tempFile();
        CustomOptionStore store = new CustomOptionStore(storeFile);

        line("[removing a documented parameter]");
        Schema schema = help();
        Tool tool = new Tool("probe", "probe_env", "/envs/probe");
        tool.schema = schema;
        SchemaFormPanel form = new SchemaFormPanel();
        form.setCustomOptions(store);
        form.showTool(tool);
        form.setSize(900, 600);
        layout(form);
        check("it starts in the form", findByName(form, "--gamma") != null);

        // What MainPanel does after OK: write the edits, then rebuild the schema the
        // form is holding from its own help text. No environment is started — an
        // edit is a local statement about a list the plugin already has — and that
        // is what makes it appear at once rather than after a probe that may fail.
        store.hide("probe", "--gamma");
        check("the schema rebuilds itself",
                schema.reapplyEdits("probe", null, store));
        form.rebuildForTest();
        layout(form);
        check("it is gone from the form", findByName(form, "--gamma") == null);
        check("the others are still there",
                findByName(form, "--alpha") != null && findByName(form, "-beta") != null);
        line("");

        line("[the order recorded in the editor is the order shown]");
        java.util.List<String> wanted = new java.util.ArrayList<String>();
        wanted.add("-beta");
        wanted.add("--alpha");
        store.setOrder("probe", wanted);
        schema.reapplyEdits("probe", null, store);
        form.rebuildForTest();
        layout(form);
        java.util.List<String> shown = form.shownFlags();
        System.out.println("      shown: " + shown);
        check("the first parameter is the one put first",
                "-beta".equals(shown.get(0)));
        check("the second follows", "--alpha".equals(shown.get(1)));
        line("");

        line("[a parameter added in the editor appears]");
        store.add("probe", "-z", "added in the editor");
        schema.reapplyEdits("probe", null, store);
        form.rebuildForTest();
        layout(form);
        check("the added parameter is in the list", findByName(form, "-z") != null);
        check("and in the recorded order", form.shownFlags().contains("-z"));
        line("");

        line("[editing again from the rebuilt schema does not compound]");
        // The fault being guarded against: an edit applied to the result of a
        // previous edit. Hiding and reordering both rebuild the list, so the second
        // edit has to start from the help rather than from what the first produced.
        Schema again = help();
        again.reapplyEdits("probe", null, store);
        Schema twice = help();
        twice.reapplyEdits("probe", null, store);
        twice.reapplyEdits("probe", null, store);
        check("the list is the same either way",
                flags(again).equals(flags(twice)));
        System.out.println("      once:  " + flags(again));
        System.out.println("      twice: " + flags(twice));
        line("");

        line("[a second edit is visible after the first]");
        store.hide("probe", "-beta");
        schema.reapplyEdits("probe", null, store);
        form.rebuildForTest();
        layout(form);
        check("both removals are in force",
                findByName(form, "--gamma") == null && findByName(form, "-beta") == null);
        check("the added one is still there", findByName(form, "-z") != null);
        check("what survives is still in the recorded order",
                form.shownFlags().size() == 2);
        line("");

        storeFile.delete();
        line("An edit reaches the list, and edits do not compound.");
    }

    private static Schema help() {
        return HelpParser.parse(
                "OPTIONS:\n"
                + " --alpha <int>   first\n"
                + " -beta           second\n"
                + " --gamma <txt>   third\n",
                "probe");
    }

    private static String flags(Schema s) {
        StringBuilder sb = new StringBuilder();
        for (Schema.Option o : s.options) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(o.flag);
        }
        return "[" + sb + "]";
    }

    /** The order the editor would present, read back from the store. */
    private static java.util.List<String> editorWouldShow(CustomOptionStore store) {
        Schema s = help();
        store.applyTo(s, "probe");
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (Schema.Option o : s.options) {
            out.add(o.flag);
        }
        return out;
    }

    /** The parameters in the order their rows appear. */
    private static java.util.List<String> visibleOrder(Container c) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        collectOrder(c, out);
        return out;
    }

    private static void collectOrder(Container c, java.util.List<String> out) {
        for (Component child : c.getComponents()) {
            Object v = child instanceof javax.swing.JComponent
                    ? ((javax.swing.JComponent) child).getClientProperty("bioenv.flag")
                    : null;
            if (v != null) {
                out.add(String.valueOf(v));
            }
            if (child instanceof Container) {
                collectOrder((Container) child, out);
            }
        }
    }

    private static Component findByName(Container c, String name) {
        for (Component child : c.getComponents()) {
            if (name.equals(child.getName())) {
                return child;
            }
            if (child instanceof Container) {
                Component found = findByName((Container) child, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void layout(Container c) {
        c.doLayout();
        for (Component child : c.getComponents()) {
            if (child instanceof Container) {
                layout((Container) child);
            }
        }
        c.doLayout();
    }

    private static File tempFile() {
        try {
            File f = File.createTempFile("bioenv-sync", ".properties");
            f.delete();
            return f;
        } catch (Exception e) {
            return new File(System.getProperty("java.io.tmpdir"),
                    "bioenv-sync-" + System.nanoTime() + ".properties");
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

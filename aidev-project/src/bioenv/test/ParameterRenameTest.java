package bioenv.test;

import java.awt.Component;
import java.awt.Container;
import java.io.File;

import javax.swing.JButton;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import bioenv.core.CustomOptionStore;
import bioenv.core.DiscoveryCache;
import bioenv.core.HelpParser;
import bioenv.core.Schema;
import bioenv.ui.ParameterEditorPanel;
import bioenv.ui.SchemaFormPanel;

/**
 * Verifies that a parameter's name can be corrected, and that the correction sticks.
 *
 * <p>A help can be misread — a family of options listed with one example, or columns
 * that are ambiguous — so the name the plugin derived may not be one the program
 * accepts. The reader can see that, and correcting the name is how they say so.
 *
 * <p>Run headless.
 *
 * <p>Run: {@code java -Djava.awt.headless=true -cp &lt;classes&gt; bioenv.test.ParameterRenameTest}
 */
public final class ParameterRenameTest {

    private static int pass;
    private static int fail;

    private static final String HELP =
            "SIMPLE USAGE:\n"
            + " probe [options]\n"
            + "\n"
            + "OPTIONS:\n"
            + " -in <file>     input file\n"
            + " -op            one thing the tool stops after\n"
            + " -o             another thing\n";

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
        line("=== Correcting a parameter's name ===");
        line("");

        line("[the name can be changed in the help]");
        Schema s = HelpParser.parse(HELP, "probe");
        check("the help gave three parameters", s.options.size() == 3);
        check("renaming reports work", s.renameInHelp("-o", "--out"));
        Schema after = HelpParser.parse(s.rawHelp, "probe");
        check("the new name is there", after.option("--out") != null);
        check("the old name is gone", after.option("-o") == null);
        check("its description came along",
                after.option("--out") != null
                        && after.option("--out").description.contains("another"));
        // A prefix must not be caught by the change.
        check("the longer name is untouched", after.option("-op") != null);
        check("the first is untouched", after.option("-in") != null);
        line("");

        line("[a rename survives being rebuilt from the help]");
        // The schema is rebuilt from its help every time an edit is applied, so a
        // rename that only touched the option list would be undone by the next edit.
        Schema s2 = HelpParser.parse(HELP, "probe");
        s2.renameInHelp("-op", "--stop-after");
        File storeFile = tempFile();
        CustomOptionStore store = new CustomOptionStore(storeFile);
        store.add("probe", "-x", "added");
        s2.reapplyEdits("probe", null, store);
        check("the renamed one kept its new name", s2.option("--stop-after") != null);
        check("the old name did not come back", s2.option("-op") == null);
        line("");

        line("[and survives a round trip through the cache]");
        File dir = storeFile.getParentFile();
        DiscoveryCache cache = new DiscoveryCache(new File(dir, "cache.json"));
        DiscoveryCache.Data data = new DiscoveryCache.Data();
        DiscoveryCache.CachedTool c = new DiscoveryCache.CachedTool();
        c.command = "probe";
        c.envName = "probe_env";
        c.envPrefix = "/envs/probe";
        c.schema = s2;
        data.tools.add(c);
        cache.save(data);
        Schema restored = cache.load().tools.get(0).schema;
        check("the restored schema has the new name",
                restored.option("--stop-after") != null);
        check("and not the old one", restored.option("-op") == null);
        check("its help says so", restored.rawHelp.contains("--stop-after"));
        line("");

        line("[renaming something that is not there says so]");
        check("an unknown flag reports nothing done",
                !s.renameInHelp("-nothinglikethis", "-x"));
        check("a no-op rename reports nothing done", !s.renameInHelp("-in", "-in"));
        line("");

        line("[the panel offers an editable name]");
        Schema s3 = HelpParser.parse(HELP, "probe");
        CustomOptionStore uiStore = new CustomOptionStore(
                new File(dir, "ui.properties"));
        ParameterEditorPanel editor = new ParameterEditorPanel(s3, "probe", uiStore);
        editor.setSize(900, 600);
        layout(editor);

        Component nameBox = findByName(editor, "bioenv.editor.name.-op");
        check("the name is a text field", nameBox instanceof JTextField);
        check("it starts with the current name",
                nameBox instanceof JTextField
                        && "-op".equals(((JTextField) nameBox).getText()));
        // The description was already editable and stays so.
        check("the description is editable too",
                findByName(editor, "bioenv.editor.desc.-op") instanceof JTextField);
        line("");

        line("[typing a new name is recorded]");
        if (nameBox instanceof JTextField) {
            ((JTextField) nameBox).setText("--stop-after");
        }
        check("the panel reports the rename",
                "--stop-after".equals(editor.renames().get("-op")));
        check("and the row now carries the new name",
                editor.orderedFlags().contains("--stop-after"));
        // Nothing reaches the help until OK, so Cancel remains an escape.
        check("the help is untouched until OK", s3.option("-op") != null);
        line("");

        line("[typing one character at a time does not leave a chain]");
        ParameterEditorPanel e2 = new ParameterEditorPanel(
                HelpParser.parse(HELP, "probe"), "probe", uiStore);
        e2.setSize(900, 600);
        layout(e2);
        JTextField box = (JTextField) findByName(e2, "bioenv.editor.name.-o");
        if (box != null) {
            box.setText("--o");
            box.setText("--ou");
            box.setText("--out");
        }
        check("one rename is recorded, from the original name",
                e2.renames().size() == 1 && "--out".equals(e2.renames().get("-o")));
        line("");

        line("[applying a rename through the form]");
        ParameterEditorPanel e3 = new ParameterEditorPanel(
                HelpParser.parse(HELP, "probe"), "probe", uiStore);
        e3.setSize(900, 600);
        layout(e3);
        JTextField b3 = (JTextField) findByName(e3, "bioenv.editor.name.-op");
        if (b3 != null) {
            b3.setText("--stop");
        }
        Schema schema3 = HelpParser.parse(HELP, "probe");
        e3.setCurrentSchema(schema3);
        // applyChanges writes the rename into the help itself; the list is rebuilt
        // from that help, which is what the form then shows. Both steps are what
        // MainPanel does on OK.
        e3.applyChanges();
        schema3.reapplyEdits("probe", null, uiStore);
        check("the schema carries the new name", schema3.option("--stop") != null);
        check("and not the old one", schema3.option("-op") == null);

        SchemaFormPanel form = new SchemaFormPanel();
        form.setCustomOptions(uiStore);
        bioenv.core.Tool t = new bioenv.core.Tool("probe", "probe_env", "/envs/probe");
        t.schema = schema3;
        form.showTool(t);
        form.setSize(900, 600);
        layout(form);
        check("the form shows the new name", findByName(form, "--stop") != null);
        check("and not the old one", findByName(form, "-op") == null);
        line("");

        line("[a parameter that had no description can be given one]");
        // The column lands at the end of a line with no description, so the new text
        // has to be separated from the name: joined straight on it becomes one word,
        // and the next parse reads that as a parameter of its own.
        check("a description can be set on a bare flag",
                descriptionOnBareFlag());
        line("");

        line("[a rename does not disturb the rest]");
        check("a description the user rewrote is kept",
                applyDescriptionKeepsName());
        line("");

        deleteTree(dir);
        line("A misread name can be corrected, and the correction sticks.");
    }

    /** A flag the help gave no description can be given one, and it sticks. */
    private static boolean descriptionOnBareFlag() {
        String help = "OPTIONS:\n -in <file>   input file\n -q\n";
        File f = new File(System.getProperty("java.io.tmpdir"),
                "bioenv-bare-" + System.nanoTime() + ".properties");
        CustomOptionStore store = new CustomOptionStore(f);
        Schema s = HelpParser.parse(help, "probe");
        if (s.option("-q") == null || !s.option("-q").description.isEmpty()) {
            return false;
        }
        ParameterEditorPanel ed = new ParameterEditorPanel(s, "probe", store);
        ed.setSize(900, 600);
        layout(ed);
        JTextField d = (JTextField) findByName(ed, "bioenv.editor.desc.-q");
        if (d == null) {
            return false;
        }
        d.setText("be quiet");
        ed.setCurrentSchema(s);
        ed.applyChanges();
        s.reapplyEdits("probe", null, store);
        Schema.Option q = s.option("-q");
        boolean ok = q != null && "be quiet".equals(q.description);
        // The flag must still be a flag: joining the text on would have made "-qbe
        // quiet", which parses as nothing at all.
        ok = ok && s.options.size() == 2;
        f.delete();
        return ok;
    }

    /** A description edited on an added parameter survives, with the name intact. */
    private static boolean applyDescriptionKeepsName() {
        File f = new File(System.getProperty("java.io.tmpdir"),
                "bioenv-rename-" + System.nanoTime() + ".properties");
        CustomOptionStore store = new CustomOptionStore(f);
        Schema s = HelpParser.parse(HELP, "probe");
        ParameterEditorPanel ed = new ParameterEditorPanel(s, "probe", store);
        ed.setSize(900, 600);
        layout(ed);
        JTextField desc = (JTextField) findByName(ed, "bioenv.editor.desc.-in");
        if (desc != null) {
            desc.setText("the input proteome");
        }
        JTextField nm = (JTextField) findByName(ed, "bioenv.editor.name.-in");
        if (nm != null) {
            nm.setText("-input");
        }
        ed.setCurrentSchema(s);
        ed.applyChanges();
        s.reapplyEdits("probe", null, store);
        Schema.Option o = s.option("-input");
        return o != null && o.description != null
                && o.description.contains("input proteome");
    }

    private static File tempFile() {
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "bioenv-rename-" + System.nanoTime());
        dir.mkdirs();
        return new File(dir, "edits.properties");
    }

    private static void deleteTree(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    deleteTree(k);
                }
            }
        }
        f.delete();
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

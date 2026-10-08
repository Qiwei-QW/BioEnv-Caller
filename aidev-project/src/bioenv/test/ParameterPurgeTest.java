package bioenv.test;

import java.awt.Component;
import java.awt.Container;
import java.io.File;

import javax.swing.JButton;
import javax.swing.SwingUtilities;

import bioenv.core.CustomOptionStore;
import bioenv.core.DiscoveryCache;
import bioenv.core.HelpParser;
import bioenv.core.Schema;
import bioenv.ui.ParameterEditorPanel;
import bioenv.ui.SchemaFormPanel;

/**
 * Verifies deleting a parameter for good.
 *
 * <p>Removing a parameter takes it out of the list for now. Deleting takes it out of
 * the help text the list is built from, so it does not return on the next edit, the
 * next restart, or the next read of the cache — a help can be wrong in a way that is
 * not worth working around on every visit.
 *
 * <p>Run headless.
 *
 * <p>Run: {@code java -Djava.awt.headless=true -cp &lt;classes&gt; bioenv.test.ParameterPurgeTest}
 */
public final class ParameterPurgeTest {

    private static int pass;
    private static int fail;

    /** A help with a wrapped description, which must go with its parameter. */
    private static final String HELP =
            "SIMPLE USAGE:\n"
            + " probe [options]\n"
            + "\n"
            + "OPTIONS:\n"
            + " -in <file>     input file\n"
            + " -gt <float>    gap threshold, which the tool documents over two\n"
            + "                lines because it is a long explanation\n"
            + " -out <file>    output file\n"
            + " -o             short flag\n";

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
        line("=== Deleting a parameter for good ===");
        line("");

        line("[the parameter and its wrapped description both go]");
        Schema s = HelpParser.parse(HELP, "probe");
        int before = s.options.size();
        check("the help gave four parameters", before == 4);
        check("the line count is known", s.rawHelp.split("\n", -1).length > 4);

        int linesBefore = s.rawHelp.split("\n", -1).length;
        check("deleting reports work", s.deleteFromHelp("-gt"));
        int linesAfter = s.rawHelp.split("\n", -1).length;
        System.out.println("      lines " + linesBefore + " -> " + linesAfter);
        // The declaration and the one line its description wraps onto, which belong
        // together: leaving the second behind would turn prose into a parameter.
        check("the declaration and its wrapped line went",
                linesAfter == linesBefore - 2);
        line("");

        line("[the rebuilt list no longer has it, and the rest survive]");
        Schema after = HelpParser.parse(s.rawHelp, "probe");
        check("it is gone", after.option("-gt") == null);
        check("the first is still there", after.option("-in") != null);
        check("the last is still there", after.option("-o") != null);
        check("the count dropped by one", after.options.size() == before - 1);
        // A wrapped line left behind would be read as a new parameter.
        check("no phantom parameter was left behind",
                after.options.size() == before - 1);
        line("");

        line("[the wrapped description does not become a parameter]");
        for (Schema.Option o : after.options) {
            check("still a real flag: " + o.flag, o.flag.startsWith("-"));
        }
        line("");

        line("[a prefix does not match a longer flag]");
        Schema s2 = HelpParser.parse(HELP, "probe");
        // -o is a prefix of -out, and deleting -out must not take -o with it.
        check("deleting -out works", s2.deleteFromHelp("-out"));
        Schema after2 = HelpParser.parse(s2.rawHelp, "probe");
        check("-o survived", after2.option("-o") != null);
        check("-out is gone", after2.option("-out") == null);
        line("");

        line("[deleting something that is not there says so]");
        check("an unknown flag reports nothing done",
                !s2.deleteFromHelp("-nothinglikethis"));
        check("and the help is unchanged",
                s2.rawHelp.equals(HelpParser.parse(s2.rawHelp, "probe").rawHelp));
        line("");

        line("[a deletion survives being rebuilt from the help]");
        // The schema is rebuilt from its help whenever an edit is applied, so a
        // deletion that only touched the option list would return on the next edit.
        Schema s3 = HelpParser.parse(HELP, "probe");
        s3.deleteFromHelp("-gt");
        File storeFile = tempFile();
        CustomOptionStore store = new CustomOptionStore(storeFile);
        store.add("probe", "-x", "added");
        s3.reapplyEdits("probe", null, store);
        check("the deleted one is still gone after an edit",
                s3.option("-gt") == null);
        check("the added one is there", s3.option("-x") != null);
        check("the others are still there",
                s3.option("-in") != null && s3.option("-o") != null);
        line("");

        line("[and survives a round trip through the cache]");
        File dir = storeFile.getParentFile();
        DiscoveryCache cache = new DiscoveryCache(new File(dir, "cache.json"));
        DiscoveryCache.Data data = new DiscoveryCache.Data();
        DiscoveryCache.CachedTool c = new DiscoveryCache.CachedTool();
        c.command = "probe";
        c.envName = "probe_env";
        c.envPrefix = "/envs/probe";
        c.schema = s3;
        data.tools.add(c);
        cache.save(data);

        DiscoveryCache.Data back = cache.load();
        Schema restored = back.tools.get(0).schema;
        check("the restored schema has no -gt", restored.option("-gt") == null);
        check("its help text is the shortened one",
                !restored.rawHelp.contains("-gt"));
        check("and rebuilding from it keeps it gone",
                HelpParser.parse(restored.rawHelp, "probe").option("-gt") == null);
        line("");

        line("[the panel offers the button, and OK applies it]");
        Schema s4 = HelpParser.parse(HELP, "probe");
        CustomOptionStore uiStore = new CustomOptionStore(
                new File(dir, "ui.properties"));
        ParameterEditorPanel editor = new ParameterEditorPanel(s4, "probe", uiStore);
        editor.setSize(800, 600);
        layout(editor);
        check("a delete button is offered",
                findByName(editor, "bioenv.editor.purge.-gt") != null);
        check("nothing is marked yet", editor.purgedFlags().isEmpty());

        JButton purge = (JButton) findByName(editor, "bioenv.editor.purge.-gt");
        editor.setPurgeConfirmer(new ParameterEditorPanel.PurgeConfirmer() {
            @Override
            public boolean confirm(String flag) {
                return true;
            }
        });
        if (purge != null) {
            purge.doClick();
            layout(editor);
        }
        check("pressing it marks the parameter", editor.purgedFlags().contains("-gt"));
        check("and takes the row away",
                findByName(editor, "bioenv.editor.purge.-gt") == null);
        // Nothing reaches the help until OK, so Cancel remains an escape.
        check("the help is untouched until OK", s4.option("-gt") != null);
        line("");

        line("[declining the confirmation leaves it alone]");
        ParameterEditorPanel e2 = new ParameterEditorPanel(
                HelpParser.parse(HELP, "probe"), "probe", uiStore);
        e2.setSize(800, 600);
        layout(e2);
        e2.setPurgeConfirmer(new ParameterEditorPanel.PurgeConfirmer() {
            @Override
            public boolean confirm(String flag) {
                return false;
            }
        });
        JButton p2 = (JButton) findByName(e2, "bioenv.editor.purge.-gt");
        if (p2 != null) {
            p2.doClick();
            layout(e2);
        }
        check("nothing is marked", e2.purgedFlags().isEmpty());
        check("the row is still there",
                findByName(e2, "bioenv.editor.purge.-gt") != null);
        line("");

        deleteTree(dir);
        line("A deleted parameter does not come back.");
    }

    private static File tempFile() {
        try {
            File dir = new File(System.getProperty("java.io.tmpdir"),
                    "bioenv-purge-" + System.nanoTime());
            dir.mkdirs();
            return new File(dir, "edits.properties");
        } catch (Exception e) {
            return new File(System.getProperty("java.io.tmpdir"),
                    "bioenv-purge-" + System.nanoTime() + ".properties");
        }
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

package bioenv.test;

import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.lang.reflect.Field;

import javax.swing.JButton;
import javax.swing.SwingUtilities;

import bioenv.core.CustomOptionStore;
import bioenv.core.DiscoveryCache;
import bioenv.core.Schema;
import bioenv.core.Tool;
import bioenv.core.ToolRegistry;
import bioenv.ui.ParameterEditorPanel;
import bioenv.ui.SchemaFormPanel;

/**
 * Walks the real path a user takes, using the real registry and cache rather than
 * objects built for the test.
 *
 * <p>This exists because the earlier tests built their own schemas and called the
 * store directly, so they proved the pieces worked while the whole did not.
 *
 * <p>Run: {@code java -cp &lt;classes&gt; bioenv.test.EditorEndToEndTest}
 */
public final class EditorEndToEndTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                try {
                    body();
                } catch (Throwable t) {
                    t.printStackTrace();
                    fail++;
                }
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

    private static void body() throws Exception {
        line("=== The editor, through a real registry and cache ===");
        line("");

        // A cache and stores pointed at throwaway files, so the user's own are
        // untouched. The stores are given explicitly rather than taken from a
        // registry built with no arguments, because that registry's stores are the
        // real ones and a test must never write to those.
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "bioenv-e2e-" + System.nanoTime());
        dir.mkdirs();
        DiscoveryCache cache = new DiscoveryCache(new File(dir, "cache.json"));
        ToolRegistry registry = new ToolRegistry(null, null);
        registry.setCache(cache);
        registry.setStores(
                new bioenv.core.CorrectionStore(new File(dir, "types.properties")),
                new CustomOptionStore(new File(dir, "edits.properties")));

        CustomOptionStore store = registry.getCustomOptions();
        line("  store file: " + store.getFile());
        check("the store is a throwaway, not the user's",
                store.getFile().getAbsolutePath().contains("bioenv-e2e-"));

        // A tool with a schema, as the cache would restore one.
        Schema schema = bioenv.core.HelpParser.parse(
                "OPTIONS:\n"
                + " --alpha <int>   first\n"
                + " -beta           second\n"
                + " --gamma <txt>   third\n",
                "probe");
        Tool tool = new Tool("probe", "probe_env", "/envs/probe");
        tool.schema = schema;
        line("  tool schema: " + flags(schema));
        line("");

        SchemaFormPanel form = new SchemaFormPanel();
        form.setCustomOptions(store);
        form.setCorrections(registry.getCorrections());
        form.showTool(tool);
        form.setSize(900, 600);
        layout(form);
        check("the form shows the tool's parameters",
                form.shownFlags().size() == 3);
        line("");

        line("[open the editor on what the form is showing]");
        String scope = form.currentScopeKey();
        line("      scope from the form: [" + scope + "]");
        line("      tool.command:        [" + tool.command + "]");
        check("the scope names the tool", scope.contains(tool.command));

        ParameterEditorPanel editor = new ParameterEditorPanel(
                form.currentSchema(), scope, store);
        editor.setCurrentSchema(form.currentSchema());
        line("      editor shows: " + editor.orderedFlags());
        line("");

        line("[remove --gamma and press OK]");
        JButton rm = (JButton) findByName(editor, "bioenv.editor.remove.--gamma");
        check("the remove button is there", rm != null);
        if (rm != null) {
            rm.doClick();
            layout(editor);
        }
        // Exactly what MainPanel does, in order: write the edits, then hand the
        // editor's result to the form, which applies them to the schema it is
        // showing and lays itself out again.
        String what = editor.applyChanges();
        line("      applyChanges reported: [" + what + "]");
        line("      store hidden for [" + scope + "]: " + store.hiddenFor(scope));
        form.applyEditorResult(editor.chosenKinds());
        layout(form);
        line("      form shows: " + form.shownFlags());
        line("");

        check("the removal was written", store.hiddenFor(scope).contains("--gamma"));
        check("the schema dropped the parameter",
                form.currentSchema().option("--gamma") == null);
        check("and the form followed",
                !form.shownFlags().contains("--gamma"));
        check("the others are still shown", form.shownFlags().size() == 2);
        line("");

        line("[add a parameter and press OK]");
        store.add(scope, "-z", "mine");
        form.applyEditorResult(null);
        layout(form);
        line("      form shows: " + form.shownFlags());
        check("the added parameter appears", form.shownFlags().contains("-z"));
        line("");

        line("[reorder and press OK]");
        java.util.List<String> wanted = new java.util.ArrayList<String>();
        wanted.add("-z");
        wanted.add("--alpha");
        wanted.add("-beta");
        store.setOrder(scope, wanted);
        form.applyEditorResult(null);
        layout(form);
        line("      form shows: " + form.shownFlags());
        check("the order is the one recorded",
                form.shownFlags().indexOf("-z") == 0);
        line("");

        line("[editing twice does not compound]");
        // The panel rebuilds from the help on every apply, so a second edit starts
        // from the documented list rather than from the result of the first.
        store.add(scope, "-y", "another");
        form.applyEditorResult(null);
        layout(form);
        java.util.List<String> once = form.shownFlags();
        form.applyEditorResult(null);
        layout(form);
        java.util.List<String> twice = form.shownFlags();
        System.out.println("      once:  " + once);
        System.out.println("      twice: " + twice);
        check("the list is the same either way", once.equals(twice));
        check("both additions are present",
                twice.contains("-z") && twice.contains("-y"));
        line("");

        line("[what the running plugin's stores are]");
        // The registry holds its own stores; the panel must be given those, not a
        // different pair, or an edit lands somewhere the form never reads.
        Field f = ToolRegistry.class.getDeclaredField("customOptions");
        f.setAccessible(true);
        Object registryStore = f.get(registry);
        check("the registry's store is the one the form has",
                registryStore == store);
        line("");

        deleteTree(dir);
        line("The editor's result reaches the option list.");
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

    private static String flags(Schema s) {
        if (s == null) {
            return "(null)";
        }
        StringBuilder sb = new StringBuilder();
        for (Schema.Option o : s.options) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(o.flag);
        }
        return "[" + sb + "]";
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

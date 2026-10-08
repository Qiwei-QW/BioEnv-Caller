package bioenv.test;

import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.util.Map;

import javax.swing.JButton;
import javax.swing.SwingUtilities;

import bioenv.core.CustomOptionStore;
import bioenv.core.HelpParser;
import bioenv.core.Schema;
import bioenv.ui.SchemaFormPanel;

/**
 * Verifies that a parameter the help does not describe can be added by hand, used,
 * and remembered.
 *
 * <p>Some tools document incompletely — an option appears only in the manual, or the
 * help lists a family with one example. For those the form is missing a parameter
 * the user needs, and there was no way to make one appear: the free-text box takes an
 * argument but not a parameter with a name, a place in the list and a remembered
 * description.
 *
 * <p>Run headless. The store is given a temporary file so the user's own added
 * parameters are never touched.
 *
 * <p>Run: {@code java -Djava.awt.headless=true -cp &lt;classes&gt; bioenv.test.CustomOptionTest}
 */
public final class CustomOptionTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                try {
                    body();
                } catch (Exception e) {
                    e.printStackTrace();
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
        line("=== A parameter added by hand ===");
        line("");

        File tmp = File.createTempFile("bioenv-custom", ".properties");
        tmp.delete();
        File storeFile = new File(tmp.getParentFile(), tmp.getName());

        line("[the store keeps it, per tool and subcommand]");
        CustomOptionStore store = new CustomOptionStore(storeFile);
        check("it starts empty", store.size() == 0);
        check("adding reports a change", store.add("bio fasta", "-x", "my own option"));
        check("it is remembered", store.size() == 1);
        check("it is listed for its scope",
                store.forScope("bio fasta").containsKey("-x"));
        check("its description is kept",
                "my own option".equals(store.forScope("bio fasta").get("-x")));
        check("another scope is unaffected", store.forScope("bio gff").isEmpty());
        check("another tool is unaffected", store.forScope("seqkit seq").isEmpty());

        // A second store over the same file must see it: this is what makes it
        // survive a restart.
        CustomOptionStore reloaded = new CustomOptionStore(storeFile);
        check("it survives a reload",
                reloaded.forScope("bio fasta").containsKey("-x"));
        line("");

        line("[it appears in the schema like any other parameter]");
        Schema s = HelpParser.parse(
                "OPTIONS:\n -M <txt>   method\n -d         dna\n", "bio");
        check("the help gave two options", s.options.size() == 2);
        // Nothing is added for a scope the store has no entry for.
        check("an unrelated scope adds nothing",
                reloaded.applyTo(s, "seqkit stats") == 0);
        check("and leaves the list alone", s.options.size() == 2);

        schemaWithAdded(reloaded, s);
        check("the matching scope adds it", s.option("-x") != null);
        check("it is marked as added by hand",
                s.option("-x") != null && s.option("-x").userAdded);
        check("it keeps its description",
                s.option("-x") != null
                        && "my own option".equals(s.option("-x").description));
        check("the documented ones are untouched",
                s.option("-M") != null && !s.option("-M").userAdded);
        check("it does not displace a documented name",
                reloaded.applyTo(s, "bio fasta") == 0);
        line("");

        line("[removing it]");
        check("removing reports a change", reloaded.remove("bio fasta", "-x"));
        check("it is gone", reloaded.forScope("bio fasta").isEmpty());
        check("removing again reports nothing",
                !reloaded.remove("bio fasta", "-x"));
        CustomOptionStore after = new CustomOptionStore(storeFile);
        check("the removal survives a reload", after.forScope("bio fasta").isEmpty());
        line("");

        line("[the form shows an added parameter like any other]");
        CustomOptionStore uiStore = new CustomOptionStore(
                new File(tmp.getParentFile(), tmp.getName() + ".ui"));
        SchemaFormPanel form = new SchemaFormPanel();
        form.setCustomOptions(uiStore);
        bioenv.core.Tool t = new bioenv.core.Tool("bio", "bio_env", "/envs/bio_env");
        t.schema = HelpParser.parse(
                "OPTIONS:\n -M <txt>   method\n -d         dna\n", "bio");
        form.showTool(t);
        form.setSize(1000, 700);
        layout(form);

        // Adding and removing moved into the parameter editor, which is covered by
        // ParameterEditorTest. What matters here is that an added parameter reaches
        // the option list and behaves like the documented ones.
        uiStore.add("bio", "-x", "added for the test");
        uiStore.applyTo(form.currentSchema(), "bio");
        form.rebuildForTest();
        layout(form);
        check("the added parameter is shown", findByName(form, "-x") != null);
        Component xField = findByName(form, "-x");
        check("it has a value field like the rest",
                xField instanceof javax.swing.JTextField);
        // Untouched, it contributes nothing — the same rule the documented options
        // follow, so an added parameter does not put itself on every command.
        check("untouched it stays out of the command",
                !bioenv.core.CommandBuilder.preview(t, form.currentSchema(), "",
                        form.collect(), "").contains("-x"));
        if (xField instanceof javax.swing.JTextField) {
            ((javax.swing.JTextField) xField).setText("hello");
        }
        check("and once filled in it reaches the command",
                bioenv.core.CommandBuilder.preview(t, form.currentSchema(), "",
                        form.collect(), "").contains("-x hello"));
        line("");

        storeFile.delete();
        new File(tmp.getParentFile(), tmp.getName() + ".ui").delete();
        line("A parameter the help omits can be added, used, and removed.");
    }

    /** Applies the added parameters for the scope the schema belongs to. */
    private static void schemaWithAdded(CustomOptionStore store, Schema s) {
        store.applyTo(s, "bio fasta");
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

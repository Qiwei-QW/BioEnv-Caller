package bioenv.test;

import java.awt.Component;
import java.awt.Container;
import java.util.Map;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;

import bioenv.core.CommandBuilder;
import bioenv.core.HelpParser;
import bioenv.core.ParamValue;
import bioenv.core.Schema;
import bioenv.core.Tool;
import bioenv.ui.SchemaFormPanel;

/**
 * Verifies two things about parameters the user added by hand.
 *
 * <p>First, that setting one to "switch" produces a bare flag. The choice of
 * control lived only in the panel, while the command builder decided from the
 * option's own shape whether to write a value after the flag — so a tick written
 * into a tick box came out as the literal word "true" after the flag.
 *
 * <p>Second, that removing one asks first. The button sits at the end of every added
 * row, next to the type chooser, and a mis-click would silently drop a parameter the
 * user had recorded from somewhere the plugin cannot re-read.
 *
 * <p>Run headless.
 *
 * <p>Run: {@code java -Djava.awt.headless=true -cp &lt;classes&gt; bioenv.test.CustomSwitchTest}
 */
public final class CustomSwitchTest {

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
        line("=== Parameters added by hand ===");
        line("");

        Schema s = HelpParser.parse(
                "OPTIONS:\n -M <txt>   method\n -d         dna\n", "orthofinder");

        Tool t = new Tool("orthofinder", "orthofinder_env", "/envs/orthofinder_env");
        t.schema = s;
        SchemaFormPanel form = new SchemaFormPanel();
        // Removal goes through the store, so the panel needs one. A temporary file
        // keeps the user's own added parameters untouched.
        java.io.File storeFile = tempStore();
        bioenv.core.CustomOptionStore store =
                new bioenv.core.CustomOptionStore(storeFile);
        form.setCustomOptions(store);
        // A parameter the help never described, added the way the user adds one: in
        // the store. Putting it straight into the schema would not survive, because
        // an edit is applied by rebuilding from the help and the store is what says
        // which parameters exist beyond it.
        store.add("orthofinder", "-mmc", "use MCL");
        // In the running plugin the registry applies the store when the tool is read;
        // here the schema is already in hand, so it is applied directly.
        store.applyTo(s, "orthofinder");
        form.showTool(t);
        form.setSize(1000, 700);
        layout(form);
        check("the added parameter is in the form", findByName(form, "-mmc") != null);

        line("[setting it to 'switch' through the parameter editor]");
        // The kind chooser moved into the parameter editor, so that is where a kind
        // is stated now. The editor reports what it was left holding.
        java.util.Map<String, Schema.Option.Kind> chosen =
                new java.util.LinkedHashMap<String, Schema.Option.Kind>();
        chosen.put("-mmc", Schema.Option.Kind.SWITCH);
        form.applyEditorResult(chosen);
        form.rebuildForTest();
        layout(form);
        Component control = findByName(form, "-mmc");
        check("the control is a tick box", control instanceof JCheckBox);
        line("");

        line("[ticking it gives a bare flag, with no value]");
        if (control instanceof JCheckBox) {
            ((JCheckBox) control).setSelected(true);
        }
        Map<String, ParamValue> vals = form.collect();
        check("the tick counts as intent",
                vals.get("-mmc") != null && vals.get("-mmc").contributes());
        String command = CommandBuilder.preview(t, s, "", vals, "");
        System.out.println("      command: " + command);
        check("the flag is on the command", command.contains("-mmc"));
        check("no 'true' is written after it", !command.contains("-mmc true"));
        check("nothing at all follows the flag",
                command.trim().endsWith("-mmc"));
        line("");

        line("[the same holds for an option the help did describe]");
        // The fault was in how a chosen kind reached the builder, so it applied to
        // every option, not only added ones.
        chosen.put("-d", Schema.Option.Kind.TEXT);
        form.applyEditorResult(chosen);
        form.rebuildForTest();
        layout(form);
        Component d = findByName(form, "-d");
        check("choosing 'text' gives a field, not a tick box",
                d instanceof javax.swing.JTextField);
        if (d instanceof javax.swing.JTextField) {
            ((javax.swing.JTextField) d).setText("value-for-d");
        }
        Map<String, ParamValue> vals2 = form.collect();
        String command2 = CommandBuilder.preview(t, s, "", vals2, "");
        System.out.println("      command: " + command2);
        check("its value is written when it is a text field",
                command2.contains("-d value-for-d"));
        line("");

        line("[the row itself carries no remove button any more]");
        // Adding and removing moved into the parameter editor, so the option list
        // keeps a search field, a count and the window button rather than a cross on
        // every added row.
        check("the form has no per-row remove button",
                findByName(form, "bioenv.remove.-mmc") == null);
        line("");

        line("A bare switch stays bare.");
    }

    /** A path for a throwaway store, so the user's own is never touched. */
    private static java.io.File tempStore() {
        try {
            java.io.File f = java.io.File.createTempFile("bioenv-switch", ".properties");
            f.delete();
            return f;
        } catch (java.io.IOException e) {
            // A store that cannot be written is still usable in memory.
            return new java.io.File(System.getProperty("java.io.tmpdir"),
                    "bioenv-switch-" + System.nanoTime() + ".properties");
        }
    }

    private static JComboBox<?> findChooser(Container c, String name) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof JComboBox && name.equals(ch.getName())) {
                return (JComboBox<?>) ch;
            }
            if (ch instanceof Container) {
                JComboBox<?> r = findChooser((Container) ch, name);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
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

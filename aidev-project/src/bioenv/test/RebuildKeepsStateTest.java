package bioenv.test;

import java.awt.Component;
import java.awt.Container;
import java.util.Map;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import bioenv.core.HelpParser;
import bioenv.core.ParamValue;
import bioenv.core.Schema;
import bioenv.core.Tool;
import bioenv.ui.SchemaFormPanel;

/**
 * Verifies that a rebuild keeps what the user entered.
 *
 * <p>A rebuild recreates every control, so a control not given its old value comes
 * back at its default. That lost a ticked switch whenever the panel was rebuilt for
 * an unrelated reason — and closing the options window rebuilds it, so running from
 * that window dropped the tick and the command came out without the option.
 *
 * <p>Run headless: no frame is created, so the process exits on its own.
 *
 * <p>Run: {@code java -Djava.awt.headless=true -cp &lt;classes&gt; bioenv.test.RebuildKeepsStateTest}
 */
public final class RebuildKeepsStateTest {

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
        line("=== A rebuild keeps what was entered ===");
        line("");

        Schema s = HelpParser.parse(
                "OPTIONS:\n"
                + " -d          Input is DNA sequences.\n"
                + " -v          Show the current version number\n"
                + " -o <txt>    Output directory\n",
                "orthofinder");
        Tool t = new Tool("orthofinder", "orthofinder_env", "/envs/orthofinder_env");
        t.schema = s;
        SchemaFormPanel form = new SchemaFormPanel();
        form.showTool(t);
        form.setSize(1000, 700);
        layout(form);

        line("[a ticked switch survives]");
        // The kind is stated through the parameter editor, which is where a kind is
        // chosen now, and arrives at the form as the editor's result.
        java.util.Map<String, Schema.Option.Kind> chosen =
                new java.util.LinkedHashMap<String, Schema.Option.Kind>();
        chosen.put("-v", Schema.Option.Kind.SWITCH);
        form.applyEditorResult(chosen);
        form.rebuildForTest();
        layout(form);
        Component v = findByName(form, "-v");
        check("stating 'switch' gives a tick box", v instanceof JCheckBox);
        if (v instanceof JCheckBox) {
            ((JCheckBox) v).setSelected(true);
        }
        check("ticking it counts as intent", contributes(form, "-v"));

        form.rebuildForTest();
        Component v2 = findByName(form, "-v");
        check("it is still a tick box", v2 instanceof JCheckBox);
        check("and still ticked", v2 instanceof JCheckBox && ((JCheckBox) v2).isSelected());
        check("so it still reaches the command", contributes(form, "-v"));
        line("");

        line("[typed text survives]");
        Component o = findByName(form, "-o");
        check("the output option is a text field", o instanceof JTextField);
        if (o instanceof JTextField) {
            ((JTextField) o).setText("/data/out");
        }
        form.rebuildForTest();
        Component o2 = findByName(form, "-o");
        check("the text is still there",
                o2 instanceof JTextField && "/data/out".equals(((JTextField) o2).getText()));
        check("and still reaches the command", contributes(form, "-o"));
        line("");

        line("[an untouched control stays untouched]");
        // The point of tracking intent: a control the user never touched must not
        // start contributing just because the form was rebuilt.
        check("an untouched switch contributes nothing", !contributes(form, "-d"));
        form.rebuildForTest();
        check("and still does after a rebuild", !contributes(form, "-d"));
        line("");

        line("A rebuild restores what the user entered, and only that.");
    }

    private static boolean contributes(SchemaFormPanel form, String flag) {
        Map<String, ParamValue> vals = form.collect();
        ParamValue pv = vals.get(flag);
        return pv != null && pv.contributes();
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

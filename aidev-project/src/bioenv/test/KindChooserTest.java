package bioenv.test;

import java.awt.Component;
import java.awt.Container;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.swing.JCheckBox;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import bioenv.core.HelpParser;
import bioenv.core.ParamValue;
import bioenv.core.Schema;
import bioenv.core.Tool;
import bioenv.ui.SchemaFormPanel;

/**
 * Verifies that a stated kind decides the control a parameter is given and how it
 * reaches the command line.
 *
 * <p>The kind used to be chosen from a dropdown beside every row. It is now stated
 * in the parameter editor, which is where the list as a whole is corrected, so this
 * test states kinds the way the editor does — through the form's own result — and
 * checks what the form does with them. That is the contract that matters: the
 * control shown, the value read back, and the argument written.
 *
 * <p>Run headless.
 *
 * <p>Run: {@code java -Djava.awt.headless=true -cp &lt;classes&gt; bioenv.test.KindChooserTest}
 */
public final class KindChooserTest {

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
        line("=== A stated kind shapes the row ===");
        line("");

        Schema schema = fixture();
        Tool tool = new Tool("demo", "demo_env", "/envs/demo");
        tool.schema = schema;
        SchemaFormPanel form = new SchemaFormPanel();
        form.showTool(tool);
        form.setSize(900, 600);
        layout(form);

        line("[an inferred kind is honoured, and nothing is guessed from the value]");
        // The parser reads the type out of the help text (placeholder shape first),
        // and the form shows the control that type calls for. A row the parser
        // could not type stays a plain field, which is the safety net: a wrong
        // guess about a value's *shape* never turns into a wrong control.
        //
        // Historical note: this block used to assert that every row was a plain
        // text field, on the reasoning that "the plugin does not guess a control
        // from a value's shape". That reasoning still holds for the shape of a
        // typed value, but the parser genuinely does know these three types —
        // <file> is a path, <int> is a number, and a flag that takes no value is a
        // switch — so text-by-default was hiding types the help had already stated.
        // SchemaFormPanel.kindOf() now applies the inferred kind; see its comment.
        check("the path option offers a text field inside its control",
                fieldInside(findEditor(form, "-i")) != null);
        check("the numeric option becomes a spinner",
                findEditor(form, "-n") instanceof JSpinner);
        check("the switch option becomes a tick box",
                findEditor(form, "-q") instanceof JCheckBox);
        check("no kind chooser is drawn beside a row",
                countChoosers(form) == 0);
        line("");

        line("[stating a kind changes the control]");
        Map<String, Schema.Option.Kind> kinds =
                new LinkedHashMap<String, Schema.Option.Kind>();
        kinds.put("-i", Schema.Option.Kind.INPUT_PATH);
        kinds.put("-n", Schema.Option.Kind.NUMBER);
        kinds.put("-q", Schema.Option.Kind.SWITCH);
        form.applyEditorResult(kinds);
        form.rebuildForTest();
        layout(form);

        check("an input path offers a browse button", hasBrowseButton(form, "-i"));
        check("a number becomes a spinner",
                findEditor(form, "-n") instanceof JSpinner);
        check("a switch becomes a tick box",
                findEditor(form, "-q") instanceof JCheckBox);
        line("");

        line("[a value already typed survives the change of control]");
        // Reload with the kinds already stated, type into the number, then state a
        // different kind: what was typed is what the new control starts from.
        form.showTool(tool);
        form.applyEditorResult(kinds);
        form.rebuildForTest();
        layout(form);
        Component n1 = findEditor(form, "-n");
        if (n1 instanceof JSpinner) {
            ((JSpinner) n1).setValue(Integer.valueOf(7));
        }
        Map<String, Schema.Option.Kind> retyped =
                new LinkedHashMap<String, Schema.Option.Kind>();
        retyped.put("-i", Schema.Option.Kind.INPUT_PATH);
        retyped.put("-n", Schema.Option.Kind.TEXT);
        retyped.put("-q", Schema.Option.Kind.SWITCH);
        form.applyEditorResult(retyped);
        form.rebuildForTest();
        layout(form);
        Component n2 = findEditor(form, "-n");
        check("the number is now a field", n2 instanceof JTextField);
        check("and it kept what was in it",
                n2 instanceof JTextField && ((JTextField) n2).getText().contains("7"));
        line("");

        line("[what the kind means for the command]");
        form.showTool(tool);
        form.applyEditorResult(kinds);
        form.rebuildForTest();
        layout(form);

        Component q = findEditor(form, "-q");
        if (q instanceof JCheckBox) {
            ((JCheckBox) q).setSelected(true);
        }
        // A path option is a field with a browse button beside it, so the thing to
        // type into is the field inside, not the container that holds both.
        JTextField pathField = fieldInside(findEditor(form, "-i"));
        check("the path option has a field to type in", pathField != null);
        if (pathField != null) {
            pathField.setText("in.fa");
        }
        Map<String, ParamValue> vals = form.collect();
        String command = bioenv.core.CommandBuilder.preview(
                tool, form.currentSchema(), "", vals, "");
        System.out.println("      command: " + command);
        check("the ticked switch is written bare", command.contains("-q"));
        check("and carries no value", !command.contains("-q true"));
        check("the path is written with its value", command.contains("-i in.fa"));
        line("");

        line("[the labels are readable, which is what the editor lists]");
        // The names TBtools gives the same controls. KindCatalogueTest checks the
        // whole set; these are the ones this test's own options use.
        check("CheckBox",
                "CheckBox".equals(SchemaFormPanel.label(Schema.Option.Kind.SWITCH)));
        check("number",
                "number".equals(SchemaFormPanel.label(Schema.Option.Kind.NUMBER)));
        check("InFilePath",
                "InFilePath".equals(SchemaFormPanel.label(Schema.Option.Kind.INPUT_PATH)));
        check("OutFilePath",
                "OutFilePath".equals(SchemaFormPanel.label(Schema.Option.Kind.OUTPUT_PATH)));
        check("Text", "Text".equals(SchemaFormPanel.label(Schema.Option.Kind.TEXT)));
        // Every label is understood back, which is how the editor reads its dropdown.
        java.util.Set<String> seen = new java.util.LinkedHashSet<String>();
        for (Schema.Option.Kind k : Schema.Option.Kind.values()) {
            String label = SchemaFormPanel.label(k);
            if (!seen.add(label)) {
                continue;
            }
            check("round trip for " + label,
                    SchemaFormPanel.kindFor(label) != null);
        }
        line("");

        line("A stated kind decides the control, and the control decides the command.");
    }

    private static Schema fixture() {
        return HelpParser.parse(
                "OPTIONS:\n"
                + " -i <file>   input\n"
                + " -n <int>    count\n"
                + " -q          quiet\n",
                "demo");
    }

    private static Component findEditor(Container c, String flag) {
        return findByName(c, flag);
    }

    /** The text field inside a control, for kinds that wrap one with their own button. */
    private static JTextField fieldInside(Component c) {
        if (c instanceof JTextField) {
            return (JTextField) c;
        }
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                JTextField found = fieldInside(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
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

    private static boolean hasBrowseButton(Container c, String flag) {
        // The path kinds wrap the field and a browse button in one container, so the
        // button is looked for inside that container rather than beside it.
        Component editor = findEditor(c, flag);
        return containsButton(editor);
    }

    private static boolean containsButton(Component c) {
        if (c instanceof javax.swing.JButton) {
            return true;
        }
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                if (containsButton(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int countChoosers(Container c) {
        int n = 0;
        for (Component child : c.getComponents()) {
            if (child instanceof javax.swing.JComboBox) {
                n++;
            }
            if (child instanceof Container) {
                n += countChoosers((Container) child);
            }
        }
        return n;
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

package bioenv.test;

import java.awt.Component;
import java.awt.Container;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import bioenv.core.HelpParser;
import bioenv.core.ParamValue;
import bioenv.core.Schema;
import bioenv.core.Tool;
import bioenv.ui.SchemaFormPanel;

/**
 * Verifies every control kind the panel offers: what it looks like, and what it
 * produces on the command line.
 *
 * <p>The names are the ones TBtools gives the same controls, so a choice made here
 * reads the same as one made there. Two are the plugin's own: a bare flag and a
 * number.
 *
 * <p>Run headless.
 *
 * <p>Run: {@code java -Djava.awt.headless=true -cp &lt;classes&gt; bioenv.test.KindCatalogueTest}
 */
public final class KindCatalogueTest {

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
        line("=== The kinds a parameter can be given ===");
        line("");

        line("[every kind has a name, and the names are TBtools']");
        Map<Schema.Option.Kind, String> expected =
                new LinkedHashMap<Schema.Option.Kind, String>();
        expected.put(Schema.Option.Kind.NONE, "None");
        expected.put(Schema.Option.Kind.INPUT_PATH, "InFilePath");
        expected.put(Schema.Option.Kind.OUTPUT_PATH, "OutFilePath");
        expected.put(Schema.Option.Kind.PATH_LIST, "FilePathList");
        expected.put(Schema.Option.Kind.DIRECTORY_PATH, "DirectoryPath");
        expected.put(Schema.Option.Kind.TEXT, "Text");
        expected.put(Schema.Option.Kind.CHOICE, "Choice");
        expected.put(Schema.Option.Kind.SWITCH, "CheckBox");
        expected.put(Schema.Option.Kind.NUMBER, "number");

        for (Map.Entry<Schema.Option.Kind, String> e : expected.entrySet()) {
            check("label of " + e.getKey() + " is " + e.getValue(),
                    e.getValue().equals(SchemaFormPanel.label(e.getKey())));
        }
        check("every kind is named",
                expected.size() == Schema.Option.Kind.values().length);
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

        line("[what each kind puts on the command line]");
        // One flag per kind, so the control each produces can be checked in isolation.
        Schema schema = HelpParser.parse(
                "OPTIONS:\n"
                + " --none <t>     a parameter with no control\n"
                + " --in <f>       an input path\n"
                + " --out <f>      an output path\n"
                + " --list <f>     several paths\n"
                + " --dir <d>      a directory\n"
                + " --text <t>     free text\n"
                + " --pick <t>     a choice\n"
                + " --flag         a bare flag\n"
                + " --num <n>      a number\n",
                "probe");
        Tool tool = new Tool("probe", "probe_env", "/envs/probe");
        tool.schema = schema;
        SchemaFormPanel form = new SchemaFormPanel();
        form.showTool(tool);
        form.setSize(1100, 800);
        layout(form);

        Map<String, Schema.Option.Kind> kinds =
                new LinkedHashMap<String, Schema.Option.Kind>();
        kinds.put("--none", Schema.Option.Kind.NONE);
        kinds.put("--in", Schema.Option.Kind.INPUT_PATH);
        kinds.put("--out", Schema.Option.Kind.OUTPUT_PATH);
        kinds.put("--list", Schema.Option.Kind.PATH_LIST);
        kinds.put("--dir", Schema.Option.Kind.DIRECTORY_PATH);
        kinds.put("--text", Schema.Option.Kind.TEXT);
        kinds.put("--pick", Schema.Option.Kind.CHOICE);
        kinds.put("--flag", Schema.Option.Kind.SWITCH);
        kinds.put("--num", Schema.Option.Kind.NUMBER);
        form.applyEditorResult(kinds);
        layout(form);

        check("None produces no field",
                findByName(form, "--none") instanceof JLabel);
        check("InFilePath produces a field", fieldFor(form, "--in") != null);
        check("OutFilePath produces a field", fieldFor(form, "--out") != null);
        check("FilePathList produces a field", fieldFor(form, "--list") != null);
        check("DirectoryPath produces a field", fieldFor(form, "--dir") != null);
        check("Text produces a field", fieldFor(form, "--text") != null);
        check("CheckBox produces a tick box",
                findByName(form, "--flag") instanceof JCheckBox);
        check("number produces a spinner",
                findByName(form, "--num") instanceof JSpinner);
        // Choice with no documented values falls back to a field, which is the
        // honest control when there is nothing to choose from.
        check("Choice with no values produces a field",
                fieldFor(form, "--pick") != null);
        line("");

        line("[every path kind gets a chooser button]");
        for (String flag : new String[]{"--in", "--out", "--list", "--dir"}) {
            Component c = findByName(form, flag);
            check(flag + " has a ... button",
                    c instanceof Container && containsButton((Container) c));
        }
        for (String flag : new String[]{"--text", "--pick"}) {
            Component c = findByName(form, flag);
            check(flag + " has no ... button",
                    !(c instanceof Container) || !containsButton((Container) c));
        }
        line("");

        line("[values reach the command under the right treatment]");
        setText(form, "--in", "C:\\data\\in.fa");
        setText(form, "--list", "C:\\a\\x.txt,C:\\b\\y.txt");
        setText(form, "--dir", "C:\\data");
        setText(form, "--text", "plain words");
        tick(form, "--flag");
        Map<String, ParamValue> vals = form.collect();
        String command = bioenv.core.CommandBuilder.preview(
                tool, form.currentSchema(), "", vals, "");
        System.out.println("      " + command);

        // A Windows path is not one the tool can open, however many are in the list.
        check("a Windows input path is translated",
                command.contains("/mnt/c/data/in.fa"));
        check("every path in a list is translated",
                command.contains("/mnt/c/a/x.txt") && command.contains("/mnt/c/b/y.txt"));
        check("a directory is translated", command.contains("/mnt/c/data"));
        check("a value that is not a path is left alone",
                command.contains("plain words"));
        check("a bare flag carries no value", command.contains("--flag")
                && !command.contains("--flag "));
        check("None contributes nothing", !command.contains("--none"));
        line("");

        line("[the output kinds are the ones whose folders get made]");
        check("OutFilePath counts", bioenv.core.CommandBuilder.declaresOutput(
                Schema.Option.Kind.OUTPUT_PATH));
        check("a list of outputs counts", bioenv.core.CommandBuilder.declaresOutput(
                Schema.Option.Kind.PATH_LIST));
        check("an input does not", !bioenv.core.CommandBuilder.declaresOutput(
                Schema.Option.Kind.INPUT_PATH));
        check("a directory does not", !bioenv.core.CommandBuilder.declaresOutput(
                Schema.Option.Kind.DIRECTORY_PATH));
        line("");

        line("[the three kinds that did nothing are gone]");
        // PasteAsInFilePath, TextAsOutFilePath and FilePathListInFile were offered
        // and behaved exactly as a plain input or output path: their point in
        // TBtools is the path-versus-contents distinction, which nothing here
        // implemented. A choice that changes only its own label is worse than no
        // choice, so they are removed. Both halves are checked - the enum name,
        // which is what a stored correction is read back through, and the label,
        // which is what the chooser lists - because either one surviving would put
        // the entry back in front of the user.
        for (String gone : new String[]{"PASTE_IN_PATH", "TEXT_OUT_PATH",
                "PATH_LIST_FILE"}) {
            boolean nameGone = true;
            for (Schema.Option.Kind k : Schema.Option.Kind.values()) {
                if (k.name().equals(gone)) {
                    nameGone = false;
                }
            }
            check(gone + " is no longer a kind", nameGone);
        }
        for (String label : new String[]{"PasteAsInFilePath", "TextAsOutFilePath",
                "FilePathListInFile"}) {
            check(label + " is no longer offered",
                    SchemaFormPanel.kindFor(label) == null);
        }
        line("");

        line("[the excluded TBtools controls are not offered]");
        for (String absent : new String[]{"Parameter", "Note", "MultiLineNote",
                "TerminalEntry", "Link", "Image", "Citation"}) {
            check(absent + " is not a kind", SchemaFormPanel.kindFor(absent) == null);
        }
        line("");

        line("Every kind has a name, a control, and a meaning on the command line.");
    }

    /** The text field inside a row, for kinds that wrap one with a button. */
    private static JTextField fieldFor(Container root, String flag) {
        Component c = findByName(root, flag);
        if (c instanceof JTextField) {
            return (JTextField) c;
        }
        if (c instanceof Container) {
            for (Component child : allOf((Container) c)) {
                if (child instanceof JTextField) {
                    return (JTextField) child;
                }
            }
        }
        return null;
    }

    private static void setText(Container root, String flag, String text) {
        JTextField f = fieldFor(root, flag);
        if (f != null) {
            f.setText(text);
        }
    }

    private static void tick(Container root, String flag) {
        Component c = findByName(root, flag);
        if (c instanceof JCheckBox) {
            ((JCheckBox) c).setSelected(true);
        }
    }

    private static boolean containsButton(Container c) {
        for (Component child : c.getComponents()) {
            if (child instanceof javax.swing.JButton) {
                return true;
            }
            if (child instanceof Container && containsButton((Container) child)) {
                return true;
            }
        }
        return false;
    }

    private static java.util.List<Component> allOf(Container c) {
        java.util.List<Component> out = new java.util.ArrayList<Component>();
        for (Component child : c.getComponents()) {
            out.add(child);
            if (child instanceof Container) {
                out.addAll(allOf((Container) child));
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

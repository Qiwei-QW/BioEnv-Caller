package bioenv.test;

import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import bioenv.core.CustomOptionStore;
import bioenv.core.HelpParser;
import bioenv.core.Schema;
import bioenv.ui.ParameterEditorPanel;

/**
 * Verifies the panel for correcting a tool's parameter list.
 *
 * <p>A tool's help is often wrong in ways the reader can see but the parser cannot:
 * an option appears only in the manual, a family is listed with one example, a line
 * of prose is read as a parameter that does not exist. The panel is where the user
 * says so, and nothing is written until OK.
 *
 * <p>Run headless.
 *
 * <p>Run: {@code java -Djava.awt.headless=true -cp &lt;classes&gt; bioenv.test.ParameterEditorTest}
 */
public final class ParameterEditorTest {

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
        line("=== Correcting a tool's parameter list ===");
        line("");

        File storeFile = tempFile();
        CustomOptionStore store = new CustomOptionStore(storeFile);

        line("[every parameter can be removed, whether it came from help or not]");
        Schema s = help();
        store.add("probe", "-x", "added by hand");
        store.applyTo(s, "probe");
        ParameterEditorPanel editor = new ParameterEditorPanel(s, "probe", store);
        layout(editor);

        // The help described three; the store added one.
        check("the documented --alpha is listed",
                findByName(editor, "bioenv.editor.remove.--alpha") != null);
        check("the documented -d is listed",
                findByName(editor, "bioenv.editor.remove.-d") != null);
        check("the added -x is listed",
                findByName(editor, "bioenv.editor.remove.-x") != null);
        line("");

        line("[a type can be changed here]");
        JComboBox<?> kind = (JComboBox<?>) findByName(editor, "bioenv.editor.kind.-d");
        check("a kind chooser is on the row", kind != null);
        if (kind != null) {
            kind.setSelectedItem("switch");
        }
        Map<String, Schema.Option.Kind> kinds = editor.chosenKinds();
        check("the change is reported", kinds.get("-d") == Schema.Option.Kind.SWITCH);
        // --alpha takes <int>, so its kind is what the help said, untouched.
        check("the others are unchanged",
                kinds.get("--alpha") == Schema.Option.Kind.NUMBER);
        line("");

        line("[nothing is written until OK]");
        // Remove a documented parameter, then look at the store before accepting.
        ((JButton) findByName(editor, "bioenv.editor.remove.-d")).doClick();
        layout(editor);
        check("the row shows as removed",
                findByName(editor, "bioenv.editor.remove.-d") == null);
        check("a keep button is offered instead",
                findByName(editor, "bioenv.editor.keep.-d") != null);
        check("the store knows nothing yet", !store.hasHidden("probe"));
        check("and the schema still has it", s.option("-d") != null);
        line("");

        line("[a removal can be taken back before OK]");
        ((JButton) findByName(editor, "bioenv.editor.keep.-d")).doClick();
        layout(editor);
        check("the row is back", findByName(editor, "bioenv.editor.remove.-d") != null);
        line("");

        line("[OK runs its handler from the button, not after the window closes]");
        // The panel is shown in a frame, and showing a frame returns at once — unlike
        // a modal dialog, which is what it used to be. A caller that waited to be
        // asked afterwards was always told no, so every button in the panel appeared
        // to do nothing at all. The handler is what makes OK mean something.
        final boolean[] ran = new boolean[1];
        ParameterEditorPanel okEditor = new ParameterEditorPanel(help(), "probe", store);
        okEditor.setAcceptHandler(new ParameterEditorPanel.AcceptHandler() {
            @Override
            public void accepted(ParameterEditorPanel ed) {
                ran[0] = true;
            }
        });
        check("it starts unaccepted", !okEditor.isAccepted());
        JButton okButton = (JButton) findByName(okEditor, "bioenv.editor.ok");
        check("the OK button is there", okButton != null);
        if (okButton != null) {
            okButton.doClick();
        }
        check("pressing it marks the editor accepted", okEditor.isAccepted());
        check("and runs the handler at once", ran[0]);

        final boolean[] ranCancel = new boolean[1];
        ParameterEditorPanel cancelEditor =
                new ParameterEditorPanel(help(), "probe", store);
        cancelEditor.setAcceptHandler(new ParameterEditorPanel.AcceptHandler() {
            @Override
            public void accepted(ParameterEditorPanel ed) {
                ranCancel[0] = true;
            }
        });
        JButton cancelButton = (JButton) findByName(cancelEditor, "bioenv.editor.cancel");
        if (cancelButton != null) {
            cancelButton.doClick();
        }
        check("cancel does not run the handler", !ranCancel[0]);
        check("and leaves it unaccepted", !cancelEditor.isAccepted());
        line("");

        line("[accepting writes every change]");
        // Remove -d again and add a new parameter, then accept.
        ((JButton) findByName(editor, "bioenv.editor.remove.-d")).doClick();
        layout(editor);
        editor.setCurrentSchema(s);
        String what = editor.applyChanges();
        System.out.println("      reported: " + what);
        check("the removal is recorded", store.hiddenFor("probe").contains("-d"));
        check("the result says what happened", what.contains("removed"));
        store.applyTo(s, "probe");
        check("and the schema no longer has it", s.option("-d") == null);
        check("the others survived",
                s.option("--alpha") != null && s.option("-x") != null);
        line("");

        line("[removals are remembered, and can be undone]");
        CustomOptionStore reloaded = new CustomOptionStore(storeFile);
        Schema s2 = help();
        reloaded.applyTo(s2, "probe");
        check("a reload still hides it", s2.option("-d") == null);
        check("and the rest is intact", s2.option("--alpha") != null);

        check("restoring reports work", reloaded.unhide("probe", "-d"));
        Schema s3 = help();
        reloaded.applyTo(s3, "probe");
        check("it comes back", s3.option("-d") != null);
        line("");

        line("[the order can be changed, and it holds]");
        List<String> wanted = new ArrayList<String>();
        wanted.add("-x");
        wanted.add("-d");
        wanted.add("--alpha");
        check("a new order is accepted", store.setOrder("probe", wanted));
        Schema s4 = help();
        store.applyTo(s4, "probe");
        List<String> got = new ArrayList<String>();
        for (Schema.Option o : s4.options) {
            got.add(o.flag);
        }
        System.out.println("      order: " + got);
        check("the order is applied", got.indexOf("-x") < got.indexOf("--alpha"));

        CustomOptionStore reloaded2 = new CustomOptionStore(storeFile);
        check("and is remembered",
                reloaded2.orderFor("probe").indexOf("-x") == 0);

        // A parameter the order does not mention still appears, after the listed ones.
        Schema s5 = help();
        s5.options.add(newOption("--later"));
        reloaded2.applyTo(s5, "probe");
        List<String> got2 = new ArrayList<String>();
        for (Schema.Option o : s5.options) {
            got2.add(o.flag);
        }
        System.out.println("      order with an extra: " + got2);
        check("an unlisted parameter is kept",
                got2.contains("--later"));
        check("and sorts after the listed ones",
                got2.indexOf("--later") > got2.indexOf("--alpha"));
        line("");

        line("[reset undoes everything for one tool]");
        check("there were edits to undo",
                store.forScope("probe").size() + store.hiddenFor("probe").size()
                        + store.orderFor("probe").size() > 0);
        check("reset reports how many", reloaded2.reset("probe") > 0);
        check("nothing is left", !reloaded2.hasEdits("probe"));
        Schema s6 = help();
        reloaded2.applyTo(s6, "probe");
        check("the help is back as it was",
                s6.option("-d") != null && s6.option("-x") == null);
        line("");

        line("[edits are scoped to one tool and subcommand]");
        store.add("probe stats", "--other", "elsewhere");
        check("the other scope is separate",
                store.forScope("probe").isEmpty() == false
                        && !store.forScope("probe stats").containsKey("-x"));
        check("a subcommand's edit does not reach the top level",
                !store.hiddenFor("probe").contains("--other"));
        line("");

        storeFile.delete();
        line("The list can be corrected, and the correction is remembered.");
    }

    private static Schema help() {
        return HelpParser.parse(
                "OPTIONS:\n"
                + " --alpha <int>   first\n"
                + " -d              a bare flag\n"
                + " -o <file>       output\n",
                "probe");
    }

    private static Schema.Option newOption(String flag) {
        Schema.Option o = new Schema.Option();
        o.flag = flag;
        o.description = "";
        return o;
    }

    private static File tempFile() {
        try {
            File f = File.createTempFile("bioenv-editor", ".properties");
            f.delete();
            return f;
        } catch (Exception e) {
            return new File(System.getProperty("java.io.tmpdir"),
                    "bioenv-editor-" + System.nanoTime() + ".properties");
        }
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

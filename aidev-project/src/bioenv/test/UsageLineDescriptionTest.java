package bioenv.test;

import java.awt.Component;
import java.awt.Container;
import java.io.File;

import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import bioenv.core.CustomOptionStore;
import bioenv.core.HelpParser;
import bioenv.core.Schema;
import bioenv.core.Tool;
import bioenv.ui.ParameterEditorPanel;
import bioenv.ui.SchemaFormPanel;

/**
 * Verifies that a description can be supplied for a parameter the help never
 * explains.
 *
 * <p>Some tools name an option only in the usage line and say nothing about it. The
 * plugin lists it, because it is real, and the tooltip says the documentation offers
 * no explanation. There is no line in the help to correct, so a description the user
 * supplies has to be kept with the edits and applied over the parse — which for such
 * a parameter produces nothing.
 *
 * <p>Run headless.
 *
 * <p>Run: {@code java -Djava.awt.headless=true -cp &lt;classes&gt; bioenv.test.UsageLineDescriptionTest}
 */
public final class UsageLineDescriptionTest {

    private static int pass;
    private static int fail;

    /** -dir is named only in the usage line; -in is documented properly. */
    private static final String HELP =
            "Usage: emboss [options] -dir <dir>\n"
            + "\n"
            + "OPTIONS:\n"
            + " -in <file>   input file\n";

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
        line("=== Describing a parameter the help does not explain ===");
        line("");

        File dir = new File(System.getProperty("java.io.tmpdir"),
                "bioenv-usagedesc-" + System.nanoTime());
        dir.mkdirs();
        CustomOptionStore store = new CustomOptionStore(
                new File(dir, "edits.properties"));

        Schema s = HelpParser.parse(HELP, "emboss");
        line("[the parameter is listed, with no description]");
        check("it was parsed from the usage line", s.option("-dir") != null);
        check("and it is marked as coming from there",
                s.option("-dir").fromUsageLine);
        check("the help gave it no description",
                s.option("-dir").description.isEmpty());
        check("a documented parameter still has its own",
                "input file".equals(s.option("-in").description));
        line("");

        line("[a description typed in the editor is applied]");
        ParameterEditorPanel ed = new ParameterEditorPanel(s, "emboss", store);
        ed.setSize(900, 600);
        layout(ed);
        JTextField field = (JTextField) findByName(ed, "bioenv.editor.desc.-dir");
        check("the row has a description field", field != null);
        if (field != null) {
            field.setText("the directory to scan");
        }
        ed.setCurrentSchema(s);
        ed.applyChanges();
        s.reapplyEdits("emboss", null, store);

        Schema.Option after = s.option("-dir");
        check("the parameter is still there", after != null);
        check("and now has the description",
                after != null && "the directory to scan".equals(after.description));
        check("the documented one is untouched",
                "input file".equals(s.option("-in").description));
        check("nothing was invented", s.options.size() == 2);
        line("");

        line("[it survives being rebuilt from the help]");
        // The schema is rebuilt from its help on every edit, so a description kept
        // only in memory would be replaced by the empty one the parse produces.
        Schema again = HelpParser.parse(HELP, "emboss");
        store.applyTo(again, "emboss");
        check("the description comes back",
                again.option("-dir") != null
                        && "the directory to scan".equals(again.option("-dir").description));
        line("");

        line("[and survives a restart, which is what the store is for]");
        CustomOptionStore reopened = new CustomOptionStore(
                new File(dir, "edits.properties"));
        Schema fresh = HelpParser.parse(HELP, "emboss");
        reopened.applyTo(fresh, "emboss");
        check("it is still there",
                fresh.option("-dir") != null
                        && "the directory to scan".equals(fresh.option("-dir").description));
        line("");

        line("[the option list shows it]");
        SchemaFormPanel form = new SchemaFormPanel();
        form.setCustomOptions(reopened);
        Tool tool = new Tool("emboss", "emboss_env", "/envs/emboss");
        Schema forForm = HelpParser.parse(HELP, "emboss");
        reopened.applyTo(forForm, "emboss");
        tool.schema = forForm;
        form.showTool(tool);
        form.setSize(900, 600);
        layout(form);
        // The field carries the description as a grey hint, which is how the option
        // list shows what a parameter does.
        JTextField inForm = (JTextField) findByName(form, "-dir");
        check("the row is in the form", inForm != null);
        check("and the description is shown as its hint",
                inForm != null && "the directory to scan".equals(hintOf(inForm)));
        line("");

        line("[a documented description is still corrected in the help]");
        // The other path: where the help does explain the parameter, the text belongs
        // in the help, so it survives a re-read as the tool's own wording would.
        Schema s2 = HelpParser.parse(HELP, "emboss");
        ParameterEditorPanel ed2 = new ParameterEditorPanel(s2, "emboss", store);
        ed2.setSize(900, 600);
        layout(ed2);
        JTextField f2 = (JTextField) findByName(ed2, "bioenv.editor.desc.-in");
        if (f2 != null) {
            f2.setText("the input proteome");
        }
        ed2.setCurrentSchema(s2);
        ed2.applyChanges();
        check("the help now carries the new text",
                s2.rawHelp.contains("the input proteome"));
        s2.reapplyEdits("emboss", null, store);
        check("and the parameter has it",
                s2.option("-in") != null
                        && "the input proteome".equals(s2.option("-in").description));
        line("");

        deleteTree(dir);
        line("A parameter the help does not explain can still be described.");
    }

    /** The grey hint a field is showing, which is where the description lives. */
    private static String hintOf(JTextField f) {
        // A field that has not been typed into shows the hint as its text, and the
        // component says so through this property.
        Object showing = f.getClientProperty("bioenv.showingHint");
        if (Boolean.TRUE.equals(showing)) {
            return f.getText();
        }
        // Older HintText implementations keep the hint in the tooltip instead.
        Object tip = f.getClientProperty("bioenv.hint");
        if (tip != null) {
            return String.valueOf(tip);
        }
        return f.getToolTipText() == null ? "" : f.getToolTipText();
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

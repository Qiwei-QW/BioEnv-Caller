package bioenv.test;

import java.awt.Component;
import java.awt.Container;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;

import bioenv.core.Schema;
import bioenv.core.Tool;
import bioenv.i18n.I18n;
import bioenv.i18n.Strings;
import bioenv.ui.SchemaFormPanel;

/**
 * Verifies the subcommand selector and the button that returns to the top level.
 *
 * <p>Two things are being checked, and they are related. The selector used to
 * carry the internal token {@code @top} as its first entry — a marker the code
 * understood and the user could not, sitting in a list they have to read. And
 * once several subcommands have been explored, getting back to the command's own
 * options meant reopening the list and finding that first entry every time.
 *
 * <p>So the entry now reads as words, and a button next to it goes back in one
 * press. The button is idle at the top level, where it would do nothing.
 *
 * <p>Run: {@code java -Djava.awt.headless=true -cp &lt;classes&gt; bioenv.test.SubcommandNavTest}
 */
public final class SubcommandNavTest {

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
        line("=== Subcommand navigation ===");
        line("");

        Schema parent = new Schema();
        parent.parsed = true;
        parent.rawHelp = "parent";
        parent.subcommands.add(new Schema.Subcommand("search", "search for information"));
        parent.subcommands.add(new Schema.Subcommand("fetch", "download data"));
        parent.subcommands.add(new Schema.Subcommand("fasta", "convert to FASTA"));

        // One subcommand carries options of its own, so switching to it has
        // something to show.
        Schema child = new Schema();
        child.parsed = true;
        child.rawHelp = "child";
        Schema.Option gene = new Schema.Option();
        gene.flag = "-gene";
        gene.arg = "<name>";
        gene.description = "gene name";
        gene.kind = Schema.Option.Kind.TEXT;
        child.options.add(gene);
        parent.subcommands.get(2).schema = child;

        Tool tool = new Tool("bio", "bio_env", "/envs/bio_env");
        tool.schema = parent;
        SchemaFormPanel form = new SchemaFormPanel();
        form.showTool(tool);
        form.setSize(900, 600);
        layout(form);

        line("[the selector reads as words]");
        JComboBox<?> box = findBox(form);
        check("the selector is present", box != null);
        if (box == null) {
            return;
        }
        check("one entry per subcommand, plus the top level",
                box.getItemCount() == 4);
        check("no internal marker is shown", !containsAt(box));
        // The entry must read as words the user can understand, rather than the
        // internal @top token. Ask I18n for the text instead of matching the English
        // wording: the default language is Chinese, so an English literal only ever
        // matched when English was forced in by hand, which is why this class was
        // never in the build.
        I18n.loadOnce();
        String topLevel = I18n.t(Strings.FORM_TOP_LEVEL);
        check("the first entry names the top level",
                String.valueOf(box.getItemAt(0)).contains(topLevel));
        check("the subcommands follow in order",
                "search".equals(box.getItemAt(1))
                        && "fetch".equals(box.getItemAt(2))
                        && "fasta".equals(box.getItemAt(3)));
        line("");

        line("[the way back is one press]");
        JButton up = findUpButton(form);
        check("the button is present", up != null);
        // The button is labelled with its own string, not the selector's top-level
        // entry: "Back to top level" versus "(top level)".
        check("it says what it does",
                up != null && up.getText().contains(I18n.t(Strings.FORM_BACK_TO_TOP)));
        check("at the top level it is idle", up != null && !up.isEnabled());
        line("");

        line("[choosing a subcommand enables it]");
        box.setSelectedItem("fasta");
        layout(form);
        JButton up2 = findUpButton(form);
        check("the button became available", up2 != null && up2.isEnabled());
        check("the selector shows the chosen subcommand",
                "fasta".equals(String.valueOf(findBox(form).getSelectedItem())));
        line("");

        line("[pressing it goes back]");
        up2.doClick();
        layout(form);
        JComboBox<?> box2 = findBox(form);
        check("the selector is back on the top level", box2 != null
                && String.valueOf(box2.getSelectedItem()).contains(topLevel));
        JButton up3 = findUpButton(form);
        check("the button is idle again", up3 != null && !up3.isEnabled());
        line("");

        line("[the selector survives a subcommand that is still loading]");
        // Opening a subcommand whose options have not been fetched yet clears the
        // current schema. The row must stay, or the way back disappears exactly
        // when the user has just gone somewhere they may want to leave.
        Schema pending = new Schema();
        pending.parsed = true;
        pending.rawHelp = "parent";
        pending.subcommands.add(new Schema.Subcommand("one", "first"));
        pending.subcommands.add(new Schema.Subcommand("two", "second"));
        Tool t2 = new Tool("demo", "demo_env", "/envs/demo_env");
        t2.schema = pending;
        SchemaFormPanel f2 = new SchemaFormPanel();
        f2.showTool(t2);
        f2.setSize(900, 600);
        layout(f2);
        JComboBox<?> b2 = findBox(f2);
        check("the selector exists before any subcommand is opened", b2 != null);
        if (b2 != null) {
            b2.setSelectedItem("one");
            layout(f2);
            check("it is still there while that subcommand loads",
                    findBox(f2) != null);
            JButton ub = findUpButton(f2);
            check("and so is the way back", ub != null && ub.isEnabled());
        }
        line("");

        line("[a single-level command shows no selector]");
        Schema flat = new Schema();
        flat.parsed = true;
        flat.rawHelp = "flat";
        Schema.Option only = new Schema.Option();
        only.flag = "-h";
        only.description = "help";
        only.kind = Schema.Option.Kind.SWITCH;
        flat.options.add(only);
        Tool t3 = new Tool("flat", "flat_env", "/envs/flat_env");
        t3.schema = flat;
        SchemaFormPanel f3 = new SchemaFormPanel();
        f3.showTool(t3);
        f3.setSize(900, 600);
        layout(f3);
        check("no selector without subcommands", findBox(f3) == null);
        check("no back button either", findUpButton(f3) == null);
        line("");

        line("Navigation reads in the user's terms and works in one press.");
    }

    private static boolean containsAt(JComboBox<?> box) {
        for (int i = 0; i < box.getItemCount(); i++) {
            if (String.valueOf(box.getItemAt(i)).contains("@")) {
                return true;
            }
        }
        return false;
    }

    /** The subcommand selector, found by name: kind selectors are combo boxes too. */
    private static JComboBox<?> findBox(Container c) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof JComboBox
                    && "bioenv.subcommand".equals(ch.getName())) {
                return (JComboBox<?>) ch;
            }
            if (ch instanceof Container) {
                JComboBox<?> r = findBox((Container) ch);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    /** The button that goes back to the top level, found by the text it shows. */
    private static JButton findUpButton(Container c) {
        // Matched on the localised label, for the same reason the checks above ask
        // I18n: the default language is Chinese.
        String label = I18n.t(Strings.FORM_BACK_TO_TOP);
        for (Component ch : c.getComponents()) {
            if (ch instanceof JButton) {
                String t = ((JButton) ch).getText();
                if (t != null && t.contains(label)) {
                    return (JButton) ch;
                }
            }
            if (ch instanceof Container) {
                JButton r = findUpButton((Container) ch);
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

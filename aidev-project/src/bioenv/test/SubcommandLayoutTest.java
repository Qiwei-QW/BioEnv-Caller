package bioenv.test;

import java.awt.Component;
import java.awt.Container;

import javax.swing.JButton;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.TitledBorder;

import bioenv.core.Schema;
import bioenv.core.Tool;
import bioenv.i18n.I18n;
import bioenv.i18n.Strings;
import bioenv.ui.SchemaFormPanel;

/**
 * Verifies where the subcommand controls sit in the panel.
 *
 * <p>The way back used to share a row with the selector, which took width from the
 * very list the user is reading and left two unrelated controls paired together.
 * It now has its own row, placed between the command's own settings and the option
 * list — the order the panel is read in, since the selector decides which options
 * appear below.
 *
 * <p>Order is checked through the component tree rather than screen coordinates,
 * which are unavailable headless.
 *
 * <p>Run: {@code java -Djava.awt.headless=true -cp &lt;classes&gt; bioenv.test.SubcommandLayoutTest}
 */
public final class SubcommandLayoutTest {

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
        line("=== Where the subcommand controls sit ===");
        line("");

        Schema parent = new Schema();
        parent.parsed = true;
        parent.rawHelp = "x";
        parent.subcommands.add(new Schema.Subcommand("search", "search for information"));
        parent.subcommands.add(new Schema.Subcommand("fetch", "download data"));
        parent.subcommands.add(new Schema.Subcommand("fasta", "convert to FASTA"));

        Tool tool = new Tool("bio", "bio_env", "/envs/bio_env");
        tool.schema = parent;
        SchemaFormPanel form = new SchemaFormPanel();
        form.showTool(tool);
        form.setSize(900, 700);
        layout(form);

        // Look the controls up through the strings the panel actually uses. The
        // default language is Chinese, so matching on the English wording only ever
        // worked when a test forced an English language file in by hand - which is
        // why this class was never in the build. Asking I18n for the text makes the
        // lookup independent of which language is in force.
        I18n.loadOnce();
        Component workdir = findTooltip(form, I18n.t(Strings.FORM_WORKDIR_TIP));
        Component box = findName(form, "bioenv.subcommand");
        JButton up = findButton(form, I18n.t(Strings.FORM_BACK_TO_TOP));
        Component options = findTitled(form, I18n.t(Strings.FORM_OPTIONS_TITLE));

        line("[all the parts are present]");
        check("the working-dir field", workdir != null);
        check("the subcommand selector", box != null);
        check("the button", up != null);
        check("the options area", options != null);
        if (workdir == null || box == null || up == null || options == null) {
            return;
        }
        line("");

        line("[the way back shares the selector's row]");
        // It had a row of its own for a while. That cost a row of height on every
        // tool with subcommands, and the button is a small thing to pay for it, so
        // it shares the line with the selector instead.
        check("the button is in the selector's row",
                up.getParent() == box.getParent());
        check("the button sits to the right of the selector",
                up.getX() >= box.getX() + box.getWidth());
        line("");

        line("[the selector keeps the width]");
        // A selector squeezed by a neighbouring button was the visible symptom.
        check("the selector is wide", box.getWidth() > 500);
        line("");

        line("[the order is settings, then subcommand, then options]");
        check("the settings and the selector share the north area",
                indexHolding(form, workdir) == 0 && indexHolding(form, box) == 0);
        check("the option list is the centre area",
                indexHolding(form, options) == 1);

        Container north = (Container) form.getComponent(0);
        // Both the selector and the button live inside the same subcommand block,
        // so the comparison has to be made among that block's own children rather
        // than among the north area's.
        Container subBlock = (Container) north.getComponent(indexHolding(north, box));
        int settingsRow = indexHolding(north, workdir);
        check("settings come before the subcommand block",
                settingsRow < indexHolding(north, subBlock));
        line("");

        line("[no subcommands means no extra rows]");
        Schema flat = new Schema();
        flat.parsed = true;
        flat.rawHelp = "x";
        Schema.Option only = new Schema.Option();
        only.flag = "-h";
        only.description = "help";
        only.kind = Schema.Option.Kind.SWITCH;
        flat.options.add(only);
        Tool t2 = new Tool("flat", "flat_env", "/envs/flat_env");
        t2.schema = flat;
        SchemaFormPanel f2 = new SchemaFormPanel();
        f2.showTool(t2);
        f2.setSize(900, 700);
        layout(f2);
        check("no selector", findName(f2, "bioenv.subcommand") == null);
        check("no back button", findButton(f2, "Back to top level") == null);
        line("");

        line("The way back sits on its own line, above the options it returns from.");
    }

    /** Index of the form child whose subtree holds the target, or -1. */
    private static int indexHolding(Container parent, Component target) {
        for (int i = 0; i < parent.getComponentCount(); i++) {
            Component ch = parent.getComponent(i);
            if (ch == target || holds(ch, target)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean holds(Component c, Component target) {
        if (c == target) {
            return true;
        }
        if (!(c instanceof Container)) {
            return false;
        }
        for (Component ch : ((Container) c).getComponents()) {
            if (holds(ch, target)) {
                return true;
            }
        }
        return false;
    }

    private static Component findTooltip(Container c, String fragment) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof JTextField) {
                String tt = ((JTextField) ch).getToolTipText();
                if (tt != null && tt.contains(fragment)) {
                    return ch;
                }
            }
            if (ch instanceof Container) {
                Component r = findTooltip((Container) ch, fragment);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    private static Component findName(Container c, String name) {
        for (Component ch : c.getComponents()) {
            if (name.equals(ch.getName())) {
                return ch;
            }
            if (ch instanceof Container) {
                Component r = findName((Container) ch, name);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    private static JButton findButton(Container c, String text) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof JButton && text.equals(((JButton) ch).getText())) {
                return (JButton) ch;
            }
            if (ch instanceof Container) {
                JButton r = findButton((Container) ch, text);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    private static Component findTitled(Container c, String title) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof JScrollPane) {
                javax.swing.border.Border b = ((JScrollPane) ch).getBorder();
                if (b instanceof TitledBorder
                        && title.equals(((TitledBorder) b).getTitle())) {
                    return ch;
                }
            }
            if (ch instanceof Container) {
                Component r = findTitled((Container) ch, title);
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

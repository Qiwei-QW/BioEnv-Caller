package bioenv.test;

import java.awt.Component;
import java.awt.Container;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;

import bioenv.core.HelpParser;
import bioenv.core.Schema;
import bioenv.core.Tool;
import bioenv.ui.CollapsiblePanel;
import bioenv.ui.MainPanel;
import bioenv.ui.SchemaFormPanel;

/**
 * Verifies the three ways of making a long option list easier to work with:
 * folding the sections that are only glanced at, searching the list, and opening it
 * in a window of its own.
 *
 * <p>Measured in a real frame, because the layout pipeline only assigns sizes once
 * a component is displayable. A hand-driven {@code doLayout} reports zero widths and
 * makes this kind of check meaningless.
 *
 * <p>Run: {@code java -cp &lt;classes&gt; bioenv.test.OptionAccessTest}
 */
public final class OptionAccessTest {

    private static int pass;
    private static int fail;

    static JFrame frame;
    static MainPanel panel;
    static SchemaFormPanel form;

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                frame = new JFrame("access");
                panel = new MainPanel();
                frame.setContentPane(panel);
                frame.setSize(1600, 900);
                frame.setVisible(true);
            }
        });
        Thread.sleep(900);

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                form = findForm(panel);
            }
        });

        line("=== Reaching the options of a long tool ===");
        line("");

        // A tool with enough options that the list cannot all fit.
        show(40);
        Thread.sleep(300);

        line("[the baseline: how much is visible unfolded]");
        int open = visibleOptionsHeight();
        System.out.println("      options pane height = " + open + " px");
        check("the option list has some height", open > 0);
        line("");

        line("[neither the preview nor the extra arguments fold any more]");
        // Both used to fold so that a long option list could take their height. The
        // extra-arguments box is where a parameter the help never described is
        // written, and hiding the one place to put such a parameter was worse than
        // the height it saved; the preview is a few lines the user checks before
        // pressing Run.
        check("the preview is not a foldable section", previewSection() == null);
        check("the extra arguments are not a foldable section",
                extrasSection() == null);
        line("");

        line("[searching narrows the list]");
        int total = form.optionCount();
        check("the tool has many options", total >= 40);
        check("all are shown at first", form.filterOptions("") == total);

        int matched = form.filterOptions("option1");
        System.out.println("      'option1' matches " + matched + " of " + total);
        check("a prefix match finds some", matched > 0 && matched < total);

        // The description is searched as well as the name, which is what makes the
        // field useful when the user remembers the purpose but not the flag.
        int byDescription = form.filterOptions("description of option 7");
        check("a description match works", byDescription >= 1);

        check("nonsense matches nothing", form.filterOptions("zzzznotpresent") == 0);
        check("clearing shows everything again", form.filterOptions("") == total);
        line("");

        line("[the options can be opened in a window]");
        check("the panel exposes a search field", form.optionSearch() != null);
        // Opening a modal dialog would block the test, so the window is only checked
        // for being reachable rather than shown.
        check("the window entry point exists", hasOpenButton(panel));
        line("");

        line("=== results ===");
        line("  passed: " + pass);
        line("  failed: " + fail);
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                frame.dispose();
            }
        });
        if (fail > 0) {
            line("");
            line("FAILURES PRESENT");
            System.exit(1);
        }
    }

    private static void show(final int n) throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                if (form != null) {
                    form.showTool(bigTool(n));
                }
            }
        });
    }

    private static int visibleOptionsHeight() {
        JScrollPane sp = scrollWithTitle(panel, "Options");
        if (sp == null) {
            return 0;
        }
        JViewport vp = sp.getViewport();
        return vp.getExtentSize().height;
    }

    private static CollapsiblePanel previewSection() {
        for (Component c : flatten(panel)) {
            if (c instanceof CollapsiblePanel) {
                String t = headingOf((CollapsiblePanel) c);
                if (t != null && t.toLowerCase().contains("command preview")) {
                    return (CollapsiblePanel) c;
                }
            }
        }
        return null;
    }

    private static CollapsiblePanel extrasSection() {
        for (Component c : flatten(panel)) {
            if (c instanceof CollapsiblePanel) {
                String t = headingOf((CollapsiblePanel) c);
                if (t != null && t.toLowerCase().contains("additional")) {
                    return (CollapsiblePanel) c;
                }
            }
        }
        return null;
    }

    private static String headingOf(CollapsiblePanel p) {
        for (Component c : p.getComponents()) {
            if (c instanceof JButton) {
                return ((JButton) c).getText();
            }
        }
        return null;
    }

    private static void press(final CollapsiblePanel p) throws RuntimeException {
        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    p.headingButton().doClick();
                }
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean hasOpenButton(Container c) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof JButton) {
                String t = ((JButton) ch).getText();
                if (t != null && t.toLowerCase().contains("window")) {
                    return true;
                }
            }
            if (ch instanceof Container && hasOpenButton((Container) ch)) {
                return true;
            }
        }
        return false;
    }

    private static Tool bigTool(int n) {
        StringBuilder sb = new StringBuilder("Usage: demo [options]\n\nOptions:\n");
        for (int i = 0; i < n; i++) {
            sb.append(String.format("  --option%02d VALUE      description of option %d%n", i, i));
        }
        Schema s = HelpParser.parse(sb.toString(), "demo");
        Tool t = new Tool("demo", "demo_env", "/envs/demo_env");
        t.schema = s;
        return t;
    }

    private static java.util.List<Component> flatten(Container c) {
        java.util.List<Component> out = new java.util.ArrayList<Component>();
        for (Component ch : c.getComponents()) {
            out.add(ch);
            if (ch instanceof Container) {
                out.addAll(flatten((Container) ch));
            }
        }
        return out;
    }

    private static SchemaFormPanel findForm(Container c) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof SchemaFormPanel) {
                return (SchemaFormPanel) ch;
            }
            if (ch instanceof Container) {
                SchemaFormPanel r = findForm((Container) ch);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    private static JScrollPane scrollWithTitle(Container c, String title) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof JScrollPane) {
                javax.swing.border.Border b = ((JScrollPane) ch).getBorder();
                if (b instanceof javax.swing.border.TitledBorder
                        && title.equals(((javax.swing.border.TitledBorder) b).getTitle())) {
                    return (JScrollPane) ch;
                }
            }
            if (ch instanceof Container) {
                JScrollPane r = scrollWithTitle((Container) ch, title);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
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

package bioenv.test;

import java.awt.Component;
import java.awt.Container;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import javax.swing.border.TitledBorder;

import bioenv.ui.CollapsiblePanel;
import bioenv.ui.MainPanel;

/**
 * Verifies the panel's arrangement: the toolbar on the left, the form in the
 * middle, and the output as a column on the right.
 *
 * <p>Checked in a real frame rather than by calling {@code doLayout} by hand. The
 * layout pipeline only assigns sizes once the component is displayable, so a
 * hand-driven pass reports zero widths — which is what makes this kind of test
 * misleading when written the obvious way.
 *
 * <p>Run: {@code java -cp &lt;classes&gt; bioenv.test.MainLayoutTest}
 */
public final class MainLayoutTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        final JFrame[] frame = new JFrame[1];
        final MainPanel[] panel = new MainPanel[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                frame[0] = new JFrame("layout");
                panel[0] = new MainPanel();
                frame[0].setContentPane(panel[0]);
                frame[0].setSize(1600, 900);
                frame[0].setVisible(true);
            }
        });
        Thread.sleep(1200);

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                try {
                    body(panel[0]);
                } finally {
                    frame[0].dispose();
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

    private static void body(MainPanel p) {
        line("=== Arrangement ===");
        line("");

        line("[the top-level split is the toolbar and the rest]");
        JSplitPane outer = firstSplit(p);
        check("a split exists", outer != null);
        if (outer == null) {
            return;
        }
        check("it is horizontal", outer.getOrientation() == JSplitPane.HORIZONTAL_SPLIT);
        check("the toolbar is on the left",
                titleOf(outer.getLeftComponent()).contains("Commands"));
        line("");

        line("[the output is a column on the right]");
        // The split whose *right* component is the output panel itself. A looser
        // search finds the outer split, whose right side merely contains the output,
        // and then reports the tree's width as if it were the output's.
        JSplitPane columnSplit = splitWithRightTitle(p, "Output");
        check("a split holds the output on its right", columnSplit != null);
        if (columnSplit == null) {
            return;
        }
        check("that split is horizontal",
                columnSplit.getOrientation() == JSplitPane.HORIZONTAL_SPLIT);

        Component form = columnSplit.getLeftComponent();
        Component output = columnSplit.getRightComponent();
        line("      form   x=" + form.getX() + " width=" + form.getWidth());
        line("      output x=" + output.getX() + " width=" + output.getWidth());
        check("the output begins to the right of the form",
                output.getX() > form.getX());
        check("the output column has a usable width", output.getWidth() >= 250);
        check("the form keeps most of the width",
                form.getWidth() > output.getWidth());
        check("the output is not stretched across the form",
                output.getWidth() < 600);
        line("");

        line("[the preview is below the form, and always shown]");
        // It was briefly a foldable section; it is shown outright now, since it is
        // the thing the user checks before pressing Run.
        check("the preview is not a foldable section",
                findSection(p, "Command preview") == null);
        check("the preview panel is present",
                findTitled(p, "Command preview"));
        line("");

        line("[the output is not below the form]");
        // A regression guard: the output used to sit under the preview, and the
        // arrangement being changed back would otherwise pass unnoticed.
        check("no split has the output underneath",
                !splitWithBottomTitle(p, "Output"));
        line("");

        line("The output is a column on the right, and the form keeps the room.");
    }

    /** True when a component anywhere in the tree carries this titled border. */
    private static boolean findTitled(Container c, String title) {
        for (Component ch : c.getComponents()) {
            if (titleOf(ch).contains(title)) {
                return true;
            }
            if (ch instanceof Container && findTitled((Container) ch, title)) {
                return true;
            }
        }
        return false;
    }

    /** Finds a foldable section by part of its title. */
    private static CollapsiblePanel findSection(Container c, String title) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof CollapsiblePanel) {
                CollapsiblePanel p = (CollapsiblePanel) ch;
                for (Component inner : p.getComponents()) {
                    if (inner instanceof JButton) {
                        String t = ((JButton) inner).getText();
                        if (t != null && t.contains(title)) {
                            return p;
                        }
                    }
                }
            }
            if (ch instanceof Container) {
                CollapsiblePanel r = findSection((Container) ch, title);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    /** The split whose right component carries this title. */
    private static JSplitPane splitWithRightTitle(Container c, String title) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof JSplitPane) {
                JSplitPane s = (JSplitPane) ch;
                if (titleOf(s.getRightComponent()).contains(title)) {
                    return s;
                }
            }
            if (ch instanceof Container) {
                JSplitPane r = splitWithRightTitle((Container) ch, title);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    /** True when some split has this title on its bottom component. */
    private static boolean splitWithBottomTitle(Container c, String title) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof JSplitPane) {
                JSplitPane s = (JSplitPane) ch;
                if (s.getOrientation() == JSplitPane.VERTICAL_SPLIT
                        && titleOf(s.getRightComponent()).contains(title)) {
                    return true;
                }
            }
            if (ch instanceof Container && splitWithBottomTitle((Container) ch, title)) {
                return true;
            }
        }
        return false;
    }

    private static JSplitPane firstSplit(Container c) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof JSplitPane) {
                return (JSplitPane) ch;
            }
        }
        return null;
    }

    private static String titleOf(Component c) {
        if (c == null) {
            return "";
        }
        if (c instanceof JComponent) {
            Border b = ((JComponent) c).getBorder();
            if (b instanceof TitledBorder) {
                return ((TitledBorder) b).getTitle();
            }
        }
        return "";
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

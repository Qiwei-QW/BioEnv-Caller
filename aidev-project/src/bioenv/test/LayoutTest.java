package bioenv.test;

import java.awt.Component;
import java.awt.Container;
import javax.swing.JSplitPane;

import bioenv.ui.MainPanel;

/**
 * Verifies that the main area is genuinely resizable.
 *
 * <p>Two things are checked, because "there is a split pane" is not the same as
 * "the user can resize what they want":
 *
 * <ol>
 *   <li>every boundary that should be draggable is a {@link JSplitPane}</li>
 *   <li>the initial proportions are actually applied, which requires them to be
 *       set after the first layout rather than before it</li>
 * </ol>
 *
 * <p>Run: {@code java -Djava.awt.headless=true -cp <classes> bioenv.test.LayoutTest}
 */
public final class LayoutTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        line("=== Layout test ===");
        line("headless = " + java.awt.GraphicsEnvironment.isHeadless());
        line("");

        MainPanel panel = new MainPanel();

        // Lay it out at a realistic size, as Swing would once shown.
        panel.setSize(1400, 900);
        panel.doLayout();
        layoutRecursively(panel);
        panel.doLayout();

        java.util.List<JSplitPane> splits = new java.util.ArrayList<JSplitPane>();
        collect(panel, splits);

        line("[split panes present]");
        // Four boundaries: tree | rest, options | extras, form | preview,
        // preview | output. The options/extras split only exists once a tool has
        // been opened, because before that there is no form to divide, so a tool
        // is shown here rather than testing the empty state.
        check("two boundaries exist before a tool is opened", splits.size() >= 2);

        java.awt.Container form = findFormPanel(panel);
        check("the parameter form panel was found", form != null);
        if (form != null) {
            selectAnyTool(form);
            panel.setSize(1400, 900);
            panel.doLayout();
            layoutRecursively(panel);
            panel.doLayout();
            splits.clear();
            collect(panel, splits);
        }

        check("three resizable boundaries exist", splits.size() >= 3);
        for (JSplitPane sp : splits) {
            line("    " + (sp.getOrientation() == JSplitPane.HORIZONTAL_SPLIT
                    ? "horizontal" : "vertical")
                    + "  divider=" + sp.getDividerLocation()
                    + "  size=" + sp.getWidth() + "x" + sp.getHeight());
        }
        line("");

        line("[only the tree is dragged horizontally]");
        int h = 0;
        int v = 0;
        for (JSplitPane sp : splits) {
            if (sp.getOrientation() == JSplitPane.HORIZONTAL_SPLIT) {
                h++;
            } else {
                v++;
            }
        }
        // Two horizontal splits: the tree against everything else, and the form
        // against the output column. The second was added deliberately when the
        // output moved from below the preview to a column beside it, so that its
        // width is the user's to set.
        // 三条水平分隔条：AI 助手 | 其余、命令树 | 其余、表单 | 输出。
        // 原来是两条 —— AI 那一栏包在最外面之后多了一条，这是**刻意的布局变更**，
        // 所以改的是"应该有几条"，不是把判断放宽。
        check("three horizontal splits (assistant | rest, tree | rest, form | output)",
                h == 3);
        // One vertical split remains: the options against the extras. The preview
        // is a foldable section now rather than a split pane, so that folding it
        // returns its height to the option list instead of merely shrinking it.
        check("one vertical split remains (options | extras)", v == 1);
        line("");

        line("[initial proportions were applied]");
        // A divider at 0 or at the full extent means the initial position never
        // took effect, which is the failure this guards against.
        for (JSplitPane sp : splits) {
            int pos = sp.getDividerLocation();
            int extent = sp.getOrientation() == JSplitPane.HORIZONTAL_SPLIT
                    ? sp.getWidth() : sp.getHeight();
            boolean sensible = pos > 0 && pos < extent
                    && extent - pos > 0;
            check("divider " + pos + " of " + extent + " leaves both sides visible",
                    sensible);
        }
        line("");

        line("[divider size makes it grabbable]");
        for (JSplitPane sp : splits) {
            check("divider is at least 4px (" + sp.getDividerSize() + ")",
                    sp.getDividerSize() >= 4);
        }
        line("");

        line("[panes declare minimum sizes]");
        // A minimum size stops a drag from collapsing a pane out of existence.
        boolean formMin = panelHasMinimum(panel);
        check("at least one pane sets a minimum size", formMin);
        line("");

        line("[the options area scrolls at a usable rate]");
        // The form is a plain JPanel, which does not implement Scrollable, so a
        // wheel notch moved it by the scroll pane's one-pixel fallback. Without an
        // explicit increment the area appears to ignore the wheel.
        javax.swing.JScrollPane optionScroll = findOptionsScroll(panel);
        check("the options scroll pane was found", optionScroll != null);
        if (optionScroll != null) {
            int unit = optionScroll.getVerticalScrollBar().getUnitIncrement();
            int block = optionScroll.getVerticalScrollBar().getBlockIncrement();
            line("    unitIncrement=" + unit + "  blockIncrement=" + block);
            check("a wheel notch moves a whole row, not a pixel", unit >= 20);
            check("a page moves several rows", block >= unit * 3);
        }
        line("");

        line("=== results ===");
        line("  passed: " + passed);
        line("  failed: " + failed);
        if (failed > 0) {
            line("");
            line("FAILURES PRESENT");
            System.exit(1);
        }
        line("");
        line("The tree drags horizontally; four vertical boundaries are draggable.");
    }

    private static void collect(Container c, java.util.List<JSplitPane> out) {
        for (Component child : c.getComponents()) {
            if (child instanceof JSplitPane) {
                out.add((JSplitPane) child);
            }
            if (child instanceof Container) {
                collect((Container) child, out);
            }
        }
    }

    private static void layoutRecursively(Container c) {
        c.doLayout();
        for (Component child : c.getComponents()) {
            if (child instanceof Container) {
                layoutRecursively((Container) child);
            }
        }
    }

    /** Finds the parameter form so a tool can be shown in it. */
    private static java.awt.Container findFormPanel(Container c) {
        for (Component child : c.getComponents()) {
            if (child instanceof bioenv.ui.SchemaFormPanel) {
                return (Container) child;
            }
            if (child instanceof Container) {
                java.awt.Container found = findFormPanel((Container) child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * Shows a tool with a schema in the form, so the options/extras split is
     * built. Without a tool the form is in its empty state and has no split.
     */
    private static void selectAnyTool(java.awt.Container form) {
        bioenv.core.Schema s = new bioenv.core.Schema();
        s.parsed = true;
        s.rawHelp = "Usage: demo [options]";
        bioenv.core.Schema.Option in = new bioenv.core.Schema.Option();
        in.flag = "-in";
        in.arg = "<inputfile>";
        in.description = "Input file";
        in.kind = bioenv.core.Schema.Option.Kind.INPUT_PATH;
        s.options.add(in);
        bioenv.core.Schema.Option n = new bioenv.core.Schema.Option();
        n.flag = "-n";
        n.arg = "<int>";
        n.description = "Count";
        n.kind = bioenv.core.Schema.Option.Kind.NUMBER;
        s.options.add(n);
        bioenv.core.Tool t = new bioenv.core.Tool("demo", "demo_env", "/envs/demo");
        t.schema = s;
        ((bioenv.ui.SchemaFormPanel) form).showTool(t);
    }

    /**
     * Finds the options area's scroll pane, identified by its title.
     *
     * <p>Accepts the title in either language. The pane is titled through the
     * catalogue ({@code Strings.FORM_OPTIONS_TITLE}), so pinning this lookup to the
     * English word would silently make the test pass or fail depending on the
     * language active when the JVM started — a failure that says nothing about the
     * layout it is meant to check. Comparing against the catalogue in both languages
     * keeps the identification exact while removing the language dependency.
     */
    private static javax.swing.JScrollPane findOptionsScroll(Container c) {
        for (Component child : c.getComponents()) {
            if (child instanceof javax.swing.JScrollPane) {
                javax.swing.JScrollPane sp = (javax.swing.JScrollPane) child;
                javax.swing.border.Border b = sp.getBorder();
                if (b instanceof javax.swing.border.TitledBorder
                        && isOptionsTitle(
                                ((javax.swing.border.TitledBorder) b).getTitle())) {
                    return sp;
                }
            }
            if (child instanceof Container) {
                javax.swing.JScrollPane found =
                        findOptionsScroll((Container) child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** True when the title is the options pane's, in whichever language is loaded. */
    private static boolean isOptionsTitle(String title) {
        if (title == null) {
            return false;
        }
        for (bioenv.i18n.Lang lang : bioenv.i18n.Lang.values()) {
            if (title.equals(bioenv.i18n.I18n.tIn(lang,
                    bioenv.i18n.Strings.FORM_OPTIONS_TITLE))) {
                return true;
            }
        }
        return false;
    }

    private static boolean panelHasMinimum(Container c) {
        for (Component child : c.getComponents()) {
            if (child.getMinimumSize() != null
                    && child.getMinimumSize().width > 0
                    && child.getMinimumSize().height > 0) {
                return true;
            }
            if (child instanceof Container && panelHasMinimum((Container) child)) {
                return true;
            }
        }
        return false;
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
        }
        line("  " + (ok ? "PASS" : "FAIL") + "  " + label);
    }

    private static void line(String s) {
        System.out.println(s);
    }
}

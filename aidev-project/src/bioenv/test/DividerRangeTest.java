package bioenv.test;

import java.awt.Component;
import java.awt.Container;
import javax.swing.JSplitPane;

import bioenv.ui.MainPanel;

/**
 * Verifies the tree divider stays movable across window widths.
 *
 * <p>The reported problem was that the divider sat against one edge and would not
 * move. The cause is a split pane clamping a divider to the bounds implied by
 * both panes' minimum sizes: if either minimum is large, a narrow window leaves
 * no travel. The fix is only correct if it holds at narrow widths as well as
 * wide ones, so every width is checked.
 *
 * <p>Run: {@code java -Djava.awt.headless=true -cp <classes> bioenv.test.DividerRangeTest}
 */
public final class DividerRangeTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        line("=== Divider range across window widths ===");
        line("");

        int[] widths = {760, 900, 1100, 1400, 1900};

        line("[the tree divider must move in both directions]");
        for (int w : widths) {
            MainPanel panel = new MainPanel();
            panel.setSize(w, 800);
            layout(panel);

            JSplitPane split = findHorizontal(panel);
            if (split == null) {
                fail("no horizontal split at width " + w);
                continue;
            }
            int extent = split.getWidth();
            int divider = split.getDividerLocation();
            int minLeft = minOf(split.getLeftComponent(), true);
            int minRight = minOf(split.getRightComponent(), true);
            int maxDivider = extent - split.getDividerSize() - minRight;

            // "Movable" means there is travel on both sides of where it sits.
            boolean canGoLeft = divider > minLeft;
            boolean canGoRight = divider < maxDivider;
            line(String.format("    width=%-5d divider=%-5d range=[%d..%d] "
                    + "left=%d right=%d", w, divider, minLeft, maxDivider,
                    divider - minLeft, maxDivider - divider));
            check("width " + w + ": room to drag left", canGoLeft);
            check("width " + w + ": room to drag right", canGoRight);
        }
        line("");

        line("[the tree keeps a sensible starting width]");
        for (int w : widths) {
            MainPanel panel = new MainPanel();
            panel.setSize(w, 800);
            layout(panel);
            JSplitPane split = findHorizontal(panel);
            if (split == null) {
                continue;
            }
            int divider = split.getDividerLocation();
            // Narrow enough to leave room for the form, wide enough to read names.
            check("width " + w + ": tree starts between 120 and 400 px (got "
                    + divider + ")", divider >= 120 && divider <= 400);
        }
        line("");

        line("[the right side is never squeezed out]");
        for (int w : widths) {
            MainPanel panel = new MainPanel();
            panel.setSize(w, 800);
            layout(panel);
            JSplitPane split = findHorizontal(panel);
            if (split == null) {
                continue;
            }
            int rightWidth = split.getWidth() - split.getDividerLocation()
                    - split.getDividerSize();
            check("width " + w + ": right side keeps >= 300 px (got "
                    + rightWidth + ")", rightWidth >= 300);
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
        line("The tree divider stays movable at every window width tested.");
    }

    private static JSplitPane findHorizontal(Container c) {
        for (Component child : c.getComponents()) {
            if (child instanceof JSplitPane
                    && ((JSplitPane) child).getOrientation()
                        == JSplitPane.HORIZONTAL_SPLIT) {
                return (JSplitPane) child;
            }
            if (child instanceof Container) {
                JSplitPane found = findHorizontal((Container) child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static int minOf(Component c, boolean horizontal) {
        if (c == null) {
            return 0;
        }
        java.awt.Dimension d = c.getMinimumSize();
        return horizontal ? d.width : d.height;
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
            passed++;
        } else {
            failed++;
        }
        line("  " + (ok ? "PASS" : "FAIL") + "  " + label);
    }

    private static void fail(String why) {
        failed++;
        line("  FAIL  " + why);
    }

    private static void line(String s) {
        System.out.println(s);
    }
}

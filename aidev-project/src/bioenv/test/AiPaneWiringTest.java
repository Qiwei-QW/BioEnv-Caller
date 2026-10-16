package bioenv.test;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;

import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import bioenv.i18n.I18n;
import bioenv.i18n.Strings;
import bioenv.ui.AiAssistPanel;
import bioenv.ui.MainPanel;

/**
 * Checks that the assistant pane's parts are actually wired into the component tree.
 *
 * <p>This exists because of a bug that every other test in the suite passed through.
 * The pane kept its content in a container that was created and written into but
 * never added to the pane. A component with no parent has no size, is never
 * displayable, and everything put inside it is invisible — so the pane showed
 * nothing at all, not the loading line, not the fallback text when the browser is
 * unavailable, not the page. Seventy-six checks passed on a pane that displayed
 * nothing, over six rounds of looking for the fault in the browser.
 *
 * <p>So the first two checks here are the ones that would have ended it in minutes:
 * the pane's centre slot is filled, and the sentence the constructor puts there can
 * be reached by walking down from the window's own panel. Both are structural: no
 * layout pass, no display and no browser needed, which is what makes them cheap
 * enough to run everywhere.
 *
 * <p>The last check covers a separate fault with the same flavour — the preview
 * pane stopped updating after a change that looked unrelated. Its text is written
 * by {@code setText}, which the document is emptied and refilled from offset zero,
 * so an edit filter that guards the start of the text silently swallowed the
 * plugin's own refreshes. A single write cannot show that; the check writes twice
 * and requires the second to take.
 *
 * <p>Run: {@code java -Djava.awt.headless=true -cp &lt;classes&gt; bioenv.test.AiPaneWiringTest}
 */
public final class AiPaneWiringTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        line("=== The assistant pane is wired into the tree ===");
        line("");

        final MainPanel[] holder = new MainPanel[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                holder[0] = new MainPanel();
            }
        });
        MainPanel root = holder[0];

        line("[the pane and its content container]");
        AiAssistPanel ai = find(root, AiAssistPanel.class);
        check("the assistant pane is reachable from the panel root", ai != null);

        if (ai != null) {
            // The bug in one assertion: body was built, written into, and never added.
            Component centre = ((BorderLayout) ai.getLayout())
                    .getLayoutComponent(BorderLayout.CENTER);
            check("its content container is in the centre slot", centre != null);
            check("that container's parent is the pane itself",
                    centre != null && centre.getParent() == ai);
        } else {
            check("its content container is in the centre slot", false);
            check("that container's parent is the pane itself", false);
        }

        // And the same fault seen from the outside: what the pane shows must be
        // findable by walking down, not merely constructed.
        check("the sentence the pane opens with is reachable in the tree",
                findText(root, I18n.t(Strings.AI_LOADING)) != null);
        line("");

        line("[the preview takes the plugin's own writes]");
        JTextArea preview = findByTooltip(root, I18n.t(Strings.HINT_COMMAND_AREA_LONG));
        check("the command preview is findable", preview != null);
        if (preview != null) {
            preview.setText("first");
            preview.setText("second");
            check("writing it twice leaves the second write in place",
                    "second".equals(preview.getText()));
            preview.setText("");
        } else {
            check("writing it twice leaves the second write in place", false);
        }
        line("");

        line(pass + " passed, " + fail + " failed");
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------

    private static <T extends Component> T find(Container c, Class<T> type) {
        if (type.isInstance(c)) {
            return type.cast(c);
        }
        Component[] kids = c.getComponents();
        for (int i = 0; i < kids.length; i++) {
            if (kids[i] instanceof Container) {
                T hit = find((Container) kids[i], type);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    /** 找那个提示文字 —— 它在树里，就说明装它的容器挂上了。 */
    private static Component findText(Container c, String text) {
        if (c instanceof javax.swing.JLabel && text.equals(((javax.swing.JLabel) c).getText())) {
            return c;
        }
        if (c instanceof javax.swing.text.JTextComponent
                && text.equals(((javax.swing.text.JTextComponent) c).getText())) {
            return c;
        }
        Component[] kids = c.getComponents();
        for (int i = 0; i < kids.length; i++) {
            if (kids[i] instanceof Container) {
                Component hit = findText((Container) kids[i], text);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    /** 命令预览框靠悬停提示认出来 —— 它是这一栏里唯一挂那句话的文本区。 */
    private static JTextArea findByTooltip(Container c, String tip) {
        if (c instanceof JTextArea && tip.equals(((JTextArea) c).getToolTipText())) {
            return (JTextArea) c;
        }
        Component[] kids = c.getComponents();
        for (int i = 0; i < kids.length; i++) {
            if (kids[i] instanceof Container) {
                JTextArea hit = findByTooltip((Container) kids[i], tip);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + label);
        } else {
            fail++;
            System.out.println("  FAIL  " + label);
        }
    }

    private static void line(String s) {
        System.out.println(s);
    }
}

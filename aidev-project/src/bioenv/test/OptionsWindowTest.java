package bioenv.test;

import java.awt.Component;
import java.awt.Frame;
import java.awt.Window;
import java.awt.Container;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import bioenv.core.Schema;
import bioenv.core.Tool;
import bioenv.ui.MainPanel;
import bioenv.ui.SchemaFormPanel;

/**
 * Verifies the window that opens for the options, and the placement of the button
 * that returns to the top level.
 *
 * <p>The window has to carry the system's maximise control. It used to be a dialog,
 * which has only a close button, so it could not be made full screen at all — the
 * frame's type was the limitation, not its size.
 *
 * <p>Needs a real display: it opens windows and asserts they are visible and that
 * closing disposes of them, which is the whole point of the checks. Headless it
 * reports a skip and exits 0, so it can be run in a batch that may or may not have
 * a display without the result being read as a failure.
 *
 * <p>Run: {@code java -cp &lt;classes&gt; bioenv.test.OptionsWindowTest}
 */
public final class OptionsWindowTest {

    private static int pass;
    private static int fail;

    static JFrame frame;
    static MainPanel panel;
    static SchemaFormPanel form;

    public static void main(String[] args) throws Exception {
        // This class tests that a window is really shown, so it needs a display.
        // Report a skip rather than crashing with HeadlessException: a batch run on
        // a machine without a display should not read as a failure, and a silent
        // pass would hide that the check never ran.
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            line("SKIP  OptionsWindowTest needs a display (no headless run possible)");
            line("      It checks that the options window is visible and that closing");
            line("      disposes of it; neither can be observed without a display.");
            return;
        }
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                frame = new JFrame("host");
                panel = new MainPanel();
                frame.setContentPane(panel);
                frame.setSize(1500, 900);
                frame.setVisible(true);
            }
        });
        Thread.sleep(800);
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                form = findForm(panel);
                showTool();
            }
        });
        Thread.sleep(300);

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                body();
            }
        });

        line("");
        line("  passed: " + pass + "   failed: " + fail);
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

    private static void body() {
        line("=== Reaching the options ===");
        line("");

        line("[the way back shares the row with the selector]");
        JComboBox<?> selector = (JComboBox<?>) findByName(panel, "bioenv.subcommand");
        JButton up = findButton(panel, "Back to top level");
        check("the selector exists", selector != null);
        check("the way back exists", up != null);
        if (selector != null && up != null) {
            // Same row container: sharing it is the point, since a row of its own
            // costs height on every tool that has subcommands.
            check("they share a row", selector.getParent() == up.getParent());
            check("the button is to the right of the selector",
                    up.getX() > selector.getX());
            check("the selector is still wide", selector.getWidth() > 150);
        }
        line("");

        line("[the options window can be made full screen]");
        check("the panel offers a window", hasOpenButton(panel));
        // Open it and inspect the window the panel created.
        form.openOptionsInWindow();
        Frame w = findOptionsWindow();
        check("a window was opened", w != null);
        if (w != null) {
            // A frame carries the system maximise control; a dialog does not, and a
            // dialog is what this used to be.
            check("it is a frame, so it can be maximised",
                    w instanceof JFrame);
            check("it is resizable", w.isResizable());
            check("it is not modal", true);   // a Frame has no modality
            check("it opens at a generous size",
                    w.getWidth() >= 900 && w.getHeight() >= 600);
            // No in-window duplicates of what the title bar already offers: the
            // system supplies minimise, maximise and close on a frame.
            check("no Full screen button (the title bar has one)",
                    findButton(w, "Full screen") == null);
            check("no Close button (the title bar has one)",
                    findButton(w, "Close") == null);
            // Hidden rather than removed: the toolbar travels with the option list,
            // so the button has to come back when the list does.
            JButton opener = findButton(w, "Open in a window");
            check("the open-in-a-window button is hidden inside that window",
                    opener == null || !opener.isVisible());

            // The command is shown and editable, with a way to run it.
            Component preview = findByName(w, SchemaFormPanel.WINDOW_PREVIEW_NAME);
            check("the command is shown in it", preview != null);
            check("the command can be edited",
                    preview instanceof javax.swing.JTextArea
                            && ((javax.swing.JTextArea) preview).isEditable());
            check("a run button is offered",
                    findButton(w, "Run this command") != null);

            form.closeOptionsWindowForTest();
            check("closing disposes of it", !w.isDisplayable());
        }
        line("");

        line("The options open in a window that fills the screen on request.");
    }

    private static void showTool() {
        Schema s = new Schema();
        s.parsed = true;
        s.rawHelp = "x";
        for (int i = 0; i < 20; i++) {
            Schema.Option o = new Schema.Option();
            o.flag = "--option" + i;
            o.description = "description " + i;
            o.kind = Schema.Option.Kind.TEXT;
            s.options.add(o);
        }
        s.subcommands.add(new Schema.Subcommand("alpha", "first"));
        s.subcommands.add(new Schema.Subcommand("beta", "second"));
        Tool t = new Tool("demo", "demo_env", "/envs/demo_env");
        t.schema = s;
        form.showTool(t);
    }

    private static Frame findOptionsWindow() {
        for (Window win : Window.getWindows()) {
            if (win.isVisible() && win != frame && win instanceof Frame
                    && !(win instanceof java.awt.Dialog)) {
                String title = ((Frame) win).getTitle();
                if (title != null && title.startsWith("Options")) {
                    return (Frame) win;
                }
            }
        }
        return null;
    }

    private static boolean hasOpenButton(Container c) {
        return findButton(c, "Open in a window") != null;
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

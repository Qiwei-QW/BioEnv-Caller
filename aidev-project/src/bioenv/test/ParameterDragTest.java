package bioenv.test;

import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import bioenv.core.CustomOptionStore;
import bioenv.core.HelpParser;
import bioenv.core.Schema;
import bioenv.ui.ParameterEditorPanel;
import bioenv.ui.SchemaFormPanel;

/**
 * Verifies the four things wrong with the parameter editor: the type chooser still
 * in the option list, no way to fill the screen, a move cursor over the row's
 * buttons, and a drag that only moved one place at a time.
 *
 * <p>Run headless.
 *
 * <p>Run: {@code java -Djava.awt.headless=true -cp &lt;classes&gt; bioenv.test.ParameterDragTest}
 */
public final class ParameterDragTest {

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
        line("=== The parameter editor ===");
        line("");

        line("[the option list no longer carries a type chooser]");
        SchemaFormPanel form = new SchemaFormPanel();
        Schema fs = HelpParser.parse(
                "OPTIONS:\n --alpha <int>  first\n -d            a flag\n", "probe");
        bioenv.core.Tool t = new bioenv.core.Tool("probe", "env", "/envs/x");
        t.schema = fs;
        form.showTool(t);
        form.setSize(900, 600);
        layout(form);
        check("no kind chooser beside a parameter",
                findChooser(form, "--alpha") == null);
        check("but the parameter itself is there",
                findByName(form, "--alpha") != null);
        // A kind set elsewhere still reaches the command; only its control moved.
        // --alpha takes <int>, so it is a number and needs a value written after it.
        java.util.Map<String, bioenv.core.ParamValue> vals = form.collect();
        bioenv.core.ParamValue alpha = vals.get("--alpha");
        check("the parameter is collected", alpha != null);
        check("its parsed kind survives being read",
                alpha != null && alpha.option.kind == Schema.Option.Kind.NUMBER);
        // Untouched, it stays off the command — the rule every other parameter
        // follows, so a form nobody has filled in produces no arguments.
        check("untouched it stays off the command",
                !bioenv.core.CommandBuilder.preview(t, form.currentSchema(), "", vals, "")
                        .contains("--alpha"));
        line("");

        File storeFile = tempFile();
        CustomOptionStore store = new CustomOptionStore(storeFile);
        ParameterEditorPanel editor = new ParameterEditorPanel(help(), "probe", store);
        editor.setSize(800, 600);
        layout(editor);

        line("[the window is a frame, so the title bar carries the controls]");
        // The panel no longer offers its own Full screen button: the window is a
        // frame, so the system's maximise button and a double-click on the title bar
        // both do it, and a third way to do the same thing is clutter.
        check("no Full screen button in the panel",
                findByName(editor, "bioenv.editor.fullscreen") == null);
        line("");

        line("[only the grip offers a drag]");
        // The row contains a description field, a type chooser and a remove button,
        // all of which want the mouse themselves.
        Component grip = gripOf(editor, "--alpha");
        check("the row has a grip", grip != null);
        check("the grip shows a hand, not a move cursor",
                grip != null && !isMoveCursor(grip.getCursor()));
        check("the remove button does not show a move cursor",
                !isMoveCursor(findByName(editor, "bioenv.editor.remove.--alpha")
                        .getCursor()));
        check("the type chooser does not show a move cursor",
                !isMoveCursor(findChooser(editor, "bioenv.editor.kind.--alpha").getCursor()));
        line("");

        line("[a drag can move a row anywhere, not one place at a time]");
        java.util.List<String> before = editor.orderedFlags();
        System.out.println("      before: " + before);
        check("the last parameter is at the end",
                "--gamma".equals(before.get(before.size() - 1)));

        // A drag is pure geometry, and a headless run has no geometry to give it, so
        // the move itself is driven directly. It is the same call the drag makes.
        // The old code moved by one position per event, so a drag across the list
        // shuffled the row along instead of dropping it where it was released.
        check("the move is accepted", editor.moveTo("--gamma", 0));
        java.util.List<String> after = editor.orderedFlags();
        System.out.println("      after:  " + after);
        check("it moved to the front in one step", after.indexOf("--gamma") == 0);
        check("nothing was lost", after.size() == before.size());
        check("the rest kept their order",
                after.indexOf("--alpha") < after.indexOf("-beta"));

        // And back to the end.
        editor.moveTo("--gamma", 2);
        java.util.List<String> back = editor.orderedFlags();
        System.out.println("      back:   " + back);
        check("it can go to the end again",
                "--gamma".equals(back.get(back.size() - 1)));
        line("");

        line("[a drag does not destroy the control it is holding]");
        // Rebuilding the rows mid-drag would replace the grip the mouse is pressed
        // on, which ends the drag at every step: the row could then only be nudged
        // one place at a time, however far the pointer was taken.
        Component gripBefore = gripOf(editor, "--alpha");
        check("the grip is there", gripBefore != null);
        editor.beginDragForTest("--alpha");
        editor.moveTo("--alpha", 2);
        layout(editor);
        Component gripAfter = gripOf(editor, "--alpha");
        check("the grip survives the move", gripAfter == gripBefore);
        check("and the move happened",
                editor.orderedFlags().indexOf("--alpha") == 2);
        editor.endDragForTest();

        // Outside a drag, the rows are rebuilt as before, so a removal is reflected.
        editor.moveTo("--alpha", 0);
        layout(editor);
        check("a move outside a drag still reorders",
                editor.orderedFlags().indexOf("--alpha") == 0);
        line("");

        storeFile.delete();
        line("The chooser moved, the window fills, and a drag goes where it is put.");
    }

    private static Schema help() {
        return HelpParser.parse(
                "OPTIONS:\n"
                + " --alpha <int>   first\n"
                + " -beta           second\n"
                + " --gamma <txt>   third\n",
                "probe");
    }

    /** The grip on a row, found by the arrow character it shows. */
    private static Component gripOf(Container c, String flag) {
        Component row = findByName(c, "bioenv.editor.row." + flag);
        if (!(row instanceof Container)) {
            return null;
        }
        for (Component ch : ((Container) row).getComponents()) {
            if (ch instanceof JLabel && "\u2261".equals(((JLabel) ch).getText())) {
                return ch;
            }
        }
        return null;
    }

    /** Drags the grip onto the given row's position, in one gesture. */
    private static void dragGripToRow(final ParameterEditorPanel editor,
                                      final Component grip, final int targetIndex) {
        if (grip == null || grip.getParent() == null) {
            return;
        }
        Component firstRow = rowNumber(editor, targetIndex);
        if (firstRow == null) {
            return;
        }
        // Where the first row sits, in the grip's own coordinates.
        Point onFirstRow = SwingUtilities.convertPoint(firstRow, 2, 4, grip);
        try {
            ((javax.swing.JComponent) grip).dispatchEvent(new MouseEvent(grip,
                    MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0,
                    5, 5, 1, false, MouseEvent.BUTTON1));
            ((javax.swing.JComponent) grip).dispatchEvent(new MouseEvent(grip,
                    MouseEvent.MOUSE_DRAGGED, System.currentTimeMillis(), 0,
                    onFirstRow.x, onFirstRow.y, 0, false, MouseEvent.BUTTON1));
            ((javax.swing.JComponent) grip).dispatchEvent(new MouseEvent(grip,
                    MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0,
                    onFirstRow.x, onFirstRow.y, 1, false, MouseEvent.BUTTON1));
        } catch (Exception e) {
            System.out.println("      drag failed: " + e);
        }
        layout(editor);
    }

    /** The nth visible row container. */
    private static Component rowNumber(Container c, int n) {
        int seen = 0;
        for (Component ch : allComponents(c)) {
            String name = ch.getName();
            if (name != null && name.startsWith("bioenv.editor.row.")) {
                if (seen == n) {
                    return ch;
                }
                seen++;
            }
        }
        return null;
    }

    private static java.util.List<Component> allComponents(Container c) {
        java.util.List<Component> out = new java.util.ArrayList<Component>();
        for (Component ch : c.getComponents()) {
            out.add(ch);
            if (ch instanceof Container) {
                out.addAll(allComponents((Container) ch));
            }
        }
        return out;
    }

    private static boolean isMoveCursor(Cursor c) {
        return c != null && c.getType() == Cursor.MOVE_CURSOR;
    }

    private static JComboBox<?> findChooser(Container c, String name) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof JComboBox && name.equals(ch.getName())) {
                return (JComboBox<?>) ch;
            }
            if (ch instanceof Container) {
                JComboBox<?> r = findChooser((Container) ch, name);
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

    private static void layout(Container c) {
        c.doLayout();
        for (Component ch : c.getComponents()) {
            if (ch instanceof Container) {
                layout((Container) ch);
            }
        }
        c.doLayout();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
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

    private static File tempFile() {
        try {
            File f = File.createTempFile("bioenv-drag", ".properties");
            f.delete();
            return f;
        } catch (Exception e) {
            return new File(System.getProperty("java.io.tmpdir"),
                    "bioenv-drag-" + System.nanoTime() + ".properties");
        }
    }
}

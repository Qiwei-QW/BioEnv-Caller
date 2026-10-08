package bioenv.test;

import java.awt.Component;
import java.awt.Container;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.JComboBox;
import javax.swing.SwingUtilities;

import bioenv.core.Schema;
import bioenv.core.Tool;
import bioenv.i18n.I18n;
import bioenv.i18n.Strings;
import bioenv.ui.SchemaFormPanel;

/**
 * Verifies that opening a subcommand leaves the panel on that subcommand.
 *
 * <p>A subcommand's options are fetched the first time it is opened, so the panel
 * shows a placeholder and rebuilds when the result arrives. Rebuilding refilled the
 * selector, and refilling it changes the selection as a side effect: clearing the
 * items selects nothing, and adding the first item selects that. Both changes fired
 * the action listener, which read them as the user choosing the top level and
 * cleared the active subcommand — so the panel bounced back the moment loading
 * finished, and the user had to pick their subcommand a second time.
 *
 * <p>The test drives that path with a real loader and waits for the background
 * result, because the bug only appears once the rebuild happens.
 *
 * <p>Run: {@code java -Djava.awt.headless=true -cp &lt;classes&gt; bioenv.test.SubcommandReloadTest}
 */
public final class SubcommandReloadTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        line("=== Opening a subcommand stays on it ===");
        line("");

        // The loader is the slow part in real use; here it returns at once, which
        // still exercises the same rebuild-on-completion path.
        final AtomicInteger loads = new AtomicInteger();

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                parent = new Schema();
                parent.parsed = true;
                parent.rawHelp = "parent";
                parent.subcommands.add(new Schema.Subcommand("search", "search"));
                parent.subcommands.add(new Schema.Subcommand("fasta", "convert to FASTA"));
                parent.subcommands.add(new Schema.Subcommand("gff", "convert to GFF"));

                tool = new Tool("bio", "bio_env", "/envs/bio_env");
                tool.schema = parent;
                form = new SchemaFormPanel();
                form.setSubcommandLoader(new SchemaFormPanel.SubcommandLoader() {
                    @Override
                    public Schema load(Schema.Subcommand sub) {
                        loads.incrementAndGet();
                        Schema child = new Schema();
                        child.parsed = true;
                        child.rawHelp = "help for " + sub.name;
                        Schema.Option o = new Schema.Option();
                        o.flag = "-" + sub.name;
                        o.arg = "";
                        o.description = "the " + sub.name + " option";
                        o.kind = Schema.Option.Kind.SWITCH;
                        child.options.add(o);
                        return child;
                    }
                });
                form.showTool(tool);
                form.setSize(900, 600);
                layout(form);
            }
        });

        line("[choosing a subcommand]");
        choose("fasta");
        check("the loader was asked for that subcommand", loads.get() == 1);
        check("the selector shows it",
                "fasta".equals(String.valueOf(findBox().getSelectedItem())));
        line("");

        line("[after loading completes]");
        // Let the SwingWorker finish and its done() run on the event thread.
        settle();
        check("the selector still shows the chosen subcommand",
                "fasta".equals(String.valueOf(findBox().getSelectedItem())));
        check("the panel is not back on the top level",
                !String.valueOf(findBox().getSelectedItem()).contains(topLevel()));
        check("the subcommand's own option is on screen", hasOptionField("-fasta"));
        line("");

        line("[the panel can still be rebuilt without losing the choice]");
        // Any rebuild used to reset the selection. Trigger one by choosing again
        // and waiting, then by asking for a refresh.
        choose("gff");
        settle();
        check("switching to another subcommand works",
                "gff".equals(String.valueOf(findBox().getSelectedItem())));
        check("its option is on screen", hasOptionField("-gff"));
        line("");

        line("[going back to the top level still works]");
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                // Select the top-level entry by the label the box actually holds.
                findBox().setSelectedItem(topLevel());
                layout(form);
            }
        });
        settle();
        check("the selector is on the top level",
                String.valueOf(findBox().getSelectedItem()).contains(topLevel()));
        line("");

        line("=== results ===");
        line("  passed: " + pass);
        line("  failed: " + fail);
        if (fail > 0) {
            line("");
            line("FAILURES PRESENT");
            System.exit(1);
        }
        line("");
        line("A chosen subcommand survives its own loading.");
    }

    private static Schema parent;
    private static Tool tool;
    private static SchemaFormPanel form;

    /** Selects a subcommand on the event thread. */
    private static void choose(final String name) throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                findBox().setSelectedItem(name);
                layout(form);
            }
        });
    }

    /**
     * Lets queued event-thread work and the background loader finish.
     *
     * <p>SwingWorker's {@code done} runs on the event thread, so it has to be given
     * the thread repeatedly rather than merely slept on.
     */
    private static void settle() throws Exception {
        for (int i = 0; i < 40; i++) {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    layout(form);
                }
            });
            Thread.sleep(25);
        }
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                layout(form);
            }
        });
    }

    private static boolean hasOptionField(String flag) {
        return findNamed(form, flag) != null;
    }

    private static Component findNamed(Container c, String name) {
        for (Component ch : c.getComponents()) {
            if (name.equals(ch.getName())) {
                return ch;
            }
            if (ch instanceof Container) {
                Component r = findNamed((Container) ch, name);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    /**
     * The selector's wording for the top-level entry.
     *
     * <p>Asked of I18n rather than written out in English: the default language is
     * Chinese, so an English literal only ever matched when English was forced in
     * by hand, which is why this class was never in the build.
     */
    private static String topLevel() {
        I18n.loadOnce();
        return I18n.t(Strings.FORM_TOP_LEVEL);
    }

    private static JComboBox<?> findBox() {
        return findBox(form);
    }

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


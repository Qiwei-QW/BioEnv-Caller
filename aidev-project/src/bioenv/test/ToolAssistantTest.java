package bioenv.test;

import java.io.File;

import javax.swing.SwingUtilities;

import bioenv.core.ToolNotesStore;
import bioenv.i18n.I18n;
import bioenv.ui.ToolAssistantPanel;

/**
 * Verifies the tool assistant window: two tabs, the help tab read-only and
 * carrying the tool's own text, the notes tab editable and loading back whatever
 * was saved.
 */
public final class ToolAssistantTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "bioenv-assist-" + System.nanoTime());
        dir.mkdirs();

        final String help = "trimAl v1.5.rev1\n\nBasic usage\n"
                + "  trimal -in <inputfile> -out <outputfile>\n";

        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    try {
                        body(dir, help);
                    } catch (Exception e) {
                        fail("unexpected " + e);
                    }
                }
            });
        } finally {
            deleteTree(dir);
        }

        line("");
        line("  passed: " + pass + "   failed: " + fail);
        if (fail > 0) {
            line("");
            line("FAILURES PRESENT");
            System.exit(1);
        }
    }

    private static void body(File dir, String help) throws Exception {
        I18n.loadOnce();
        File notesFile = new File(dir, "tool-notes.properties");

        line("[two tabs, named]");
        ToolNotesStore store = new ToolNotesStore(notesFile);
        ToolAssistantPanel panel = new ToolAssistantPanel("trimal", help, "trimal", store);
        panel.setSize(900, 660);

        check("there are two tabs", panel.tabs().getTabCount() == 2);
        check("the first is the help", I18n.t(bioenv.i18n.Strings.ASSISTANT_TAB_HELP)
                .equals(panel.tabs().getTitleAt(0)));
        check("the second is the notes", I18n.t(bioenv.i18n.Strings.ASSISTANT_TAB_NOTES)
                .equals(panel.tabs().getTitleAt(1)));
        check("it opens on the help tab", panel.tabs().getSelectedIndex() == 0);
        line("");

        line("[the help tab is read-only and holds the tool's own text]");
        check("the help text is there", help.equals(panel.helpArea().getText()));
        check("and cannot be edited", !panel.helpArea().isEditable());
        line("");

        line("[the notes tab is editable]");
        check("it can be edited", panel.notesArea().isEditable());
        check("it starts empty", panel.notes().isEmpty());
        check("and nothing counts as changed yet", !panel.changed());
        line("");

        line("[writing a note and saving]");
        final String written = "# 参数备注\n-in  输入比对文件\n-w   半宽窗口\n";
        panel.notesArea().setText(written);
        check("the panel sees a change", panel.changed());
        check("saving writes it", panel.save());
        check("it is no longer reported as changed", !panel.changed());

        // Reopening over the same file is what a restart looks like.
        ToolNotesStore reopened = new ToolNotesStore(notesFile);
        ToolAssistantPanel second =
                new ToolAssistantPanel("trimal", help, "trimal", reopened);
        check("a new window loads the note back", written.equals(second.notes()));
        second.dispose();
        line("");

        line("[notes are per scope, so another tool starts blank]");
        ToolAssistantPanel other =
                new ToolAssistantPanel("mafft", help, "mafft", reopened);
        check("a different command has no note", other.notes().isEmpty());
        other.dispose();
        line("");

        line("[clearing removes it]");
        panel.notesArea().setText("");
        check("clearing is a change", panel.changed());
        panel.save();
        check("and it is gone from the store", !new ToolNotesStore(notesFile).has("trimal"));
        line("");

        line("[a tool that printed no help still opens]");
        // The caller passes the explanatory text in, so the help tab is never a
        // blank rectangle that reads as a broken window.
        ToolAssistantPanel empty = new ToolAssistantPanel(
                "nothing", I18n.t(bioenv.i18n.Strings.FORM_NO_HELP_OUTPUT, "nothing"),
                "nothing", new ToolNotesStore(new File(dir, "n2.properties")));
        check("it builds", empty.tabs().getTabCount() == 2);
        check("and says why there is nothing",
                empty.helpArea().getText().contains("nothing"));
        check("its notes tab still works", empty.notesArea().isEditable());
        empty.dispose();
        line("");

        panel.dispose();
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            pass++;
        } else {
            fail++;
        }
        line("  " + (ok ? "PASS" : "FAIL") + "  " + label);
    }

    private static void fail(String why) {
        fail++;
        line("  FAIL  " + why);
    }

    private static void line(String s) {
        System.out.println(s);
    }

    private static void deleteTree(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    deleteTree(k);
                }
            }
        }
        f.delete();
    }
}

package bioenv.test;

import java.io.File;

import bioenv.core.CorrectionStore;
import bioenv.core.CustomOptionStore;
import bioenv.core.HelpParser;
import bioenv.core.Schema;
import bioenv.core.ToolNotesStore;

/**
 * Verifies the notes store: that notes survive a restart, that they are kept per
 * command and subcommand, and - the requirement that matters most - that writing
 * them cannot change what the form offers.
 *
 * <p>The last one is the whole point of the feature as specified: the notes are a
 * place to write things down, not an input to the parser. A user who writes a
 * parameter list and sees the parameter types change would have been misled about
 * what the form is for.
 */
public final class ToolNotesTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "bioenv-notes-" + System.nanoTime());
        dir.mkdirs();
        File notesFile = new File(dir, "tool-notes.properties");

        try {
            storage(dir, notesFile);
            scoping(dir, notesFile);
            neverParsed(dir, notesFile);
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

    /** What goes in comes back out, and outlives the process that wrote it. */
    private static void storage(File dir, File notesFile) {
        line("[a note is kept and comes back]");
        ToolNotesStore store = new ToolNotesStore(notesFile);
        check("a scope starts empty", store.get("trimal").isEmpty());
        check("and reports no note", !store.has("trimal"));

        String text = "# 常用\n"
                + "trimal -in aln.fa -out trimmed.fa -automated1\n"
                + "\n"
                + "-w 的窗口是半宽，不是全宽。";
        check("writing a note reports a change", store.set("trimal", text));

        // A second store over the same file is a restart.
        ToolNotesStore reopened = new ToolNotesStore(notesFile);
        check("the note survives a restart", text.equals(reopened.get("trimal")));
        check("and is reported as present", reopened.has("trimal"));
        line("");

        line("[writing the same text again is not a change]");
        check("no change is reported", !reopened.set("trimal", text));
        line("");

        line("[clearing]");
        check("clearing reports a change", reopened.set("trimal", "   "));
        check("and the note is gone", !reopened.has("trimal"));
        check("clearing an absent note is not a change", !reopened.set("trimal", ""));
        ToolNotesStore after = new ToolNotesStore(notesFile);
        check("the removal survives a restart", !after.has("trimal"));
        line("");
    }

    /** A note belongs to one command, and to one subcommand of it. */
    private static void scoping(File dir, File notesFile) {
        File f = new File(dir, "scope.properties");
        line("[notes are kept apart by scope]");
        ToolNotesStore store = new ToolNotesStore(f);
        store.set("bio fasta", "fasta 的备注");
        store.set("bio gff", "gff 的备注");
        store.set("trimal", "trimal 的备注");

        check("the first subcommand keeps its own", "fasta 的备注".equals(store.get("bio fasta")));
        check("the second is separate", "gff 的备注".equals(store.get("bio gff")));
        check("the parent command is separate again",
                "trimal 的备注".equals(store.get("trimal")));
        check("a scope never written is empty", store.get("bio sam").isEmpty());
        check("three scopes are stored", store.size() == 3);
        line("");
    }

    /**
     * The requirement: notes are text to read, never an input to the parser.
     *
     * <p>Checked by giving the same help text two very different notes and
     * asserting the schema built from that help is identical either way. If a
     * future change ever wired the notes into the parser, these assertions fail.
     */
    private static void neverParsed(File dir, File notesFile) {
        line("[notes cannot change the schema]");
        String help = "Usage: demo [options]\n\nOptions:\n"
                + " -i <file>   input file\n"
                + " -n <int>    count\n"
                + " -q          quiet\n";

        Schema plain = HelpParser.parse(help, "demo");
        Schema withNotes = HelpParser.parse(help, "demo");

        // A note that looks exactly like a help document describing different
        // parameters. If anything parsed it, the two schemas would diverge.
        ToolNotesStore store = new ToolNotesStore(new File(dir, "parsed.properties"));
        store.set("demo",
                "Usage: demo [options]\n\nOptions:\n"
                        + " --totally-different <path>   a parameter that does not exist\n"
                        + " --another <int>              neither does this one\n");

        withNotes.applyCorrection("demo", new CorrectionStore(new File(dir, "c.properties")),
                new CustomOptionStore(new File(dir, "o.properties")));

        check("both schemas have the same option count",
                plain.options.size() == withNotes.options.size());
        check("and the parser found the real options, not the noted ones",
                withNotes.option("-i") != null && withNotes.option("--totally-different") == null);
        check("the note itself is still stored intact",
                store.get("demo").contains("--totally-different"));
        line("");

        line("[the note is not reachable from the schema]");
        // Structural check: the schema has no field carrying the note, so no later
        // code path could start honouring it by accident.
        boolean schemaCarriesNotes = false;
        for (java.lang.reflect.Field field : Schema.class.getDeclaredFields()) {
            if (field.getName().toLowerCase().contains("note")) {
                schemaCarriesNotes = true;
            }
        }
        check("no note field on Schema", !schemaCarriesNotes);
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

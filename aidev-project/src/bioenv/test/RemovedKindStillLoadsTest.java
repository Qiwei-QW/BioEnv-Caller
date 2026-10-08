package bioenv.test;

import java.io.File;
import java.io.PrintWriter;

import bioenv.core.CorrectionStore;
import bioenv.core.CustomOptionStore;
import bioenv.core.HelpParser;
import bioenv.core.Schema;
import bioenv.ui.SchemaFormPanel;

/**
 * Verifies that a correction file written by an earlier version - one that names
 * a kind this version no longer has - still loads, and that the parameter it
 * referred to simply keeps the kind the parser gave it.
 *
 * <p>Three kinds were removed because they did nothing their simpler cousins did
 * not already do: PASTE_IN_PATH, TEXT_OUT_PATH and PATH_LIST_FILE. A file written
 * before the removal can name them, so this is the case that has to stay harmless:
 * the user's other corrections must survive, and the stale one must be dropped
 * rather than throwing the whole file away.
 */
public final class RemovedKindStillLoadsTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "bioenv-removed-" + System.nanoTime());
        dir.mkdirs();
        File corrFile = new File(dir, "type-corrections.properties");

        // A file as an older version would have left it: one entry naming a kind
        // that no longer exists, alongside entries that are still valid.
        //
        // The separator is written as a literal backslash-t, which is how the
        // Properties format escapes a tab. Writing a real tab here produces a file
        // that parses as a single key named "demo", and the test then fails for a
        // reason that has nothing to do with the code - it did, until this comment
        // was added.
        PrintWriter w = new PrintWriter(corrFile, "UTF-8");
        w.println("#BioEnv Caller parameter types, per tool");
        w.println("demo\\t--gone=PASTE_IN_PATH");
        w.println("demo\\t--alsoGone=TEXT_OUT_PATH");
        w.println("demo\\t--thirdGone=PATH_LIST_FILE");
        w.println("demo\\t--stillHere=NUMBER");
        w.close();

        line("[a store written before the removal]");
        CorrectionStore store = new CorrectionStore(corrFile);
        check("the file still loads", store != null);
        check("a valid entry beside the stale ones survives",
                store.lookup("demo", "--stillHere") == Schema.Option.Kind.NUMBER);
        check("a stale entry is dropped",
                store.lookup("demo", "--gone") == null);
        check("a stale entry is dropped (second)",
                store.lookup("demo", "--alsoGone") == null);
        check("a stale entry is dropped (third)",
                store.lookup("demo", "--thirdGone") == null);
        line("");

        line("[the parameter keeps what the parser gave it]");
        Schema s = HelpParser.parse(
                "OPTIONS:\n"
                + " --gone <file>     a path\n"
                + " --stillHere <int> a count\n",
                "demo");
        s.applyCorrection("demo", store, new CustomOptionStore(new File(dir, "e.properties")));

        Schema.Option gone = s.option("--gone");
        check("the option is still there", gone != null);
        check("and its kind is the parser's, not the removed one",
                gone != null && gone.kind == Schema.Option.Kind.INPUT_PATH);
        check("the valid correction beside it still applies",
                s.option("--stillHere").kind == Schema.Option.Kind.NUMBER);
        line("");

        line("[the form still builds]");
        SchemaFormPanel form = new SchemaFormPanel();
        bioenv.core.Tool t = new bioenv.core.Tool("demo", "demo_env", "/envs/demo");
        t.schema = s;
        form.showTool(t);
        form.setSize(900, 600);
        check("no exception while showing the tool", true);
        line("");

        line("  passed: " + pass + "   failed: " + fail);
        if (fail > 0) {
            line("");
            line("FAILURES PRESENT");
            System.exit(1);
        }
        deleteTree(dir);
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

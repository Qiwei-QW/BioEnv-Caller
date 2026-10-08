package bioenv.test;

import bioenv.core.CustomOptionStore;
import bioenv.core.HelpParser;
import bioenv.core.Schema;

import java.io.File;
import java.nio.file.Files;

/**
 * The three things the parameter editor got wrong.
 *
 * <p><b>Delete for good rewrote the help.</b> Cutting the declaration out of the
 * document was how a parameter was deleted, which destroyed what the tool said about
 * it. The decision belongs in the store, where it is reversible and where it touches
 * nothing the tool printed.
 *
 * <p><b>Deleting an added parameter left it behind.</b> The row was taken out of the
 * list before the change was recorded, so nothing ever removed the store entry; the
 * next load added the parameter back, at the bottom, because the recorded order no
 * longer mentioned it.
 *
 * <p><b>Reset did nothing.</b> It cleared the store, but renames and description
 * rewrites live in the help text, which it never put back — so the names stayed as
 * the user last typed them.
 *
 * <p>Run: {@code java -cp &lt;classes&gt; bioenv.test.EditorFixesTest}
 */
public final class EditorFixesTest {

    private static int pass;
    private static int fail;

    private static final String HELP =
            "Usage: probe [options]\n"
            + "\n"
            + "OPTIONS:\n"
            + "  -gt <n>     gap threshold\n"
            + "  -out <f>    output file\n";

    public static void main(String[] args) throws Exception {
        line("=== 删除参数不动帮助文档 ===");
        Schema s = HelpParser.parse(HELP, "probe");
        String before = s.rawHelp;
        check("the help arrived", before.contains("-gt"));

        // This is the store operation the editor now performs instead of editing the
        // document. The document itself is not passed to it at all.
        File dir = Files.createTempDirectory("bioenv-editor-fixes").toFile();
        CustomOptionStore store = new CustomOptionStore(
                new File(dir, "custom-options.properties"));
        store.hide(s.scope("probe"), "-gt");
        s.reapplyEdits("probe", null, store);

        check("the parameter is gone", s.option("-gt") == null);
        check("the other one is kept", s.option("-out") != null);
        check("the help text is untouched", before.equals(s.rawHelp));
        check("and the declaration is still in it",
                s.rawHelp.contains("-gt"));
        line("");

        line("[原始帮助留着一份，供重置使用]");
        check("a pristine copy exists", !s.originalHelp.isEmpty());
        check("it matches what the tool printed", s.originalHelp.equals(before));
        line("");

        line("=== 重置能把改过的名字还原 ===");
        Schema r = HelpParser.parse(HELP, "probe");
        final String pristine = r.rawHelp;
        // A rename is written into the help, which is the only edit that still is.
        r.setDescriptionInHelp("-gt", "changed description");
        check("the edit reached the help",
                !r.rawHelp.equals(pristine));
        check("the pristine copy did not move",
                r.originalHelp.equals(pristine));

        check("reset reports work", r.restoreOriginalHelp());
        check("the help is back", r.rawHelp.equals(pristine));
        check("the original description is back",
                r.rawHelp.contains("gap threshold"));
        line("");

        line("[重置后重建，一切回到初始]");
        CustomOptionStore cleared = new CustomOptionStore(
                new File(dir, "cleared.properties"));
        r.reapplyEdits("probe", null, cleared);
        check("both parameters are back", r.options.size() == 2);
        check("the description is the tool's own",
                r.option("-gt") != null
                        && r.option("-gt").description.contains("gap threshold"));
        line("");

        line("[重置后原始帮助仍然留着，可以再来一次]");
        check("still pristine", r.originalHelp.equals(pristine));
        line("");

        line("=== 删除自己添加的参数，store 里要真的没有 ===");
        CustomOptionStore custom = new CustomOptionStore(
                new File(dir, "added.properties"));
        String scope = "probe";
        // The key, which is what the editor records: "@<input>" for a positional.
        custom.add(scope, "@<input>", "the reads");
        check("it is recorded", custom.isAdded(scope, "@<input>"));

        // What the editor does now: mark it removed and let the ordinary removal path
        // record it, rather than dropping the row before that path can run.
        check("removal reports work", custom.remove(scope, "@<input>"));
        check("it is no longer recorded", !custom.isAdded(scope, "@<input>"));

        Schema after = HelpParser.parse(HELP, "probe");
        custom.applyTo(after, scope);
        check("it does not come back", after.optionByKey("@<input>") == null);
        check("the documented ones are unaffected", after.options.size() == 2);
        line("");

        // Clean up.
        dir.delete();

        line("  passed: " + pass + "   failed: " + fail);
        if (fail > 0) {
            line("");
            line("FAILURES PRESENT");
            System.exit(1);
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
}

package bioenv.test;

import java.io.File;

import bioenv.core.CorrectionStore;
import bioenv.core.HelpParser;
import bioenv.core.Schema;
import bioenv.core.TypeInference;

/**
 * Verifies that a parameter type chosen for one tool stays with that tool.
 *
 * <p>The store used to keep two things: the choice itself, and a rule generalised
 * from the option's documentation and applied to every other command. The rule
 * was the problem. A decision made while looking at one tool could silently
 * change how an unrelated tool's options were shown, and the user had no way to
 * see it coming and no obvious way to trace it afterwards.
 *
 * <p>So the tests below check both halves of the new contract: a choice is
 * remembered for its own tool, and it reaches nothing else — not another command,
 * not another option of the same command, and not an option that happens to be
 * documented the same way.
 *
 * <p>Run: {@code java -cp &lt;classes&gt; bioenv.test.CorrectionStoreTest}
 */
public final class CorrectionStoreTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        line("=== Per-tool parameter types ===");
        line("");

        File tmp = File.createTempFile("bioenv-corrections", ".properties");
        tmp.delete();
        File storeFile = new File(tmp.getParentFile(), tmp.getName());

        line("[a choice is remembered for its own tool]");
        CorrectionStore cs = new CorrectionStore(storeFile);
        check("starts empty", cs.size() == 0);

        cs.remember("somecmd", "-in", Schema.Option.Kind.INPUT_PATH, "<infile>");
        cs.remember("somecmd", "-out", Schema.Option.Kind.OUTPUT_PATH, "<outfile>");
        check("both choices recorded", cs.size() == 2);
        check("the file was written", storeFile.isFile());
        check("the choice reads back",
                cs.lookup("somecmd", "-in") == Schema.Option.Kind.INPUT_PATH);
        check("and the second one too",
                cs.lookup("somecmd", "-out") == Schema.Option.Kind.OUTPUT_PATH);

        // A fresh store over the same file must see them: this is what makes a
        // choice survive a restart.
        CorrectionStore reloaded = new CorrectionStore(storeFile);
        check("a choice survives a reload",
                reloaded.lookup("somecmd", "-in") == Schema.Option.Kind.INPUT_PATH);
        line("");

        line("[a choice does not reach another tool]");
        // Same flag, same documented placeholder, different tool: the earlier
        // choice must not apply. This is the whole point of the change.
        check("another tool with the same flag is unaffected",
                reloaded.lookup("othercmd", "-in") == null);
        check("another tool with the same placeholder is unaffected",
                reloaded.lookup("othercmd", "-in", "<infile>") == null);
        check("another tool's differently named option is unaffected",
                reloaded.lookup("othercmd", "--infile", "<infile>") == null);
        line("");

        line("[a choice does not reach another option of the same tool]");
        check("a sibling option is unaffected",
                reloaded.lookup("somecmd", "-n") == null);
        check("and its placeholder does not matter",
                reloaded.lookup("somecmd", "-n", "<infile>") == null);
        line("");

        line("[choices are listed per tool]");
        reloaded.remember("secondcmd", "-t", Schema.Option.Kind.NUMBER, "<threads>");
        check("the first tool lists only its own",
                reloaded.forCommand("somecmd").size() == 2);
        check("the second tool lists only its own",
                reloaded.forCommand("secondcmd").size() == 1);
        check("an unknown tool lists nothing",
                reloaded.forCommand("nosuchcmd").isEmpty());
        line("");

        line("[nothing is generalised away any more]");
        // A placeholder that once earned a rule must now earn nothing beyond the
        // single option it was chosen for.
        CorrectionStore shape = new CorrectionStore(
                new File(tmp.getParentFile(), tmp.getName() + ".shape"));
        shape.remember("toola", "-M", Schema.Option.Kind.CHOICE, "<txt>");
        shape.remember("toolc", "-g", Schema.Option.Kind.INPUT_PATH, "<infile>");
        check("only the two choices are stored", shape.size() == 2);
        check("a role placeholder spreads nowhere",
                shape.lookup("toold", "-i", "<infile>") == null);
        check("a shape placeholder spreads nowhere",
                shape.lookup("toolb", "-Z", "<txt>") == null);
        check("each stays with its own option",
                shape.lookup("toola", "-M") == Schema.Option.Kind.CHOICE
                        && shape.lookup("toolc", "-g") == Schema.Option.Kind.INPUT_PATH);
        line("");

        line("[choices apply to the tool's own schema]");
        Schema s = HelpParser.parse(
                "  -in <infile>    Input\n"
                + "  -out <outfile>  Output\n"
                + "  -n <int>        Number\n",
                "somecmd");
        check("schema parsed three options", s.options.size() == 3);
        CorrectionStore force = new CorrectionStore(
                new File(tmp.getParentFile(), tmp.getName() + ".force"));
        force.remember("somecmd", "-in", Schema.Option.Kind.TEXT);
        s.applyCorrection("somecmd", force);
        Schema.Option in = s.option("-in");
        check("the choice overrides the parsed kind",
                in != null && in.kind == Schema.Option.Kind.TEXT);
        check("and is marked as user-set", in != null && in.userCorrected);
        check("at high confidence",
                in != null && in.confidence == TypeInference.Confidence.HIGH);
        Schema.Option out = s.option("-out");
        check("an option with no choice keeps what was parsed",
                out != null && out.kind == Schema.Option.Kind.OUTPUT_PATH);
        line("");

        line("[robustness]");
        File bad = new File(tmp.getParentFile(), tmp.getName() + ".bad");
        java.io.PrintWriter pw = new java.io.PrintWriter(bad, "UTF-8");
        pw.println("this is not = a valid \u0000 properties file");
        pw.println("=:=\\");
        pw.close();
        CorrectionStore broken = new CorrectionStore(bad);
        check("a corrupt store loads without throwing", broken.size() >= 0);
        check("lookup on it returns null", broken.lookup("x", "y") == null);

        CorrectionStore nullFile = new CorrectionStore((File) null);
        nullFile.remember("a", "b", Schema.Option.Kind.TEXT);
        check("a null file target does not throw", true);
        check("a null kind is ignored", ignoreNullKind(cs));
        line("");

        line("[choices must be reversible]");
        // A choice can turn out to be wrong — a misclick, or a change of mind
        // about what a parameter is — so removing it has to be possible.
        CorrectionStore undo = new CorrectionStore(
                new File(tmp.getParentFile(), tmp.getName() + ".undo"));
        undo.remember("toola", "-M", Schema.Option.Kind.CHOICE, "<txt>");
        undo.remember("toolb", "-g", Schema.Option.Kind.INPUT_PATH, "<infile>");
        check("entries exist to inspect", undo.size() == 2);

        String key = undo.forCommand("toola").keySet().iterator().next();
        String described = CorrectionStore.describe(key,
                undo.forCommand("toola").get(key));
        check("an entry has a readable description",
                described.contains("toola") && described.contains("-M")
                        && described.contains("CHOICE"));

        check("removing one reports success", undo.forget(key));
        check("it is gone afterwards", undo.lookup("toola", "-M") == null);
        check("the other tool is untouched",
                undo.lookup("toolb", "-g") == Schema.Option.Kind.INPUT_PATH);
        check("removing a missing key reports failure", !undo.forget("no-such-key"));

        check("resetting one tool reports a count", undo.forgetCommand("toolb") == 1);
        check("that tool is now clear", undo.forCommand("toolb").isEmpty());
        check("and nothing is left at all", undo.size() == 0);

        undo.remember("c1", "-a", Schema.Option.Kind.TEXT);
        undo.remember("c2", "-b", Schema.Option.Kind.TEXT);
        check("forget-all reports a count", undo.forgetAll() == 2);
        check("nothing remains", undo.size() == 0);
        line("");

        line("[rules written by an earlier version are dropped]");
        // Older stores may contain "pattern:" entries that applied across tools.
        // Honouring them would reintroduce exactly the leakage being removed.
        //
        // Written through Properties so the tab in the key is escaped the way the
        // real store writes it; a hand-written file would have the tab read back
        // as a key/value separator instead.
        File legacy = new File(tmp.getParentFile(), tmp.getName() + ".legacy");
        java.util.Properties lp = new java.util.Properties();
        lp.setProperty("somecmd\t-in", "INPUT_PATH");
        lp.setProperty("pattern:<infile>", "INPUT_PATH");
        java.io.BufferedWriter lbw = new java.io.BufferedWriter(
                new java.io.OutputStreamWriter(
                        new java.io.FileOutputStream(legacy), "UTF-8"));
        lp.store(lbw, "legacy");
        lbw.close();
        CorrectionStore upgraded = new CorrectionStore(legacy);
        check("the per-tool entry is kept",
                upgraded.lookup("somecmd", "-in") == Schema.Option.Kind.INPUT_PATH);
        check("the cross-tool rule is dropped",
                upgraded.lookup("othercmd", "-in", "<infile>") == null);
        check("and it does not appear in the list",
                upgraded.size() == 1);
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
        line("A parameter type stays with the tool it was chosen for.");

        storeFile.delete();
        bad.delete();
        legacy.delete();
        new File(tmp.getParentFile(), tmp.getName() + ".force").delete();
        new File(tmp.getParentFile(), tmp.getName() + ".shape").delete();
        new File(tmp.getParentFile(), tmp.getName() + ".undo").delete();
        new File(tmp.getParentFile(), tmp.getName() + ".nosignal").delete();
    }

    private static boolean ignoreNullKind(CorrectionStore cs) {
        int before = cs.size();
        cs.remember("x", "y", null);
        return cs.size() == before;
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

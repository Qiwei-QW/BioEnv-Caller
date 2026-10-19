package bioenv.test;

import bioenv.core.CommandBuilder;
import bioenv.core.ConcisePreview;
import bioenv.core.ParamValue;
import bioenv.core.Schema;
import bioenv.core.Tool;
import bioenv.core.TypeInference;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Verifies the command preview's short form, and that nothing is lost by it.
 *
 * <p>The preview is what the user checks before pressing Run, and a path picked
 * in the file chooser arrives as a full Windows path translated to
 * {@code /mnt/c/...}. Two options were enough to bury the command in folder
 * names (DESIGN.md P13), so a path argument is shown as its last name —
 * {@code input.fasta} for a file, {@code pep} for a directory.
 *
 * <p>Two rules are checked as carefully as the shortening itself, because both
 * are ways for a cosmetic change to become a wrong run:
 *
 * <ul>
 *   <li>The shown text must still be a command. The pane is editable and an
 *       edited command is what runs, so {@code expand} must write the full paths
 *       back before the text reaches the shell.</li>
 *   <li>A name is used only when it stands for exactly one thing. If the word
 *       occurs elsewhere in the command, or two different paths end in the same
 *       name, the argument keeps its full spelling.</li>
 * </ul>
 *
 * <p>Run: {@code java -cp <classes> bioenv.test.ConcisePreviewTest}
 */
public final class ConcisePreviewTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        line("=== Command preview short form test ===");
        line("");

        twoChosenPaths();
        singleFileCase();
        nameBecomesUnusableCases();
        expandingHandEditedText();
        whitespaceAndLists();

        line("=== results ===");
        line("  passed: " + passed);
        line("  failed: " + failed);
        if (failed > 0) {
            line("");
            line("FAILURES PRESENT");
            System.exit(1);
        }
        line("");
        line("Paths are shown by name, and run in full.");
    }

    // ------------------------------------------------------------------
    // The case the short form exists for
    // ------------------------------------------------------------------

    private static void twoChosenPaths() {
        line("[two paths chosen in the file chooser]");
        Schema s = new Schema();
        s.options.add(opt("-o", "DIR", Schema.Option.Kind.OUTPUT_PATH));
        s.options.add(opt("-f", "DIR", Schema.Option.Kind.INPUT_PATH));
        s.options.add(opt("-M", "STR", Schema.Option.Kind.CHOICE, "msa", "diamond"));
        s.options.add(opt("-t", "NUM", Schema.Option.Kind.NUMBER));
        Tool t = new Tool("orthofinder", "env", "/envs/orthofinder");

        Map<String, ParamValue> v = values(s);
        v.get("-o").set("C:\\Users\\demo\\Desktop\\Results_Sep07_2");
        v.get("-f").set("C:\\Users\\demo\\Desktop\\pep");
        v.get("-M").set("msa");
        v.get("-t").set("12");

        ConcisePreview p = ConcisePreview.of(t, s, "", v, null);
        line("      " + p.text());
        check("directories and folders read as their names",
                "orthofinder -o Results_Sep07_2 -f pep -M msa -t 12".equals(p.text()));
        check("the full paths are gone from the pane",
                !p.text().contains("/mnt/") && !p.text().contains("Desktop"));
        check("a value that is not a path is untouched", p.text().contains("-M msa"));
        check("the pane knows it shortened something", p.isShortened());

        // The command that runs is the one the full builder produces: shortening
        // is a way of reading the command, never a shorter command.
        String run = p.expand(p.text());
        line("      " + run);
        check("the shown text expands to the full command",
                run.equals(CommandBuilder.userFacingPreview(t, s, "", v, null)));
        check("the output path is restored",
                run.contains("-o /mnt/c/Users/demo/Desktop/Results_Sep07_2"));
        check("the input path is restored",
                run.contains("-f /mnt/c/Users/demo/Desktop/pep"));
        line("");
    }

    private static void singleFileCase() {
        line("[a single file, either way round]");
        Schema s = new Schema();
        s.options.add(opt("-i", "FILE", Schema.Option.Kind.INPUT_PATH));
        s.options.add(opt("-o", "FILE", Schema.Option.Kind.OUTPUT_PATH));
        Tool t = new Tool("somecmd", "env", "/envs/e");

        Map<String, ParamValue> v = values(s);
        v.get("-i").set("D:\\data\\seqs\\input.fasta");
        v.get("-o").set("D:\\data\\seqs\\out.fasta");
        ConcisePreview p = ConcisePreview.of(t, s, "", v, null);
        line("      " + p.text());
        check("the input reads as input.fasta", p.text().contains("-i input.fasta"));
        check("the output reads as out.fasta", p.text().contains("-o out.fasta"));
        check("expansion restores both",
                p.expand(p.text()).contains("-i /mnt/d/data/seqs/input.fasta")
                        && p.expand(p.text()).contains("-o /mnt/d/data/seqs/out.fasta"));
        line("");

        // A path typed in WSL form is a location too, and the file chooser is not
        // the only way one arrives.
        Map<String, ParamValue> typed = values(s);
        typed.get("-i").set("/home/demo/data/pep.fa");
        ConcisePreview p2 = ConcisePreview.of(t, s, "", typed, null);
        check("a POSIX path is shortened as well", p2.text().contains("-i pep.fa"));
        check("and restored", p2.expand(p2.text()).contains("-i /home/demo/data/pep.fa"));
        line("");

        // A value that carries no folder is already its own name.
        Map<String, ParamValue> bare = values(s);
        bare.get("-i").set("pep");
        ConcisePreview p3 = ConcisePreview.of(t, s, "", bare, null);
        check("a bare name is left alone", "somecmd -i pep".equals(p3.text()));
        check("and nothing is marked for expansion", !p3.isShortened());
        line("");

        // A positional has no flag in front of it, so the name it is shown as must
        // stand for the path on its own.
        line("[a positional path]");
        Schema sp = new Schema();
        sp.options.add(opt("", "INPUT", Schema.Option.Kind.INPUT_PATH));
        Tool tp = new Tool("somecmd", "env", "/envs/e");
        Map<String, ParamValue> vp = values(sp);
        vp.get(CommandBuilder.key(sp.options.get(0))).set("C:\\data\\seqs\\input.fasta");
        ConcisePreview pp = ConcisePreview.of(tp, sp, "", vp, null);
        line("      " + pp.text());
        check("the positional reads as its name", "somecmd input.fasta".equals(pp.text()));
        check("and expands to the full path",
                "somecmd /mnt/c/data/seqs/input.fasta".equals(pp.expand(pp.text())));
        line("");
    }

    // ------------------------------------------------------------------
    // Cases where the short name must not be used
    // ------------------------------------------------------------------

    private static void nameBecomesUnusableCases() {
        line("[a name that already means something else]");
        Schema s = new Schema();
        s.options.add(opt("-M", "STR", Schema.Option.Kind.CHOICE, "msa", "diamond"));
        s.options.add(opt("-f", "DIR", Schema.Option.Kind.INPUT_PATH));
        Tool t = new Tool("somecmd", "env", "/envs/e");

        Map<String, ParamValue> v = values(s);
        v.get("-M").set("msa");
        v.get("-f").set("/home/demo/msa");
        ConcisePreview p = ConcisePreview.of(t, s, "", v, null);
        line("      " + p.text());
        check("the path keeps its spelling",
                p.text().contains("/home/demo/msa") && p.text().contains("-M msa"));
        check("expanding cannot move the mode value", p.expand(p.text())
                .equals(CommandBuilder.userFacingPreview(t, s, "", v, null)));
        line("");

        line("[two paths with the same last name]");
        Schema s2 = new Schema();
        s2.options.add(opt("-i", "DIR", Schema.Option.Kind.INPUT_PATH));
        s2.options.add(opt("-o", "DIR", Schema.Option.Kind.OUTPUT_PATH));
        Tool t2 = new Tool("somecmd", "env", "/envs/e");
        Map<String, ParamValue> v2 = values(s2);
        v2.get("-i").set("C:\\lab\\run1\\pep");
        v2.get("-o").set("D:\\lab\\run2\\pep");
        ConcisePreview p2 = ConcisePreview.of(t2, s2, "", v2, null);
        line("      " + p2.text());
        check("both paths are spelled out",
                p2.text().contains("/mnt/c/lab/run1/pep")
                        && p2.text().contains("/mnt/d/lab/run2/pep"));
        check("a name claimed twice is not used", !p2.isShortened());
        check("the command is unchanged",
                p2.expand(p2.text())
                        .equals(CommandBuilder.userFacingPreview(t2, s2, "", v2, null)));
        line("");

        // The same file named twice is not a clash: both occurrences stand for the
        // same path, so both may be shortened and both expand to it.
        line("[the same path used twice]");
        Schema s3 = new Schema();
        s3.options.add(opt("--in", "FILE", Schema.Option.Kind.INPUT_PATH));
        s3.options.add(opt("--also", "FILE", Schema.Option.Kind.INPUT_PATH));
        Tool t3 = new Tool("somecmd", "env", "/envs/e");
        Map<String, ParamValue> v3 = values(s3);
        v3.get("--in").set("C:\\lab\\pep.fa");
        v3.get("--also").set("C:\\lab\\pep.fa");
        ConcisePreview p3 = ConcisePreview.of(t3, s3, "", v3, null);
        line("      " + p3.text());
        check("both are shown short", "somecmd --in pep.fa --also pep.fa".equals(p3.text()));
        check("both expand to the same full path",
                "somecmd --in /mnt/c/lab/pep.fa --also /mnt/c/lab/pep.fa"
                        .equals(p3.expand(p3.text())));
        line("");

        // A drive on its own is named by a letter, which would read as a file name.
        line("[a drive is not a name]");
        Map<String, ParamValue> v4 = values(s3);
        v4.get("--in").set("/mnt/c");
        ConcisePreview p4 = ConcisePreview.of(t3, s3, "", v4, null);
        check("the mount root is left in full", p4.text().contains("/mnt/c"));
        check("and nothing was shortened", !p4.isShortened());
        line("");

        // Free-form arguments were typed by the user: they are shown as typed.
        line("[raw arguments are never shortened]");
        Map<String, ParamValue> v5 = values(s3);
        v5.get("--in").set("C:\\lab\\note.txt");
        ConcisePreview p5 = ConcisePreview.of(t3, s3, "", v5,
                "--extra /mnt/c/lab/other/note.txt");
        line("      " + p5.text());
        check("the raw text is shown verbatim",
                p5.text().endsWith("--extra /mnt/c/lab/other/note.txt"));
        check("the option value beside it is still short",
                p5.text().contains("--in note.txt"));
        line("");
    }

    // ------------------------------------------------------------------
    // Editing the pane
    // ------------------------------------------------------------------

    private static void expandingHandEditedText() {
        line("[a hand-edited preview keeps its paths]");
        Schema s = new Schema();
        s.options.add(opt("-o", "DIR", Schema.Option.Kind.OUTPUT_PATH));
        s.options.add(opt("-f", "DIR", Schema.Option.Kind.INPUT_PATH));
        s.options.add(opt("-t", "NUM", Schema.Option.Kind.NUMBER));
        Tool t = new Tool("orthofinder", "env", "/envs/e");
        Map<String, ParamValue> v = values(s);
        v.get("-o").set("C:\\Users\\demo\\Desktop\\Results_Sep07_2");
        v.get("-f").set("C:\\Users\\demo\\Desktop\\pep");
        ConcisePreview p = ConcisePreview.of(t, s, "", v, null);

        String edited = "orthofinder -o Results_Sep07_2 -f pep -t 8";
        String run = p.expand(edited);
        line("      " + run);
        check("the edit is kept", run.endsWith("-t 8"));
        check("the output directory is restored",
                run.contains("-o /mnt/c/Users/demo/Desktop/Results_Sep07_2"));
        check("the input directory is restored",
                run.contains("-f /mnt/c/Users/demo/Desktop/pep"));
        line("");

        // A word the pane never wrote is the user's own and is passed through.
        String own = "orthofinder -o /tmp/mine -f pep";
        String ownRun = p.expand(own);
        line("      " + ownRun);
        check("a typed absolute path is untouched", ownRun.contains("-o /tmp/mine"));
        check("the name the pane wrote is still expanded",
                ownRun.contains("-f /mnt/c/Users/demo/Desktop/pep"));
        line("");

        // Quoting is the same on the way back as it was on the way out: a name
        // with a space is one argument both times.
        line("[a name with a space]");
        Schema s2 = new Schema();
        s2.options.add(opt("-o", "FILE", Schema.Option.Kind.OUTPUT_PATH));
        Tool t2 = new Tool("somecmd", "env", "/envs/e");
        Map<String, ParamValue> v2 = values(s2);
        v2.get("-o").set("C:\\My Data\\out.fa");
        ConcisePreview p2 = ConcisePreview.of(t2, s2, "", v2, null);
        line("      " + p2.text());
        check("the shown name is one word", "somecmd -o out.fa".equals(p2.text()));
        String back = p2.expand(p2.text());
        line("      " + back);
        check("the restored path is quoted as one word",
                back.equals("somecmd -o '/mnt/c/My Data/out.fa'"));
        check("expansion is idempotent", back.equals(p2.expand(back)));
        line("");

        // Nothing to expand, nothing changes.
        line("[a preview with no paths]");
        Schema s3 = new Schema();
        s3.options.add(opt("-M", "STR", Schema.Option.Kind.CHOICE, "msa", "diamond"));
        Tool t3 = new Tool("somecmd", "env", "/envs/e");
        Map<String, ParamValue> v3 = values(s3);
        v3.get("-M").set("msa");
        ConcisePreview p3 = ConcisePreview.of(t3, s3, "", v3, null);
        check("an unknown word is passed through",
                "somecmd -M diamond".equals(p3.expand("somecmd -M diamond")));
        check("and the command is the plain one", "somecmd -M msa".equals(p3.text()));
        line("");
    }

    // ------------------------------------------------------------------
    // Shape of the text
    // ------------------------------------------------------------------

    private static void whitespaceAndLists() {
        line("[layout and lists]");
        Schema s = new Schema();
        s.options.add(opt("-o", "DIR", Schema.Option.Kind.OUTPUT_PATH));
        s.options.add(opt("-f", "DIR", Schema.Option.Kind.INPUT_PATH));
        s.options.add(opt("-i", "LIST", Schema.Option.Kind.PATH_LIST));
        Tool t = new Tool("somecmd", "env", "/envs/e");
        Map<String, ParamValue> v = values(s);
        v.get("-o").set("C:\\Users\\demo\\Desktop\\Results_Sep07_2");
        v.get("-f").set("C:\\Users\\demo\\Desktop\\pep");
        ConcisePreview p = ConcisePreview.of(t, s, "", v, null);

        // The pane can be edited into more than one line; the layout the user
        // typed is theirs, so only the words may change.
        String typed = "somecmd  -o Results_Sep07_2\n-f pep";
        String run = p.expand(typed);
        line("      " + run.replace("\n", "\\n"));
        check("the double space is kept", run.startsWith("somecmd  -o "));
        check("the newline is kept", run.contains("Results_Sep07_2\n-f"));
        check("both names are expanded", run.contains("/mnt/c/Users/demo/Desktop/pep"));
        line("");

        // A path list is shortened piece by piece, so each entry reads as a name.
        line("[a comma-separated path list]");
        Schema s2 = new Schema();
        s2.options.add(opt("-i", "LIST", Schema.Option.Kind.PATH_LIST));
        Tool t2 = new Tool("somecmd", "env", "/envs/e");
        Map<String, ParamValue> v2 = values(s2);
        v2.get("-i").set("C:\\a\\one.fa,C:\\b\\two.fa");
        ConcisePreview p2 = ConcisePreview.of(t2, s2, "", v2, null);
        line("      " + p2.text());
        check("each entry reads as its own name",
                "somecmd -i one.fa,two.fa".equals(p2.text()));
        check("and the list expands whole",
                "somecmd -i /mnt/c/a/one.fa,/mnt/c/b/two.fa"
                        .equals(p2.expand(p2.text())));
        line("");
    }

    // ------------------------------------------------------------------

    private static Schema.Option opt(String flag, String arg,
                                     Schema.Option.Kind kind, String... choices) {
        Schema.Option o = new Schema.Option();
        o.flag = flag;
        o.arg = arg;
        o.kind = kind;
        o.confidence = TypeInference.Confidence.HIGH;
        o.isSwitch = kind == Schema.Option.Kind.SWITCH;
        for (String c : choices) {
            o.choices.add(c);
        }
        if (choices.length > 0) {
            o.defaultValue = choices[0];
        }
        return o;
    }

    private static Map<String, ParamValue> values(Schema s) {
        Map<String, ParamValue> m = new LinkedHashMap<String, ParamValue>();
        for (Schema.Option o : s.options) {
            m.put(CommandBuilder.key(o), new ParamValue(o));
        }
        return m;
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

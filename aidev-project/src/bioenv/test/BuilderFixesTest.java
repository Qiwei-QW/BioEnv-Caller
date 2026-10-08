package bioenv.test;

import bioenv.core.CommandBuilder;
import bioenv.core.HelpParser;
import bioenv.core.ParamValue;
import bioenv.core.Schema;
import bioenv.core.Tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Covers the faults found by a systematic reading of the parser, the command
 * builder and the form.
 *
 * <p>Each block states the defect it guards against, because several of them
 * produced no error at all — the command was simply built differently from what the
 * user asked for, which is the kind of fault that survives a long time unnoticed.
 *
 * <p>Run: {@code java -cp &lt;classes&gt; bioenv.test.BuilderFixesTest}
 */
public final class BuilderFixesTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        line("=== Command building, at the edges ===");
        line("");

        line("[a Windows path is translated even when the option is plain text]");
        // Every option starts as a text field, so most values carry no kind. A
        // Windows path typed into one used to be passed through unchanged, and the
        // tool inside WSL could not open it.
        check("a drive path is recognised", CommandBuilder.looksLikeWindowsPath(
                "C:\\Users\\me\\data\\aln.fa"));
        check("a forward-slash drive path is recognised",
                CommandBuilder.looksLikeWindowsPath("C:/Users/me/data/aln.fa"));
        check("a lower-case drive letter is recognised",
                CommandBuilder.looksLikeWindowsPath("d:\\work\\aln.fa"));
        check("a UNC wsl share is recognised",
                CommandBuilder.looksLikeWindowsPath("\\\\wsl$\\Ubuntu\\home\\me\\a.fa"));
        check("a POSIX path is not a Windows path",
                !CommandBuilder.looksLikeWindowsPath("/home/me/aln.fa"));
        check("a bare file name is not a Windows path",
                !CommandBuilder.looksLikeWindowsPath("aln.fa"));
        check("a word is not a Windows path",
                !CommandBuilder.looksLikeWindowsPath("msa"));
        check("a value with a colon inside is not a Windows path",
                !CommandBuilder.looksLikeWindowsPath("a:b"));

        Schema text = schema("--in-file", "string", "input",
                Schema.Option.Kind.TEXT);
        check("a Windows path in a plain text option is translated",
                previewOf(text, "--in-file", "C:\\data\\aln.fa").contains("/mnt/c/data/aln.fa"));
        check("a plain value is left alone",
                previewOf(text, "--in-file", "msa").contains("msa")
                        && !previewOf(text, "--in-file", "msa").contains("mnt"));

        Schema declared = schema("--in-file", "FILE", "input",
                Schema.Option.Kind.INPUT_PATH);
        check("a declared input path is still translated",
                previewOf(declared, "--in-file", "C:\\data\\aln.fa")
                        .contains("/mnt/c/data/aln.fa"));
        line("");

        line("[a value that looks like a flag is quoted]");
        // Left bare it would be read as the next option rather than as this
        // option's value.
        check("a leading dash is quoted",
                "'-weird'".equals(CommandBuilder.quoteValue("-weird")));
        check("a quoting apostrophe is escaped",
                CommandBuilder.quoteValue("-it's").contains("'\\''"));
        check("an ordinary value is untouched",
                "plain".equals(CommandBuilder.quoteValue("plain")));
        check("a value with a space is still quoted",
                "'a b'".equals(CommandBuilder.quoteValue("a b")));
        line("");

        line("[a repeated flag is written once]");
        // Some help texts describe the same flag twice under different meanings.
        // Both entries share one key, so the value was written twice.
        Schema dup = HelpParser.parse(
                "Options:\n"
                + "  -a FILE   first meaning\n"
                + "  -a DIR    second meaning\n",
                "probe");
        check("both entries are read", dup.options.size() == 2);
        Map<String, ParamValue> dupVals = new LinkedHashMap<String, ParamValue>();
        ParamValue dv = new ParamValue(dup.options.get(0));
        dv.set("x");
        dupVals.put("-a", dv);
        List<String> dupArgs = CommandBuilder.buildArgs(dup, dupVals);
        check("the flag appears once", count(dupArgs, "-a") == 1);
        check("and so does its value", count(dupArgs, "x") == 1);
        line("");

        line("[an output directory is created when the value is a path]");
        // The automatic mkdir used to fire only for options explicitly marked as
        // output files, which, since every option starts as text, was almost none.
        Tool t = new Tool("probe", "probe_env", "/envs/probe_env");
        Schema out = schema("--out", "string", "output", Schema.Option.Kind.TEXT);
        Map<String, ParamValue> ov = new LinkedHashMap<String, ParamValue>();
        ParamValue opv = new ParamValue(out.options.get(0));
        opv.set("C:\\work\\newdir\\out.fa");
        ov.put("--out", opv);
        String script = CommandBuilder.buildScript(t, out, "", ov, "", null);
        check("the folder is created", script.contains("mkdir -p"));
        check("it is the right folder", script.contains("/mnt/c/work/newdir"));
        check("the file itself is translated",
                script.contains("--out") && script.contains("/mnt/c/work/newdir/out.fa"));

        Schema scalar = schema("--mode", "string", "mode", Schema.Option.Kind.TEXT);
        Map<String, ParamValue> sv = new LinkedHashMap<String, ParamValue>();
        ParamValue spv = new ParamValue(scalar.options.get(0));
        spv.set("fast");
        sv.put("--mode", spv);
        check("an ordinary value creates no directory",
                !CommandBuilder.buildScript(t, scalar, "", sv, "", null).contains("mkdir"));
        line("");

        line("[a tab-separated option line keeps its placeholder]");
        // A tab is one character but several columns wide; counted as one it made
        // the gap too narrow to be a separator.
        Schema tabbed = HelpParser.parse(
                "Options:\n  -i FILE\tinput file\n  -o OUT\toutput file\n", "probe");
        check("the first placeholder survives",
                tabbed.option("-i") != null && "FILE".equals(tabbed.option("-i").arg));
        check("the second placeholder survives",
                tabbed.option("-o") != null && "OUT".equals(tabbed.option("-o").arg));
        check("the description is clean",
                tabbed.option("-i") != null
                        && "input file".equals(tabbed.option("-i").description));
        check("tabs and spaces give the same reading",
                readsTheSame(
                        "Options:\n  -i FILE\tinput file\n",
                        "Options:\n  -i FILE        input file\n"));
        line("");

        line("[the schema's path scopes its stored settings]");
        Schema top = new Schema();
        check("a top-level schema scopes to the command alone",
                "bio".equals(top.scope("bio")));
        top.path = "fasta";
        check("a subcommand's scope includes its path",
                "bio fasta".equals(top.scope("bio")));
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
        line("Values reach the command as the user expressed them.");
    }

    private static boolean readsTheSame(String a, String b) {
        Schema sa = HelpParser.parse(a, "probe");
        Schema sb = HelpParser.parse(b, "probe");
        Schema.Option oa = sa.option("-i");
        Schema.Option ob = sb.option("-i");
        if (oa == null || ob == null) {
            return false;
        }
        return oa.arg.equals(ob.arg) && oa.description.equals(ob.description);
    }

    private static int count(List<String> list, String value) {
        int n = 0;
        for (String s : list) {
            if (s.equals(value)) {
                n++;
            }
        }
        return n;
    }

    private static Schema schema(String flag, String arg, String desc,
                                 Schema.Option.Kind kind) {
        Schema s = new Schema();
        s.parsed = true;
        s.rawHelp = "x";
        Schema.Option o = new Schema.Option();
        o.flag = flag;
        o.arg = arg;
        o.description = desc;
        o.kind = kind;
        s.options.add(o);
        return s;
    }

    private static String previewOf(Schema s, String flag, String value) {
        Tool t = new Tool("probe", "probe_env", "/envs/probe_env");
        Map<String, ParamValue> vals = new LinkedHashMap<String, ParamValue>();
        ParamValue pv = new ParamValue(s.options.get(0));
        pv.set(value);
        vals.put(flag, pv);
        return CommandBuilder.preview(t, s, "", vals, "");
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

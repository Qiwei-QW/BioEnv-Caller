package bioenv.test;

import bioenv.core.CommandBuilder;
import bioenv.core.CustomOptionStore;
import bioenv.core.ParamValue;
import bioenv.core.Schema;

import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Arguments given by position.
 *
 * <p>Plenty of tools take a file without naming it: {@code tool input.fa}. Such a
 * parameter has no flag, so the editor used to have no way to add one — every
 * parameter it could add needed a name like {@code -x}. The panel now reads angle
 * brackets as the mark: {@code <input>} is a positional, and the brackets are a
 * label for the form, never something written into the command.
 *
 * <p>What has to hold for that to be usable: the label reaches no command line, two
 * positionals stay distinct (otherwise they would share one type and one stored
 * value), and a stored positional is found again rather than re-added on every load.
 *
 * <p>Run: {@code java -cp &lt;classes&gt; bioenv.test.PositionalParamTest}
 */
public final class PositionalParamTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        line("=== 名字不进命令行，只有值进 ===");
        Schema schema = new Schema();
        Schema.Option pos = new Schema.Option();
        pos.arg = "<input>";
        pos.kind = Schema.Option.Kind.INPUT_PATH;
        schema.options.add(pos);

        Map<String, ParamValue> values = new LinkedHashMap<String, ParamValue>();
        ParamValue v = new ParamValue(pos);
        v.set("/home/u/work/inputs/a.fa");
        values.put(pos.key(), v);

        List<String> argv = CommandBuilder.buildArgs(schema, values);
        line("    argv: " + argv);
        check("one argument", argv.size() == 1);
        check("it is the value", !argv.isEmpty()
                && argv.get(0).contains("a.fa"));
        check("the label is not in it", !argv.toString().contains("<input>"));
        check("no stray brackets", !argv.toString().contains("<"));
        line("");

        line("[它的 key 带 @ 前缀]");
        check("key is @<input>", "@<input>".equals(pos.key()));
        check("label is the placeholder", "<input>".equals(pos.label()));
        line("");

        line("=== 两个位置参数互不干扰 ===");
        Schema.Option pos2 = new Schema.Option();
        pos2.arg = "<output>";
        schema.options.add(pos2);
        check("different keys", !pos.key().equals(pos2.key()));
        check("second is @<output>", "@<output>".equals(pos2.key()));
        line("");

        line("=== 位置参数排在命名参数之后 ===");
        Schema.Option named = new Schema.Option();
        named.flag = "-t";
        schema.options.add(0, named);
        Map<String, ParamValue> both = new LinkedHashMap<String, ParamValue>();
        ParamValue nv = new ParamValue(named);
        nv.set("4");
        both.put(named.key(), nv);
        ParamValue pv = new ParamValue(pos);
        pv.set("in.fa");
        both.put(pos.key(), pv);
        List<String> ordered = CommandBuilder.buildArgs(schema, both);
        line("    argv: " + ordered);
        check("the named one comes first",
                ordered.size() >= 2 && "-t".equals(ordered.get(0)));
        check("the positional follows",
                ordered.size() >= 2 && "in.fa".equals(ordered.get(ordered.size() - 1)));
        line("");

        line("=== 存进去再读回来：认得出，不重复添加 ===");
        File dir = Files.createTempDirectory("bioenv-positional").toFile();
        CustomOptionStore store = new CustomOptionStore(
                new File(dir, "custom-options.properties"));

        Schema fresh = new Schema();
        store.add(fresh.scope("probe"), "@<input>", "the reads");
        int added = store.applyTo(fresh, fresh.scope("probe"));
        check("one parameter added", added == 1);
        check("there is exactly one", fresh.options.size() == 1);

        Schema.Option built = fresh.options.get(0);
        check("it has no flag", built.flag.isEmpty());
        check("its label is the placeholder", "<input>".equals(built.arg));
        check("its key round-trips", "@<input>".equals(built.key()));
        check("the description came through",
                "the reads".equals(built.description));
        line("");

        line("[再 apply 一次不会变成两个]");
        // The lookup has to be by key: a positional cannot be found by flag, so a
        // flag-based lookup created a second copy on every load.
        int again = store.applyTo(fresh, fresh.scope("probe"));
        check("nothing added the second time", again == 0);
        check("still exactly one", fresh.options.size() == 1);
        line("");

        line("[值排进命令里也对]");
        Map<String, ParamValue> stored = new LinkedHashMap<String, ParamValue>();
        ParamValue sv = new ParamValue(built);
        sv.set("reads.fq");
        stored.put(built.key(), sv);
        List<String> argv2 = CommandBuilder.buildArgs(fresh, stored);
        line("    argv: " + argv2);
        check("the value is there", argv2.size() == 1 && "reads.fq".equals(argv2.get(0)));
        check("the label is not", !argv2.toString().contains("<input>"));
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

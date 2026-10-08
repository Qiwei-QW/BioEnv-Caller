package bioenv.test;

import bioenv.core.CommandBuilder;
import bioenv.core.CorrectionStore;
import bioenv.core.DiscoveryCache;
import bioenv.core.EnvFingerprint;
import bioenv.core.EnvRunner;
import bioenv.core.EnvScanner;
import bioenv.core.HelpParser;
import bioenv.core.NoiseFilter;
import bioenv.core.ParamValue;
import bioenv.core.Runner;
import bioenv.core.Schema;
import bioenv.core.SchemaBuilder;
import bioenv.core.Tool;
import bioenv.core.ToolRegistry;
import bioenv.core.TypeInference;
import bioenv.core.VersionParser;
import bioenv.core.WslBridge;
import bioenv.ui.MainPanel;
import bioenv.ui.SchemaFormPanel;
import java.io.File;

/**
 * Verifies that discovery results survive a restart, and that a stale cache is
 * recognised rather than trusted.
 *
 * <p>Two failures are being guarded against, and they pull in opposite
 * directions:
 *
 * <ul>
 *   <li>Not persisting, so the user waits for a full scan on every start.</li>
 *   <li>Persisting without a validity check, so an uninstalled tool keeps being
 *       offered and a newly installed one never appears.</li>
 * </ul>
 *
 * <p>Run: {@code java -cp <classes> bioenv.core.DiscoveryCacheTest}
 */
public final class DiscoveryCacheTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        line("=== Discovery cache test ===");
        line("");

        File tmp = File.createTempFile("bioenv-cache", ".properties");
        tmp.delete();
        File file = new File(tmp.getParentFile(), tmp.getName());

        // ---------- writing and reading back ------------------------------
        line("[round trip]");
        DiscoveryCache cache = new DiscoveryCache(file);
        check("an absent cache loads as null", cache.load() == null);

        DiscoveryCache.Data data = new DiscoveryCache.Data();
        data.fingerprint = "conda=/home/x/miniconda3\nenvs=2\n/a|1|2\n/b|3|4\n";
        data.condaRoot = "/home/x/miniconda3";
        data.envCount = 2;

        DiscoveryCache.CachedTool t1 = new DiscoveryCache.CachedTool();
        t1.command = "toolone";
        t1.envName = "env_a";
        t1.envPrefix = "/envs/a";
        t1.version = "1.2.3";
        t1.schema = sampleSchema();
        data.tools.add(t1);

        DiscoveryCache.CachedTool t2 = new DiscoveryCache.CachedTool();
        t2.command = "tooltwo";
        t2.envName = "env_b";
        t2.envPrefix = "/envs/b";
        // No schema: discovered but never opened.
        data.tools.add(t2);

        cache.save(data);
        check("the file was written", file.isFile());

        DiscoveryCache.Data back = new DiscoveryCache(file).load();
        check("the cache reloads", back != null);
        if (back == null) {
            finish();
            return;
        }
        check("fingerprint preserved", data.fingerprint.equals(back.fingerprint));
        check("conda root preserved", data.condaRoot.equals(back.condaRoot));
        check("both tools preserved", back.tools.size() == 2);

        DiscoveryCache.CachedTool rt1 = back.tools.get(0);
        check("command preserved", "toolone".equals(rt1.command));
        check("environment preserved", "env_a".equals(rt1.envName));
        check("version preserved", "1.2.3".equals(rt1.version));
        check("schema preserved", rt1.schema != null);

        if (rt1.schema != null) {
            Schema s = rt1.schema;
            // -in, -M, -n and -f: the fixture defines four.
            check("all four options preserved", s.options.size() == 4);
            Schema.Option in = s.option("-in");
            check("option flag preserved", in != null);
            check("option kind preserved",
                    in != null && in.kind == Schema.Option.Kind.INPUT_PATH);
            check("option confidence preserved",
                    in != null && in.confidence == TypeInference.Confidence.HIGH);
            check("option description preserved",
                    in != null && in.description.contains("Input"));
            Schema.Option mode = s.option("-M");
            check("choices preserved",
                    mode != null && mode.choices.contains("alpha")
                            && mode.choices.contains("beta"));
            check("usage-only marker preserved",
                    s.option("-f") != null && s.option("-f").fromUsageLine);
            check("range preserved",
                    s.option("-n") != null && s.option("-n").min != null);
            check("raw help preserved", s.rawHelp.contains("Usage:"));
            check("groups preserved", s.groups.contains("OPTIONS"));
        }

        DiscoveryCache.CachedTool rt2 = back.tools.get(1);
        check("an unprobed tool keeps a null schema", rt2.schema == null);
        line("");

        // ---------- fingerprints -------------------------------------------
        line("[fingerprint comparison]");
        check("identical fingerprints match",
                EnvFingerprint.matches("a|1\nb|2\n", "a|1\nb|2\n"));
        check("a changed mtime does not match",
                !EnvFingerprint.matches("a|1\nb|2\n", "a|1\nb|3\n"));
        check("an added environment does not match",
                !EnvFingerprint.matches("envs=2\na|1\n", "envs=3\na|1\nc|9\n"));
        check("a removed environment does not match",
                !EnvFingerprint.matches("envs=2\na|1\nb|2\n", "envs=1\na|1\n"));
        check("null never matches",
                !EnvFingerprint.matches(null, "a|1\n")
                        && !EnvFingerprint.matches("a|1\n", null));
        line("");

        // ---------- damaged files ------------------------------------------
        line("[damaged files are treated as absent, not trusted]");
        write(file, "this is not a properties file at all\n=:=\\");
        check("garbage yields null", new DiscoveryCache(file).load() == null);

        write(file, "formatVersion=1\ntoolCount=5\n");
        check("an old format version is rejected",
                new DiscoveryCache(file).load() == null);

        write(file, "formatVersion=2\ntoolCount=3\n");
        DiscoveryCache.Data partial = new DiscoveryCache(file).load();
        check("a truncated cache loads with the tools it has",
                partial != null && partial.tools.isEmpty());
        line("");

        // ---------- clearing ------------------------------------------------
        line("[clearing]");
        cache.save(data);
        check("file exists before clearing", file.isFile());
        cache.clear();
        check("file removed by clear", !file.isFile());
        check("loading after clear yields null", cache.load() == null);
        line("");

        finish();
        file.delete();
    }

    /** A schema with one option of each interesting shape. */
    private static Schema sampleSchema() {
        Schema s = new Schema();
        s.parsed = true;
        s.rawHelp = "Usage: mytool [options]\nOptions:\n -in <inputfile>  Input\n";
        s.helpInvocation = "mytool --help";
        s.groups.add("OPTIONS");

        Schema.Option in = new Schema.Option();
        in.flag = "-in";
        in.arg = "<inputfile>";
        in.description = "Input file";
        in.kind = Schema.Option.Kind.INPUT_PATH;
        in.confidence = TypeInference.Confidence.HIGH;
        in.aliases.add("--input");
        s.options.add(in);

        Schema.Option mode = new Schema.Option();
        mode.flag = "-M";
        mode.arg = "<txt>";
        mode.description = "Method";
        mode.kind = Schema.Option.Kind.CHOICE;
        mode.confidence = TypeInference.Confidence.HIGH;
        mode.choices.add("alpha");
        mode.choices.add("beta");
        s.options.add(mode);

        Schema.Option n = new Schema.Option();
        n.flag = "-n";
        n.arg = "<int>";
        n.description = "Count";
        n.kind = Schema.Option.Kind.NUMBER;
        n.min = Double.valueOf(1);
        n.max = Double.valueOf(100);
        s.options.add(n);

        Schema.Option f = new Schema.Option();
        f.flag = "-f";
        f.description = "";
        f.fromUsageLine = true;
        f.kind = Schema.Option.Kind.INPUT_PATH;
        s.options.add(f);

        return s;
    }

    private static void write(File f, String text) throws Exception {
        java.io.PrintWriter pw = new java.io.PrintWriter(f, "UTF-8");
        pw.print(text);
        pw.close();
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
        }
        line("  " + (ok ? "PASS" : "FAIL") + "  " + label);
    }

    private static void finish() {
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
        line("Discovery results survive a restart, and a stale cache is not trusted.");
    }

    private static void line(String s) {
        System.out.println(s);
    }
}

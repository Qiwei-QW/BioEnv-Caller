package bioenv.test;

import java.io.File;

import bioenv.core.DiscoveryCache;
import bioenv.core.EnvRunner;
import bioenv.core.EnvScanner;
import bioenv.core.HelpParser;
import bioenv.core.Schema;
import bioenv.core.Tool;
import bioenv.core.ToolRegistry;

/**
 * Reproduces the cache losing every schema, through the real code path.
 *
 * <p>ToolRegistry.scan() calls saveCache() as soon as the tool list is known,
 * which is before any tool has been introspected - so every schema is null at
 * that moment. saveCache writes the entry for the current distro, and if it does
 * not carry over the schemas already on disk, that write replaces them with
 * nothing. The next start then restores a list in which no tool has a parameter
 * table, which is the reported symptom: a tool's parameters come back only after
 * the editor is opened and confirmed.
 *
 * <p>Driven through ToolRegistry.saveCache, not DiscoveryCache.save, because the
 * merge being fixed lives in the former.
 */
public final class CacheKeepsSchemasTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "bioenv-ovw-" + System.nanoTime());
        dir.mkdirs();
        File cacheFile = new File(dir, "cache.properties");

        String help = "Usage: demo [options]\n\nOptions:\n -q   quiet\n";

        // ---- session 1: a tool was read, so its schema is in the cache --------
        DiscoveryCache cache = new DiscoveryCache(cacheFile);
        DiscoveryCache.Data first = new DiscoveryCache.Data();
        first.fingerprint = "fp";
        first.distro = "Ubuntu";
        DiscoveryCache.CachedTool a = new DiscoveryCache.CachedTool();
        a.command = "demo";
        a.envName = "demo_env";
        a.envPrefix = "/envs/demo";
        a.schema = HelpParser.parse(help, "demo");
        first.tools.add(a);
        cache.save(first);

        line("[session 1: the tool was read]");
        check("the cache holds one schema", countSchemas(cacheFile, "Ubuntu") == 1);
        line("");

        // ---- session 2: a scan lists the tools but reads no help -------------
        // This is what scan() + saveCache() does: the registry knows the tool
        // list, and no tool has a schema yet.
        // A scanner is required: saveCache returns early without one, and a probe
        // that leaves it null passes without ever reaching the merge it is meant to
        // test. Nothing here needs WSL - only getEnvs() and getCondaRoot() are read,
        // and both answer from fields.
        bioenv.core.WslBridge bridge = new bioenv.core.WslBridge("Ubuntu");
        ToolRegistry reg = new ToolRegistry(new EnvScanner(bridge), (EnvRunner) null);
        setField(reg, "cache", cache);
        setField(reg, "scannedAt", Long.valueOf(System.currentTimeMillis()));
        setField(reg, "lastFingerprint", "fp");
        setField(reg, "wsl", bridge);
        java.util.Map<String, Tool> tools = new java.util.LinkedHashMap<String, Tool>();
        Tool t = new Tool("demo", "demo_env", "/envs/demo");
        t.schema = null;                  // not introspected in this session
        tools.put("demo", t);
        setField(reg, "tools", tools);
        reg.saveCache();

        line("[session 2: a scan listed the tool but read no help]");
        DiscoveryCache.Data afterSave = new DiscoveryCache(cacheFile).loadFor("Ubuntu");
        line("      distro on disk = [" + (afterSave == null ? "null" : afterSave.distro)
                + "]  tools=" + (afterSave == null ? -1 : afterSave.tools.size())
                + "  schemas=" + countSchemas(cacheFile, "Ubuntu"));
        DiscoveryCache.Data anyEntry = new DiscoveryCache(cacheFile).load();
        line("      load() newest distro = [" + (anyEntry == null ? "null" : anyEntry.distro)
                + "]  schemas=" + (anyEntry == null ? -1 : countSchemasIn(anyEntry)));
        check("the schema on disk was kept",
                countSchemas(cacheFile, "Ubuntu") == 1);
        line("");

        // ---- session 3: the next start restores ------------------------------
        DiscoveryCache.Data restored = new DiscoveryCache(cacheFile).loadFor("Ubuntu");
        boolean hasSchema = restored != null && !restored.tools.isEmpty()
                && restored.tools.get(0).schema != null;
        line("[session 3: the next start]");
        check("the tool comes back with its parameter table", hasSchema);
        line("");

        line("  passed: " + pass + "   failed: " + fail);
        if (fail > 0) {
            line("");
            line("FAILURES PRESENT");
            System.exit(1);
        }
        deleteTree(dir);
    }

    private static int countSchemas(File f, String distro) {
        DiscoveryCache.Data d = new DiscoveryCache(f).loadFor(distro);
        if (d == null) {
            return -1;
        }
        int n = 0;
        for (DiscoveryCache.CachedTool t : d.tools) {
            if (t.schema != null) {
                n++;
            }
        }
        return n;
    }

    private static int countSchemasIn(DiscoveryCache.Data d) {
        int n = 0;
        for (DiscoveryCache.CachedTool t : d.tools) {
            if (t.schema != null) {
                n++;
            }
        }
        return n;
    }

    private static void setField(Object o, String name, Object value) throws Exception {
        java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, value);
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




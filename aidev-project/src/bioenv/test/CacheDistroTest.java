package bioenv.test;

import bioenv.core.DiscoveryCache;

import java.io.File;
import java.nio.file.Files;

/**
 * A machine may hold several WSL distributions, and a listing built from one says
 * nothing about another.
 *
 * <p>The cache used to record only the environment fingerprint, so results gathered
 * in one distribution were offered under any other — and a restart always showed
 * whichever distribution happened to be the default. The distribution is now part
 * of the record.
 *
 * <p>Run: {@code java -cp &lt;classes&gt; bioenv.test.CacheDistroTest}
 */
public final class CacheDistroTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("bioenv-cache-distro").toFile();
        File f = new File(dir, "cache.properties");

        line("=== The distribution is remembered with the results ===");
        DiscoveryCache cache = new DiscoveryCache(f);
        DiscoveryCache.Data out = new DiscoveryCache.Data();
        out.distro = "Ubuntu-26.04";
        out.condaRoot = "/home/u/miniconda3";
        out.fingerprint = "fp-1";
        out.envCount = 3;
        DiscoveryCache.CachedTool t = new DiscoveryCache.CachedTool();
        t.command = "samtools";
        t.envName = "samtools_env";
        t.envPrefix = "/home/u/miniconda3/envs/samtools_env";
        out.tools.add(t);
        cache.save(out);

        DiscoveryCache.Data back = new DiscoveryCache(f).load();
        check("the cache loads", back != null);
        check("the distribution survives the round trip",
                back != null && "Ubuntu-26.04".equals(back.distro));
        check("the tools survive too",
                back != null && back.tools.size() == 1);
        line("");

        line("[another distribution's results are a different record]");
        DiscoveryCache.Data other = new DiscoveryCache.Data();
        other.distro = "Debian";
        other.fingerprint = "fp-2";
        new DiscoveryCache(f).save(other);
        DiscoveryCache.Data reloaded = new DiscoveryCache(f).load();
        check("the newest write is what is read",
                reloaded != null && "Debian".equals(reloaded.distro));
        check("and the previous distribution is gone",
                reloaded != null && !"Ubuntu-26.04".equals(reloaded.distro));
        line("");

        line("[a cache with no distribution recorded still loads]");
        // Upgrading from a build that did not write the field must not discard
        // the work: an absent name means "unknown", not "wrong".
        DiscoveryCache.Data legacy = new DiscoveryCache.Data();
        legacy.fingerprint = "fp-3";
        new DiscoveryCache(f).save(legacy);
        DiscoveryCache.Data read = new DiscoveryCache(f).load();
        check("it loads", read != null);
        check("with an empty distribution",
                read != null && "".equals(read.distro));
        line("");

        line("[an unknown format is refused]");
        Files.write(f.toPath(), "formatVersion=999\n".getBytes("UTF-8"));
        check("a future format is not trusted",
                new DiscoveryCache(f).load() == null);
        line("");

        // Clean up.
        f.delete();
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

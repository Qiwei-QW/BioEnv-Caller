package bioenv.core;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Persists discovery results so a session does not start from nothing.
 *
 * <p>A full scan costs about nineteen seconds on a reference machine of thirty
 * environments, and learning a single tool's options costs several more. Making
 * the user repeat that on every start would be passing an implementation
 * shortcut on to them (DESIGN.md P23).
 *
 * <h2>What is stored</h2>
 *
 * The tool list (command, environment, version) and each tool's discovered
 * schema, including the raw help text. Storing the raw text matters: the
 * "how was this built?" view and the fallback to a raw command line both need
 * it, and re-fetching it would mean re-probing.
 *
 * <h2>One entry per channel</h2>
 *
 * The file holds <b>several</b> entries, one per distribution / machine, keyed by
 * the same label {@link Transport#getDistro()} returns. This used to be a single
 * entry, which meant WSL and a Linux VM kept evicting each other: scanning in one
 * overwrote the other, so switching back and forth re-scanned every single time.
 *
 * <p>Switching channels is a normal thing to do, and the two listings describe
 * two different machines that both still exist. Keeping one file per label costs
 * a few kilobytes and turns a full rescan into an instant load.
 *
 * <h2>Staleness</h2>
 *
 * A cache is only useful if wrong entries can be recognised. The
 * {@link EnvFingerprint} recorded alongside the data changes whenever an
 * environment gains, loses or updates a package, so a mismatch means the stored
 * list may no longer describe reality and the caller rescans.
 *
 * <h2>Format</h2>
 *
 * A properties file: key-value text, no dependencies, readable and repairable
 * by hand, and trivially ignored when absent or damaged. Losing it costs a
 * rescan, never correctness.
 */
public final class DiscoveryCache {

    private static final String FILE_NAME = "discovery-cache.properties";
    /**
     * Bumped when the stored shape changes.
     *
     * <p>3 = several entries. Version 2 (a single un-prefixed entry) is still
     * read, so upgrading does not throw away the listing the user already waited
     * for; it is rewritten as version 3 on the next save.
     */
    private static final String FORMAT_VERSION = "3";

    private static final String K_VERSION = "formatVersion";
    private static final String K_ENTRY_COUNT = "entryCount";

    /**
     * One entry's keys, under a per-entry prefix.
     *
     * <p>Passing an empty prefix reads the version-2 layout, which used exactly
     * these key names with no prefix at all — so one reader serves both formats
     * and there is no second parser to keep in step.
     */
    private static final String K_FINGERPRINT = "fingerprint";
    private static final String K_TOOL_COUNT = "toolCount";
    private static final String K_ENV_COUNT = "envCount";
    private static final String K_CONDA_ROOT = "condaRoot";
    /**
     * The distribution these results came from.
     *
     * <p>Recorded because a machine may hold several, and a listing built from one
     * says nothing about another. Without it, switching distribution offered the
     * previous one's tools, and a restart always showed whichever distribution
     * happened to be the default.
     */
    private static final String K_DISTRO = "distro";
    /**
     * When this entry's tool list was last scanned, epoch milliseconds.
     *
     * <p>Shown to the user as "last scanned at ...". A restored list can be hours
     * old, and without a date there is no way to judge whether it is worth a
     * rescan — the list looks exactly the same either way.
     */
    private static final String K_SCANNED_AT = "scannedAt";

    private final File file;

    /** A tool as stored on disk. */
    public static final class CachedTool {
        public String command;
        public String envName;
        public String envPrefix;
        public String version = "";
        /**
         * The conda package this command came from, and its version.
         *
         * <p>Stored so that a rescan in a later session can still tell an upgraded
         * tool from an unchanged one. Without them every tool would look like it
         * had no known version, and the merge would keep everything — including the
         * tools that genuinely changed.
         */
        public String packageName = "";
        public String packageVersion = "";
        /** Schema options as stored, or empty when never probed. */
        public Schema schema;
    }

    /** The whole cached state. */
    public static final class Data {
        public String fingerprint = "";
        /** Distribution the results came from; empty for a cache written before
         *  the field existed, which is treated as "any distribution". */
        public String distro = "";
        public String condaRoot = "";
        public int envCount;
        /** 这份列表是什么时候扫出来的（epoch 毫秒）；0 表示不知道（旧缓存）。 */
        public long scannedAt;
        public final List<CachedTool> tools = new ArrayList<CachedTool>();
    }

    public DiscoveryCache() {
        this(defaultFile());
    }

    public DiscoveryCache(File file) {
        this.file = file;
    }

    /** Where the cache lives, beside the user's other plugin data. */
    public static File defaultFile() {
        String home = System.getProperty("user.home");
        File dir = new File(home, ".TBtools" + File.separator + "BioEnvCaller");
        return new File(dir, FILE_NAME);
    }

    public File getFile() {
        return file;
    }

    // ------------------------------------------------------------------
    // Reading and writing
    // ------------------------------------------------------------------

    /**
     * Loads the most recently written entry.
     *
     * <p>Used where "which channel did the user last use" is the question — for
     * example picking which WSL distribution to open on start.
     *
     * @return the stored data, or null when there is nothing usable. A damaged
     *         file is treated as absent, because a rescan is cheap compared to
     *         the risk of trusting corrupt data.
     */
    public Data load() {
        List<Data> all = loadAll();
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    /**
     * Loads the entry for one channel.
     *
     * @param distro the label {@link Transport#getDistro()} returns for the
     *               channel being opened, e.g. {@code Ubuntu-22.04} or
     *               {@code qw@172.16.145.128:22}
     * @return that channel's stored data, or null when it has none. An entry
     *         stored without a label counts as a match for any label, which is
     *         how caches written before the field existed behave.
     */
    public Data loadFor(String distro) {
        String want = distro == null ? "" : distro.trim();
        Data unlabelled = null;
        List<Data> all = loadAll();
        for (Data d : all) {
            if (d.distro.equals(want)) {
                return d;
            }
            if (d.distro.isEmpty() && unlabelled == null) {
                unlabelled = d;
            }
        }
        return unlabelled;
    }

    /**
     * Reads every entry in the file, oldest first.
     *
     * <p>Version 2 held a single un-prefixed entry; it is read through the same
     * key layout with an empty prefix, so there is no second parser to keep in
     * step with this one. It is upgraded to version 3 on the next save.
     */
    private List<Data> loadAll() {
        List<Data> out = new ArrayList<Data>();
        if (file == null || !file.isFile()) {
            return out;
        }
        Properties p = new Properties();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), "UTF-8"));
            p.load(br);
        } catch (Exception e) {
            return out;
        } finally {
            close(br);
        }

        String version = p.getProperty(K_VERSION);
        if (FORMAT_VERSION.equals(version)) {
            int n = parseInt(p.getProperty(K_ENTRY_COUNT), 0);
            for (int i = 0; i < n; i++) {
                Data d = readEntry(p, "e" + i + ".");
                if (d != null) {
                    out.add(d);
                }
            }
        } else if ("2".equals(version)) {
            Data d = readEntry(p, "");
            if (d != null) {
                out.add(d);
            }
        }
        // Anything else was written by an incompatible version; ignored on
        // purpose, because a rescan is cheap and a misread is not.
        return out;
    }

    /** Reads one entry; null when the entry is absent or unusable. */
    private static Data readEntry(Properties p, String pre) {
        String countText = p.getProperty(pre + K_TOOL_COUNT);
        if (countText == null) {
            return null;
        }
        Data d = new Data();
        d.fingerprint = orEmpty(p.getProperty(pre + K_FINGERPRINT));
        d.distro = orEmpty(p.getProperty(pre + K_DISTRO));
        d.condaRoot = orEmpty(p.getProperty(pre + K_CONDA_ROOT));
        d.envCount = parseInt(p.getProperty(pre + K_ENV_COUNT), 0);
        d.scannedAt = parseLong(p.getProperty(pre + K_SCANNED_AT), 0L);
        int count = parseInt(countText, 0);

        for (int i = 0; i < count; i++) {
            String prefix = pre + "tool." + i + ".";
            String command = p.getProperty(prefix + "command");
            if (command == null || command.isEmpty()) {
                continue;
            }
            CachedTool t = new CachedTool();
            t.command = command;
            t.envName = orEmpty(p.getProperty(prefix + "envName"));
            t.envPrefix = orEmpty(p.getProperty(prefix + "envPrefix"));
            t.version = orEmpty(p.getProperty(prefix + "version"));
            t.packageName = orEmpty(p.getProperty(prefix + "packageName"));
            t.packageVersion = orEmpty(p.getProperty(prefix + "packageVersion"));
            t.schema = readSchema(p, prefix + "schema.");
            d.tools.add(t);
        }
        return d;
    }

    /**
     * Writes one channel's entry, leaving every other channel's entry intact.
     *
     * <p>That "leaving intact" is the whole point: with a single-entry file,
     * scanning in WSL overwrote the Linux VM's listing and the next switch had
     * to scan it all over again. Failure is silent — it only means a rescan later.
     */
    public void save(Data data) {
        if (file == null || data == null) {
            return;
        }
        // 先读回其它通道的条目。
        LinkedHashMap<String, Data> byDistro = new LinkedHashMap<String, Data>();
        for (Data d : loadAll()) {
            byDistro.put(d.distro, d);
        }
        // 先删再放，让它落到末尾——插入顺序就是「最近使用」的顺序，
        // load() 依赖这一点回答「上次用的是哪个通道」。
        byDistro.remove(data.distro);
        byDistro.put(data.distro, data);

        Properties p = new Properties();
        p.setProperty(K_VERSION, FORMAT_VERSION);
        p.setProperty(K_ENTRY_COUNT, String.valueOf(byDistro.size()));
        int i = 0;
        for (Data d : byDistro.values()) {
            writeEntry(p, "e" + i + ".", d);
            i++;
        }

        BufferedWriter bw = null;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            bw = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(file), "UTF-8"));
            p.store(bw, "BioEnv Caller discovery cache");
        } catch (Exception ignored) {
            // A cache that cannot be written simply is not used next time.
        } finally {
            close(bw);
        }
    }

    /** Deletes every channel's entry, forcing a rescan on the next start. */
    public void clear() {
        if (file != null && file.isFile()) {
            file.delete();
        }
    }

    /** Writes one entry's keys under a prefix; {@code ""} writes the v2 layout. */
    private static void writeEntry(Properties p, String pre, Data d) {
        p.setProperty(pre + K_FINGERPRINT, orEmpty(d.fingerprint));
        p.setProperty(pre + K_DISTRO, orEmpty(d.distro));
        p.setProperty(pre + K_CONDA_ROOT, orEmpty(d.condaRoot));
        p.setProperty(pre + K_ENV_COUNT, String.valueOf(d.envCount));
        p.setProperty(pre + K_SCANNED_AT, String.valueOf(d.scannedAt));
        p.setProperty(pre + K_TOOL_COUNT, String.valueOf(d.tools.size()));

        int i = 0;
        for (CachedTool t : d.tools) {
            String prefix = pre + "tool." + i + ".";
            p.setProperty(prefix + "command", orEmpty(t.command));
            p.setProperty(prefix + "envName", orEmpty(t.envName));
            p.setProperty(prefix + "envPrefix", orEmpty(t.envPrefix));
            p.setProperty(prefix + "version", orEmpty(t.version));
            p.setProperty(prefix + "packageName", orEmpty(t.packageName));
            p.setProperty(prefix + "packageVersion", orEmpty(t.packageVersion));
            if (t.schema != null) {
                writeSchema(p, prefix + "schema.", t.schema);
            }
            i++;
        }
    }

    // ------------------------------------------------------------------
    // Schema serialisation
    // ------------------------------------------------------------------

    private static void writeSchema(Properties p, String prefix, Schema s) {
        p.setProperty(prefix + "parsed", String.valueOf(s.parsed));
        p.setProperty(prefix + "rawHelp", s.rawHelp);
        // Written so a reset still has something to restore after a restart. The
        // cached rawHelp is the edited document; without this the pristine text
        // would be gone the moment the plugin closed.
        p.setProperty(prefix + "originalHelp", s.originalHelp);
        p.setProperty(prefix + "helpInvocation", orEmpty(s.helpInvocation));
        p.setProperty(prefix + "optionCount", String.valueOf(s.options.size()));
        p.setProperty(prefix + "groups", join(s.groups));

        int i = 0;
        for (Schema.Option o : s.options) {
            String q = prefix + "opt." + i + ".";
            p.setProperty(q + "flag", orEmpty(o.flag));
            p.setProperty(q + "arg", orEmpty(o.arg));
            p.setProperty(q + "description", orEmpty(o.description));
            p.setProperty(q + "group", orEmpty(o.group));
            p.setProperty(q + "default", orEmpty(o.defaultValue));
            p.setProperty(q + "kind", o.kind.name());
            p.setProperty(q + "confidence", o.confidence.name());
            p.setProperty(q + "isSwitch", String.valueOf(o.isSwitch));
            p.setProperty(q + "fromUsage", String.valueOf(o.fromUsageLine));
            p.setProperty(q + "aliases", join(o.aliases));
            p.setProperty(q + "choices", join(o.choices));
            p.setProperty(q + "min", o.min == null ? "" : String.valueOf(o.min));
            p.setProperty(q + "max", o.max == null ? "" : String.valueOf(o.max));
            i++;
        }

        p.setProperty(prefix + "subCount", String.valueOf(s.subcommands.size()));
        for (int j = 0; j < s.subcommands.size(); j++) {
            Schema.Subcommand sc = s.subcommands.get(j);
            String q = prefix + "sub." + j + ".";
            p.setProperty(q + "name", orEmpty(sc.name));
            p.setProperty(q + "description", orEmpty(sc.description));
            if (sc.schema != null) {
                writeSchema(p, q + "schema.", sc.schema);
            }
        }
    }

    private static Schema readSchema(Properties p, String prefix) {
        String countText = p.getProperty(prefix + "optionCount");
        if (countText == null && p.getProperty(prefix + "parsed") == null) {
            return null; // this tool was never probed
        }
        Schema s = new Schema();
        s.parsed = Boolean.parseBoolean(orEmpty(p.getProperty(prefix + "parsed")));
        // Stripped on the way out of the cache too: entries written before the WSL
        // boundary cleaned anything still hold terminal codes, and the help viewer
        // would show them.
        s.rawHelp = AnsiText.strip(orEmpty(p.getProperty(prefix + "rawHelp")));
        s.originalHelp = AnsiText.strip(
                orEmpty(p.getProperty(prefix + "originalHelp")));
        // A cache written before this field existed keeps only the edited text;
        // treating that as the original is the best available answer, and it is what
        // the schema does too.
        if (s.originalHelp.isEmpty()) {
            s.originalHelp = s.rawHelp;
        }
        s.helpInvocation = orEmpty(p.getProperty(prefix + "helpInvocation"));
        addAll(s.groups, split(orEmpty(p.getProperty(prefix + "groups"))));

        int count = parseInt(p.getProperty(prefix + "optionCount"), 0);
        for (int i = 0; i < count; i++) {
            String q = prefix + "opt." + i + ".";
            String flag = orEmpty(p.getProperty(q + "flag"));
            Schema.Option o = new Schema.Option();
            o.flag = flag;
            o.arg = orEmpty(p.getProperty(q + "arg"));
            o.description = orEmpty(p.getProperty(q + "description"));
            o.group = orEmpty(p.getProperty(q + "group"));
            o.defaultValue = orEmpty(p.getProperty(q + "default"));
            o.kind = kindOf(p.getProperty(q + "kind"), Schema.Option.Kind.TEXT);
            o.confidence = confidenceOf(p.getProperty(q + "confidence"));
            o.isSwitch = Boolean.parseBoolean(orEmpty(p.getProperty(q + "isSwitch")));
            o.fromUsageLine = Boolean.parseBoolean(orEmpty(p.getProperty(q + "fromUsage")));
            addAll(o.aliases, split(orEmpty(p.getProperty(q + "aliases"))));
            addAll(o.choices, split(orEmpty(p.getProperty(q + "choices"))));
            o.min = parseDouble(p.getProperty(q + "min"));
            o.max = parseDouble(p.getProperty(q + "max"));
            s.options.add(o);
        }

        int subCount = parseInt(p.getProperty(prefix + "subCount"), 0);
        for (int j = 0; j < subCount; j++) {
            String q = prefix + "sub." + j + ".";
            Schema.Subcommand sc = new Schema.Subcommand();
            sc.name = orEmpty(p.getProperty(q + "name"));
            sc.description = orEmpty(p.getProperty(q + "description"));
            sc.schema = readSchema(p, q + "schema.");
            s.subcommands.add(sc);
        }
        return s;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append('\u0001'); // a separator that cannot occur in a flag
            }
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    private static List<String> split(String joined) {
        List<String> out = new ArrayList<String>();
        if (joined == null || joined.isEmpty()) {
            return out;
        }
        for (String part : joined.split("\u0001")) {
            if (!part.isEmpty()) {
                out.add(part);
            }
        }
        return out;
    }

    private static void addAll(List<String> target, List<String> items) {
        for (String i : items) {
            if (!target.contains(i)) {
                target.add(i);
            }
        }
    }

    private static Schema.Option.Kind kindOf(String name, Schema.Option.Kind fallback) {
        if (name == null) {
            return fallback;
        }
        try {
            return Schema.Option.Kind.valueOf(name.trim());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static TypeInference.Confidence confidenceOf(String name) {
        if (name == null) {
            return TypeInference.Confidence.LOW;
        }
        try {
            return TypeInference.Confidence.valueOf(name.trim());
        } catch (IllegalArgumentException e) {
            return TypeInference.Confidence.LOW;
        }
    }

    private static Double parseDouble(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        try {
            return Double.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int parseInt(String s, int fallback) {
        if (s == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseLong(String s, long fallback) {
        if (s == null) {
            return fallback;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
                // nothing useful to do
            }
        }
    }
}

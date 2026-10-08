package bioenv.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Discovers every tool available across all conda environments.
 *
 * <p>Contains no list of known tools (DESIGN.md P1). Whatever is installed is
 * what appears; installing something new requires no code change (DESIGN.md P4).
 *
 * <p>Two filters keep the result usable, both based on structure rather than
 * tool identity:
 *
 * <ul>
 *   <li>{@link NoiseFilter} removes compiled-in dependencies — MPI launchers,
 *       libtool wrappers, ncurses helpers — which appear in {@code bin/} but are
 *       not things a user runs.</li>
 *   <li>An environment whose {@code bin/} holds a very large number of
 *       executables is a bundle rather than a toolset, so only the executables
 *       it explicitly declares as packages are offered.</li>
 * </ul>
 */
public final class ToolRegistry {

    private final EnvScanner scanner;
    private final EnvRunner runner;
    private final SchemaBuilder schemaBuilder;
    /** Remembers type corrections the user has made (DESIGN.md P10, layer 5). */
    private CorrectionStore corrections = new CorrectionStore();

    /**
     * Remembers parameters the user added by hand, for the tools whose help does
     * not describe everything they accept.
     */
    private CustomOptionStore customOptions = new CustomOptionStore();

    /**
     * Points the stores at files of the caller's choosing.
     *
     * <p>For a test, which must not write to the user's own records. Both stores are
     * replaced, since a registry holding one real store and one throwaway would be
     * stranger than either.
     */
    public void setStores(CorrectionStore c, CustomOptionStore o) {
        if (c != null) {
            this.corrections = c;
        }
        if (o != null) {
            this.customOptions = o;
        }
    }
    /** Persists discovery results across sessions (DESIGN.md P23). */
    private DiscoveryCache cache = new DiscoveryCache();
    /**
     * Transport to the far side, needed to compute the environment fingerprint.
     *
     * <p>Typed as {@link Transport}: the fingerprint only needs "run this script
     * and give me the output", which is true over SSH as well as WSL.
     */
    private Transport wsl;
    /** Fingerprint stored alongside the loaded cache, or null. */
    private String loadedFingerprint;
    /** Distribution the loaded cache came from, or "". */
    private String loadedDistro = "";
    /** Fingerprint of the most recent scan. */
    private String lastFingerprint;
    /**
     * 当前这份工具列表是什么时候扫出来的（epoch 毫秒）。
     *
     * <p>只在 {@link #scan()} 里更新，不随单次工具探测而变：它回答的是「列表有多旧」，
     * 而不是「最后一次动这个缓存是什么时候」。界面把它显示给用户，
     * 让人自己判断该不该点一次扫描。
     */
    private long scannedAt;

    /** command -> tool, the merged result across environments. */
    private final Map<String, Tool> tools = new LinkedHashMap<String, Tool>();
    /** Cache of env prefix -> executables. */
    private final Map<String, List<String>> execCache = new HashMap<String, List<String>>();

    /** Outcome of a discovery pass. */
    public static final class Report {
        public int envsScanned;
        public int executablesSeen;
        public int toolsKept;
        public int toolsHiddenAsDependencies;
        /**
         * Requested packages passed over because something else needed them.
         *
         * <p>Reported apart from {@link #toolsHiddenAsDependencies} because the two
         * are different findings: one is a command that looks like a helper, the
         * other is a platform an environment was built on.
         */
        public int packagesHiddenAsDependencies;
        /** Environments lacking an install record, so bin/ was scanned instead. */
        public int envsUsingFallback;
        /**
         * 本次扫描相对上次的增量计数。
         *
         * <p>分开报出来，是因为用户最关心的一件事就是「我点扫描会不会把设置弄丢」。
         * 说清「保留了多少个」，这个疑问就当场有了答案。
         */
        public int toolsAdded;
        /** 这次没被枚举到、因而被删掉的工具数。 */
        public int toolsRemoved;
        /**
         * 原样保留、连 schema 与用户设置一起留下的工具数。
         *
         * <p>这些工具<b>不会</b>被重新探测，所以扫描后仍然是打开即用的状态。
         */
        public int toolsPreserved;
        /** 因为包版本变了而被当作新装、需要重新探测的工具数。 */
        public int toolsRelearned;
        /** Environment name -> number of commands offered, in scan order. */
        public final Map<String, Integer> perEnvironment =
                new LinkedHashMap<String, Integer>();
        public final List<String> notes = new ArrayList<String>();
    }

    public ToolRegistry(EnvScanner scanner, EnvRunner runner) {
        this.scanner = scanner;
        this.runner = runner;
        this.schemaBuilder = new SchemaBuilder(runner);
        this.wsl = scanner == null ? null : scanner.getWsl();
    }

    public Map<String, Tool> getTools() {
        return tools;
    }

    public SchemaBuilder getSchemaBuilder() {
        return schemaBuilder;
    }

    /**
     * Scans all environments and populates the tool map.
     *
     * <p>Blocking; call from a worker thread.
     */
    public Report scan() {
        Report rep = new Report();
        // 不再 tools.clear()：已有工具要参与下面的增量合并，它们身上带着
        // 已经探测好的 schema 和用户做过的设置。合并本身会删掉这次没枚举到的。
        execCache.clear();

        Map<String, String> envs = scanner.getEnvs();
        rep.envsScanned = envs.size();

        // Resolve each command to the environment that most likely owns it.
        // Ownership is scored rather than first-come, because a tool is often
        // present in several environments as a dependency of others.
        Map<String, String> owner = new LinkedHashMap<String, String>();
    /** Environment and command, to the package that provided it. */
    Map<String, String> packageOf = new LinkedHashMap<String, String>();
        /** Environment and package, to the version conda records for it. */
        Map<String, String> versionOf = new LinkedHashMap<String, String>();
        Map<String, Integer> ownerScore = new LinkedHashMap<String, Integer>();
        Map<String, Integer> ownerSize = new LinkedHashMap<String, Integer>();

        for (Map.Entry<String, String> e : envs.entrySet()) {
            String envName = e.getKey();
            String prefix = e.getValue();

            // Discovery is driven by what the user asked conda to install, not
            // by what happens to be on disk. An environment's bin/ contains
            // everything its dependency tree pulled in, and an environment with
            // a single requested package can carry hundreds of library
            // executables; scanning bin/ and filtering afterwards always leaks
            // some. Asking conda what was requested and taking exactly what
            // those packages provide is precise (DESIGN.md P15).
            // Everything this environment can be asked in one pass: what was requested,
            // what something depends on, every package's commands, and every package
            // conda records. Each was a separate round trip, and starting a WSL
            // process costs about a second.
            EnvRunner.EnvFacts facts = runner.facts(prefix);
            // 包版本随手记下：它和上面那些事实来自同一批 conda-meta 文件，
            // 不额外开销，但下一次扫描要靠它分辨「这个工具换过版本没有」。
            for (Map.Entry<String, String> v : facts.packageVersions.entrySet()) {
                versionOf.put(envName + "\u0000" + v.getKey(), v.getValue());
            }
            List<String> requested = facts.requested;
            List<String> candidates = new ArrayList<String>();

            if (requested.isEmpty()) {
                // No request record: fall back to scanning, so the environment
                // is still usable rather than silently empty.
                candidates.addAll(executables(prefix));
                rep.envsUsingFallback++;
            } else {
                // What the environment installed for its own sake, as against what it
                // installed because something else needed it. Asking for a Python
                // program records the program and the interpreter alike, so without
                // this an environment offers python, pip, idle, 2to3 and the rest
                // beside the one tool it exists for. The interpreter is depended on
                // by the program; the program is depended on by nothing.
                Set<String> needed = facts.neededBySomething;

                Set<String> seen = new HashSet<String>();
                for (String pkg : requested) {
                    if (needed.contains(pkg)) {
                        rep.packagesHiddenAsDependencies++;
                        continue;
                    }
                    List<String> provided = facts.commandsByPackage.get(pkg);
                    if (provided == null) {
                        // No manifest under that name; asked for directly so that a
                        // package conda records differently is not silently lost.
                        provided = runner.executablesOfPackage(prefix, pkg);
                    }
                    for (String exe : provided) {
                        if (seen.add(exe)) {
                            candidates.add(exe);
                            // Remembered so the tree can group a package's commands
                            // together; a package may ship hundreds of them.
                            packageOf.put(envName + "\u0000" + exe, pkg);
                        }
                    }
                }
                // A requested package whose manifest is absent yields nothing; only
                // then is scanning the whole bin/ worth it, and only for an
                // environment that would otherwise be empty.
                if (candidates.isEmpty()) {
                    candidates.addAll(executables(prefix));
                    rep.envsUsingFallback++;
                }
            }

            rep.executablesSeen += candidates.size();

            Set<String> declared = new HashSet<String>();
            for (String p : facts.declared) {
                declared.add(normalize(p));
            }
            int envSize = facts.declared.size();
            String envStem = normalize(envName);

            for (String exe : candidates) {
                String stem = normalize(exe);

                // The structural filters still apply, because a requested
                // package may ship helper commands alongside its main ones.
                if (!NoiseFilter.isPlausibleTool(exe)) {
                    rep.toolsHiddenAsDependencies++;
                    continue;
                }

                int score = 1;
                if (envStem.equals(stem) || envStem.equals(stem + "env")) {
                    score = 10;
                }
                if (isVouchedFor(stem, declared)) {
                    score = Math.max(score, 5);
                }

                Integer prevScore = ownerScore.get(exe);
                Integer prevSize = ownerSize.get(exe);
                boolean better;
                if (prevScore == null) {
                    better = true;
                } else if (score != prevScore.intValue()) {
                    better = score > prevScore.intValue();
                } else {
                    // Tie: prefer the more specialised (smaller) environment.
                    better = prevSize == null || envSize < prevSize.intValue();
                }
                if (better) {
                    owner.put(exe, envName);
                    ownerScore.put(exe, Integer.valueOf(score));
                    ownerSize.put(exe, Integer.valueOf(envSize));
                }
            }
        }

        // 这次枚举出来的工具，先放进一张新表，再和已有的表合并。
        Map<String, Tool> enumerated = new LinkedHashMap<String, Tool>();
        for (Map.Entry<String, String> e : owner.entrySet()) {
            String command = e.getKey();
            String envName = e.getValue();
            Tool t = new Tool(command, envName, envs.get(envName));
            String pkg = packageOf.get(envName + "\u0000" + command);
            t.packageName = pkg == null ? "" : pkg;
            String pv = pkg == null ? null
                    : versionOf.get(envName + "\u0000" + pkg);
            t.packageVersion = pv == null ? "" : pv;
            enumerated.put(command, t);
        }

        // 增量合并，而不是推倒重建。
        //
        // 这里以前是 tools.clear() 再逐个建新对象，后果有三层：所有工具变回
        // 「没探测过」，于是每个都要重新读一遍帮助；用户在工具里做过的修改
        // （帮助原文的编辑、解析结果）随旧 schema 一起消失；而末尾的 saveCache()
        // 还会把这个「schema 全空」的版本写回缓存，扫完就关插件的话缓存也一起丢了。
        //
        // 现在的规则：删掉的不留、新增的加上、没变的原封不动、
        // 换过版本的当新装的重新探测（见 mergeTools）。
        Merge merged = mergeTools(tools, enumerated);
        tools.clear();
        tools.putAll(merged.merged);

        rep.toolsKept = tools.size();
        rep.toolsAdded = merged.added;
        rep.toolsRemoved = merged.removed;
        rep.toolsPreserved = merged.preserved;
        rep.toolsRelearned = merged.relearned;
        for (Tool t : tools.values()) {
            Integer n = rep.perEnvironment.get(t.envName);
            rep.perEnvironment.put(t.envName,
                    Integer.valueOf(n == null ? 1 : n.intValue() + 1));
        }

        rep.notes.add("Scanned " + rep.envsScanned + " conda environment(s).");
        rep.notes.add("Offered " + rep.toolsKept + " command(s) from the packages "
                + "each environment was asked to install.");
        if (rep.envsUsingFallback > 0) {
            rep.notes.add(rep.envsUsingFallback + " environment(s) had no install "
                    + "record, so their bin/ directory was scanned instead.");
        }

        // Record the state this scan describes, then persist it, so the next
        // start can offer the list without rescanning (DESIGN.md P23).
        lastFingerprint = EnvFingerprint.compute(wsl, envs, scanner.getCondaRoot());
        loadedFingerprint = lastFingerprint;
        scannedAt = System.currentTimeMillis();
        saveCache();
        return rep;
    }

    /**
     * 把这次枚举出来的工具并入已有工具表。
     *
     * <p>这是「扫描不清空用户设置」的关键。规则四条：
     *
     * <ol>
     *   <li><b>没变</b> —— 保留原来那个对象，连同它的 schema 和用户在工具里
     *       做过的所有修改。只更新环境归属，因为工具可能换了环境。</li>
     *   <li><b>换了版本</b> —— 当成新安装的工具：丢弃旧 schema，下次选中它时
     *       重新读帮助。升级和降级同样对待。</li>
     *   <li><b>新增</b> —— 建新对象，schema 为空，等用户选中时再探测。</li>
     *   <li><b>没被枚举到</b> —— 丢掉，说明环境或工具已被删除。</li>
     * </ol>
     *
     * <p>为什么「没变」必须保留原对象而不是重建一个：schema 里存着帮助原文、
     * 解析结果，以及用户对它做过的编辑。重建就等于把这些全部作废，而这正是
     * 用户点一次「扫描」却发现设置全没了的原因。
     *
     * @param existing   当前工具表（会被只读，不修改）
     * @param enumerated 这次枚举出来的工具，按环境发现顺序
     * @return 合并结果，含新表与四个计数
     */
    public static Merge mergeTools(Map<String, Tool> existing, Map<String, Tool> enumerated) {
        Merge m = new Merge();
        Map<String, Tool> out = new LinkedHashMap<String, Tool>();
        if (enumerated != null) {
            for (Map.Entry<String, Tool> e : enumerated.entrySet()) {
                String command = e.getKey();
                Tool fresh = e.getValue();
                Tool old = existing == null ? null : existing.get(command);
                if (old == null) {
                    out.put(command, fresh);
                    m.added++;
                } else if (versionChanged(old.packageVersion, fresh.packageVersion)) {
                    // 包版本变了：帮助内容可能跟着变了，旧 schema 不再可信，
                    // 所以当新装的重新探测。
                    out.put(command, fresh);
                    m.relearned++;
                } else {
                    old.envName = fresh.envName;
                    old.envPrefix = fresh.envPrefix;
                    // 包信息只在新值可知时更新。取不到包名的那条兜底路径会给出
                    // 空值，此时把旧的抹掉反而会让下一次再也判断不了版本变化。
                    if (!fresh.packageName.isEmpty()) {
                        old.packageName = fresh.packageName;
                    }
                    if (!fresh.packageVersion.isEmpty()) {
                        old.packageVersion = fresh.packageVersion;
                    }
                    out.put(command, old);
                    m.preserved++;
                }
            }
        }
        if (existing != null) {
            for (String command : existing.keySet()) {
                if (!out.containsKey(command)) {
                    m.removed++;
                }
            }
        }
        m.merged = out;
        return m;
    }

    /**
     * 是否**确知**版本变了。
     *
     * <p>任一侧为空都算「不知道」，而不知道绝不能当成变了：误判成「变了」的代价
     * 是重新探测这个工具并丢掉用户在它里面配置的一切，正是这套合并要避免的事。
     * 所以拿不准时一律保留。
     */
    public static boolean versionChanged(String before, String now) {
        if (before == null || now == null) {
            return false;
        }
        String a = before.trim();
        String b = now.trim();
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        return !a.equals(b);
    }

    /** 一次增量合并的结果。 */
    public static final class Merge {
        /** 合并后的工具表，顺序按本次枚举。 */
        public Map<String, Tool> merged = new LinkedHashMap<String, Tool>();
        public int added;
        public int removed;
        public int preserved;
        public int relearned;
    }

    /** Groups the current tools by environment, for the tree view (DESIGN.md P9). */
    public Map<String, List<Tool>> byEnvironment() {
        Map<String, List<Tool>> out = new LinkedHashMap<String, List<Tool>>();
        // Preserve environment discovery order, then sort inside each group.
        for (Tool t : tools.values()) {
            List<Tool> list = out.get(t.envName);
            if (list == null) {
                list = new ArrayList<Tool>();
                out.put(t.envName, list);
            }
            list.add(t);
        }
        for (List<Tool> list : out.values()) {
            Collections.sort(list, new Comparator<Tool>() {
                @Override
                public int compare(Tool a, Tool b) {
                    return a.command.compareToIgnoreCase(b.command);
                }
            });
        }
        return out;
    }

    /** The correction store backing layer 5 of type inference. */
    /** The store of parameters added by hand. */
    public CustomOptionStore getCustomOptions() {
        return customOptions;
    }

    public CorrectionStore getCorrections() {
        return corrections;
    }

    /**
     * Number of commands above which a group is displayed collapsed.
     *
     * <p>Some packages legitimately provide hundreds of independent commands —
     * a suite such as a general sequence-analysis toolkit is several hundred
     * tools in its own right. They must all remain available, but rendering them
     * inline would push everything else out of view, so a group this large is
     * collapsed to a single row that expands on demand.
     *
     * <p>The threshold is about readability of a list, not about any particular
     * package, so it carries no tool-specific knowledge.
     */
    public static final int LARGE_GROUP_THRESHOLD = 30;

    /**
     * How many commands one package may contribute before it is folded away.
     *
     * <p>Lower than {@link #LARGE_GROUP_THRESHOLD} because the two answer different
     * questions. That one protects the tree from an environment with a great many
     * commands; this one keeps a package with a great many programs from doing the
     * same, and a package that ships more than ten is already a suite rather than a
     * tool — EMBOSS installs several hundred.
     */
    public static final int PACKAGE_GROUP_THRESHOLD = 10;

    /**
     * Records the type a user chose for one option of one tool, applies it to that
     * tool's schema immediately, and persists it.
     *
     * <p>Passing a null kind clears the choice instead, which is how a parameter
     * goes back to being an ordinary text field. The setting touches this tool's
     * option and nothing else.
     */
    public void rememberCorrection(String command, String option,
                                   Schema.Option.Kind kind) {
        Tool t = tools.get(command);
        Schema.Option o = (t != null && t.schema != null)
                ? t.schema.option(option) : null;
        if (kind == null) {
            corrections.forget(command + "\t" + option);
            if (o != null) {
                o.kind = Schema.Option.Kind.TEXT;
                o.userCorrected = false;
                o.typeReason = "";
            }
            saveCache();
            return;
        }
        String placeholder = o == null ? null : o.arg;
        corrections.remember(command, option, kind, placeholder);
        if (o != null) {
            o.kind = kind;
            o.userCorrected = true;
            o.confidence = TypeInference.Confidence.HIGH;
            o.typeReason = "you set this type";
        }
        // The corrected schema is what future sessions should load.
        saveCache();
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    /**
     * Writes the current tools and their schemas to the cache.
     *
     * <p>Called after a scan and after each tool is learned, so that closing the
     * plugin does not throw away work the user already waited for
     * (DESIGN.md P23).
     *
     * <h2>Schemas already on disk are kept</h2>
     *
     * <p>A scan lists tools without reading any help, so at that moment every
     * tool's schema is null. Writing that entry as-is replaced the entry holding
     * the parameter tables, and the next start restored a list in which no tool
     * had one - the user saw a tool's parameters only after opening it again.
     * (Reported as: edits made in the parameter editor came back on their own
     * after a restart, because the editor's store was re-applied to a schema that
     * had just been thrown away.)
     *
     * <p>So a tool whose schema this session has not learned yet keeps the schema
     * the file already had for it. Only a tool that was genuinely re-learned -
     * {@code introspect}, a version change, or a reset, all of which set a fresh
     * schema - writes a new one. A tool with no schema here and none on disk is
     * written without one, which is correct: it has not been read yet.
     */
    public void saveCache() {
        if (scanner == null || cache == null) {
            return;
        }
        DiscoveryCache.Data d = new DiscoveryCache.Data();
        d.condaRoot = scanner.getCondaRoot();
        d.distro = currentDistro();
        d.envCount = scanner.getEnvs().size();
        d.scannedAt = scannedAt;
        d.fingerprint = lastFingerprint != null ? lastFingerprint
                : EnvFingerprint.compute(wsl, scanner.getEnvs(), d.condaRoot);

        // What the file holds now, so a schema this session has not touched is
        // carried over rather than dropped.
        Map<String, Schema> onDisk = schemasOnDisk(d.distro);

        for (Tool t : tools.values()) {
            DiscoveryCache.CachedTool c = new DiscoveryCache.CachedTool();
            c.command = t.command;
            c.envName = t.envName;
            c.envPrefix = t.envPrefix;
            c.version = t.version;
            // 包名和包版本必须一起存：下次扫描要靠它们分辨「这个工具换过版本没有」，
            // 不存的话重启后每个工具的版本都是空的，于是永远判断成「没变」。
            c.packageName = t.packageName;
            c.packageVersion = t.packageVersion;
            if (t.schema != null) {
                c.schema = t.schema;
            } else {
                c.schema = onDisk.get(t.command);
            }
            d.tools.add(c);
        }
        cache.save(d);
    }

    /**
     * The schemas the cache already holds for one channel, by command.
     *
     * <p>Read at the moment of saving rather than kept in a field: the file is
     * the only place that knows what a previous session learned, and this
     * registry did not load it when it scanned instead of restoring.
     */
    private Map<String, Schema> schemasOnDisk(String distro) {
        Map<String, Schema> out = new LinkedHashMap<String, Schema>();
        if (cache == null) {
            return out;
        }
        DiscoveryCache.Data existing = cache.loadFor(distro);
        if (existing == null) {
            return out;
        }
        for (DiscoveryCache.CachedTool c : existing.tools) {
            if (c.schema != null && c.command != null && !c.command.isEmpty()) {
                out.put(c.command, c.schema);
            }
        }
        return out;
    }

    /**
     * Restores tools and schemas from the cache.
     *
     * @return the number of tools restored; zero means there was nothing usable
     *         and the caller should scan
     */
    public int loadCache() {
        if (cache == null) {
            return 0;
        }
        DiscoveryCache.Data d = cache.loadFor(currentDistro());
        if (d == null || d.tools.isEmpty()) {
            return 0;
        }
        tools.clear();
        for (DiscoveryCache.CachedTool c : d.tools) {
            Tool t = new Tool(c.command, c.envName, c.envPrefix);
            t.version = c.version;
            t.packageName = c.packageName;
            t.packageVersion = c.packageVersion;
            if (c.schema != null) {
                // The user's remembered choices are re-applied so a restored
                // schema reflects their decisions rather than the raw parse.
                c.schema.applyCorrection(t.command, corrections, customOptions);
            }
            t.schema = c.schema;
            tools.put(c.command, t);
        }
        loadedFingerprint = d.fingerprint;
        loadedDistro = d.distro;
        scannedAt = d.scannedAt;
        return tools.size();
    }

    /**
     * The distribution the cached results belong to.
     *
     * <p>Empty for a cache written before the field existed, which is treated as
     * "unknown" rather than "wrong" so an upgrade does not throw the work away.
     */
    public String getLoadedDistro() {
        return loadedDistro;
    }

    /**
     * The distribution the current registry was built against, or "".
     *
     * <p>Reads {@link Transport#getDistro()}, which the interface defines as a
     * display label for "which machine am I talking to" — a distribution name
     * under WSL, {@code user@host:port} under SSH. Callers treat it as an opaque
     * label, so no per-channel branch is needed here.
     */
    private String currentDistro() {
        if (wsl == null || wsl.getDistro() == null) {
            return "";
        }
        return wsl.getDistro().trim();
    }

    /**
     * True when the cached state still matches the environments.
     *
     * <p>Compared against the fingerprint stored with the cache. A mismatch means
     * packages changed, so the stored list may no longer be accurate and the
     * caller rescans.
     *
     * <p>A cache from another distribution is never fresh: its tools came from a
     * different Linux system entirely, and offering them under this one would be
     * a lie about where they live.
     */
    public boolean cacheIsFresh() {
        if (loadedFingerprint == null || scanner == null) {
            return false;
        }
        String mine = currentDistro();
        if (!loadedDistro.isEmpty() && !mine.isEmpty()
                && !loadedDistro.equals(mine)) {
            return false;
        }
        String now = EnvFingerprint.compute(wsl, scanner.getEnvs(),
                scanner.getCondaRoot());
        return EnvFingerprint.matches(loadedFingerprint, now);
    }

    /** The fingerprint recorded for the currently loaded state. */
    public String getLoadedFingerprint() {
        return loadedFingerprint;
    }

    /**
     * 当前这份列表是什么时候扫出来的（epoch 毫秒）；0 表示不知道（旧缓存）。
     *
     * <p>界面用它显示「上次扫描：…」，让用户能判断这份可能是自动恢复出来的
     * 列表有多旧，以及值不值得点一次扫描。
     */
    public long getScannedAt() {
        return scannedAt;
    }

    /** Replaces the correction store's backing file, used by tests. */
    public void setCache(DiscoveryCache c) {
        this.cache = c;
    }

    /**
     * Forgets everything recorded about a tool and reads its help again.
     *
     * <p>This is what "Reset this tool" means: rather than trying to undo each
     * decision in turn, the stored edits are dropped and the tool is asked for its
     * help afresh — so the parameters listed are the ones the tool itself declares,
     * with nothing of the user's left over. Undoing edits one by one had to get
     * renames, descriptions, removals, order and types all right at once, and it
     * missed most of them.
     *
     * @param t the tool to read again
     * @param scope the editor's scope, which for a subcommand is not the command
     *              name alone; the caller has it and passing it in is the only way
     *              to clear the edits that belong to what is on screen
     * @return true when the tool was read successfully
     */
    public boolean relearn(Tool t, String scope) {
        if (t == null) {
            return false;
        }
        if (customOptions != null && scope != null && !scope.isEmpty()) {
            customOptions.reset(scope);
        }
        if (corrections != null) {
            corrections.forgetCommand(t.command);
        }
        t.schema = null;   // forces introspect to probe rather than return the old one
        return introspect(t);
    }

    /**
     * Probes a tool's version and help-derived schema. Blocking.
     *
     * <p>When the probe shows the command is not actually runnable, the tool is
     * removed from the registry: an entry that cannot execute would only waste
     * the user's time.
     *
     * @return true when the tool remains available after probing
     */
    public boolean introspect(Tool t) {
        if (t == null || t.envPrefix == null) {
            return false;
        }
        // A tool is learned at most once per session (DESIGN.md P24). Users move
        // between tools while comparing them, and re-probing on every visit would
        // make them wait repeatedly for an answer that cannot have changed.
        if (t.schema != null) {
            return true;
        }
        // Help is probed first, and any version it reveals is reused. Probing
        // help and version separately would double the number of environment
        // starts, and almost every tool states its version in its help banner
        // (DESIGN.md P22: saves calls, never changes how they run).
        Schema s = schemaBuilder.build(t.envPrefix, t.command);
        if (s == null) {
            // The command does not exist in the way the shell expects.
            tools.remove(t.command);
            return false;
        }
        String fromHelp = VersionParser.extract(s.rawHelp);
        t.version = schemaBuilder.probeVersion(t.envPrefix, t.command, fromHelp);
        // Re-apply anything the user corrected previously, so their decisions
        // persist across sessions (DESIGN.md P10, layer 5), then apply learned
        // rules so a correction made once helps on tools not yet corrected.
        s.applyCorrection(t.command, corrections, customOptions);
        t.schema = s;
        // Persist immediately: the user already waited for this, and a later
        // session should not have to wait again (DESIGN.md P23).
        saveCache();
        return true;
    }

    /**
     * Learns one subcommand's options, on demand.
     *
     * <p>Deferred until the user selects the subcommand: a tool may expose
     * dozens, and learning them all up front would make opening the tool take
     * minutes for options the user is not looking at (DESIGN.md P21).
     */
    public Schema introspectSubcommand(Tool t, Schema.Subcommand sub) {
        if (t == null || sub == null || t.envPrefix == null) {
            return null;
        }
        // Already learned: re-selecting a subcommand must be instant
        // (DESIGN.md P24). The user's edits are re-applied on every visit rather
        // than only when the help was first read, because an edit can be made after
        // that and has to be in force the next time the subcommand is opened.
        if (sub.schema != null) {
            sub.schema.applyCorrection(t.command, corrections, customOptions);
            return sub.schema;
        }
        Schema child = schemaBuilder.buildSubcommand(t.envPrefix, t.command, sub);
        if (child != null) {
            child.applyCorrection(t.command, corrections, customOptions);
            // Held on the subcommand, so the next visit is instant and so an edit
            // made later can be re-applied to the same object.
            sub.schema = child;
            // Persist, so a later session does not have to probe it again.
            saveCache();
        }
        return child;
    }

    private List<String> executables(String prefix) {
        List<String> cached = execCache.get(prefix);
        if (cached != null) {
            return cached;
        }
        List<String> fresh = runner.listExecutables(prefix);
        execCache.put(prefix, fresh);
        return fresh;
    }

    /** Case-folded, separator-free form for name comparison. */
    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase().replace("_", "").replace("-", "").replace(".", "");
    }

    /**
     * True when some declared package plausibly provides this executable.
     *
     * <p>The relationship is deliberately loose in one direction and strict in
     * the other: a package may provide several executables whose names extend
     * it ({@code ncurses} provides {@code ncurses6-config}), but an executable
     * must not be credited to an unrelated package that merely happens to share
     * a prefix. Requiring either an exact match or an extension of the package
     * name achieves that, and stops a kerberos library from vouching for a DNS
     * lookup tool.
     */
    private static boolean isVouchedFor(String exeStem, Set<String> declaredPackages) {
        if (exeStem.isEmpty()) {
            return false;
        }
        for (String pkg : declaredPackages) {
            if (pkg.isEmpty()) {
                continue;
            }
            if (pkg.equals(exeStem)) {
                return true;
            }
            // The executable extends the package name: htslib -> htsfile,
            // ncurses -> ncurses6config. Guard the length so short package
            // names cannot vouch for unrelated long ones.
            if (pkg.length() >= 3 && exeStem.startsWith(pkg)) {
                return true;
            }
        }
        return false;
    }

    /** Tools sorted by name. */
    public List<Tool> sorted() {
        List<Tool> out = new ArrayList<Tool>(tools.values());
        Collections.sort(out, new Comparator<Tool>() {
            @Override
            public int compare(Tool a, Tool b) {
                return a.command.compareToIgnoreCase(b.command);
            }
        });
        return out;
    }

    /** Tools whose command matches a substring, case-insensitively. */
    public List<Tool> search(String query) {
        List<Tool> out = new ArrayList<Tool>();
        String q = query == null ? "" : query.trim().toLowerCase();
        for (Tool t : sorted()) {
            if (q.isEmpty() || t.command.toLowerCase().contains(q)) {
                out.add(t);
            }
        }
        return out;
    }
}

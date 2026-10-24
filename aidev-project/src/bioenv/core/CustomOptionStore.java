package bioenv.core;

import java.io.File;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * The user's own edits to a tool's parameter list, per tool and subcommand.
 *
 * <h2>Why this exists</h2>
 *
 * The plugin can only offer what a tool's help describes, and help is often wrong or
 * incomplete: an option appears only in the manual, a family of options is listed
 * with one example, or a line of prose is read as a parameter that does not exist.
 * Without a way to correct that, the form is wrong in ways the user can see but
 * cannot fix.
 *
 * <p>So three edits are kept:
 *
 * <ul>
 *   <li><b>Added</b> — a parameter the help never described.</li>
 *   <li><b>Hidden</b> — a parameter the help describes but that is not real, or not
 *       wanted. Hidden rather than removed, so the decision can be undone: the help
 *       is re-read on every scan, and a deletion that could not be reversed would
 *       have to be made again each time.</li>
 *   <li><b>Order</b> — the order the parameters are shown and passed in.</li>
 * </ul>
 *
 * <p>Everything is keyed by scope, so an edit made for {@code bio fasta} does not
 * touch {@code bio gff}, which routinely shares parameter names with it.
 */
public final class CustomOptionStore {

    private static final String FILE_NAME = "custom-options.properties";
    private static final String SEP = "\t";
    /** Prefix marking a store entry as a hidden parameter rather than an added one. */
    private static final String HIDDEN_PREFIX = "hidden:";
    /** Prefix marking a store entry as the order of a scope. */
    private static final String ORDER_PREFIX = "order:";
    /** Separator inside the stored order list. */
    private static final String LIST_SEP = "\u0001";
    /**
     * Marks a stored key that names a positional argument rather than a flag.
     *
     * <p>A positional has nothing to write on the command line but its value, so it
     * has no flag to be stored under. Its label stands in, marked so that it cannot
     * be confused with a flag of the same spelling. The same convention is what
     * {@link Schema.Option#key()} produces, which is what lets an edit made in the
     * editor be found again when the schema is rebuilt.
     */
    private static final String POSITIONAL_MARK = "@";

    private final File file;
    /** Key: {@code scope\tdescription} for added; see {@link #key}. */
    private final Map<String, String> options = new LinkedHashMap<String, String>();
    /** Key: {@code hidden:scope} to a list of names. */
    private final Map<String, List<String>> hidden = new LinkedHashMap<String, List<String>>();
    /** Key: {@code order:scope} to the order the parameters are shown in. */
    private final Map<String, List<String>> order = new LinkedHashMap<String, List<String>>();
    /**
     * 本实例自上次成功写盘以来改过/新增的键（原始属性键值）。
     *
     * <p>保存时先读回磁盘再叠上这一小份，而不是把内存里的三张表整体写回去：
     * 另一个面板实例（TBtools 可能建不止一个，见 {@code PluginInject}）刚保存的
     * 参数编辑不会被过期快照删掉。
     */
    private final Map<String, String> changed = new LinkedHashMap<String, String>();
    /** 本实例删掉的键；删除也必须记住，否则别人删掉的会被写回来。 */
    private final Set<String> removed = new LinkedHashSet<String>();

    public CustomOptionStore() {
        this(defaultFile());
    }

    public CustomOptionStore(File file) {
        this.file = file;
        load();
    }

    /** Where added parameters live, beside the other stores. */
    public static File defaultFile() {
        String home = System.getProperty("user.home");
        File dir = new File(home, ".TBtools" + File.separator + "BioEnvCaller");
        return new File(dir, FILE_NAME);
    }

    public File getFile() {
        return file;
    }

    // ------------------------------------------------------------------
    // Added parameters
    // ------------------------------------------------------------------

    /**
     * Adds a parameter, or replaces the description of one already there.
     *
     * @param scope  the tool and subcommand it belongs to, e.g. {@code "bio fasta"}
     * @param flag   the parameter's name, e.g. {@code "-x"}
     * @param desc   what it does, shown beside it
     * @return true when anything changed
     */
    public boolean add(String scope, String flag, String description) {
        if (scope == null || flag == null || flag.trim().isEmpty()) {
            return false;
        }
        String name = flag.trim();
        String desc = description == null ? "" : description.trim();
        // Adding a name that was hidden means the user changed their mind about it,
        // so the hiding is lifted as part of the same action.
        boolean wasHidden = unhide(scope, name);
        String optionKey = key(scope, name);
        String previous = options.put(optionKey, desc);
        if (previous != null && previous.equals(desc)) {
            return wasHidden;
        }
        changed.put(optionKey, desc);
        removed.remove(optionKey);
        save();
        return true;
    }

    /** Removes an added parameter. A documented one is hidden instead. */
    public boolean remove(String scope, String flag) {
        if (scope == null || flag == null) {
            return false;
        }
        String optionKey = key(scope, flag.trim());
        if (options.remove(optionKey) == null) {
            return false;
        }
        removed.add(optionKey);
        changed.remove(optionKey);
        if (forgetInOrder(scope, flag.trim())) {
            String orderKey = ORDER_PREFIX + scope;
            changed.put(orderKey, join(order.get(orderKey)));
            removed.remove(orderKey);
        }
        save();
        return true;
    }

    /**
     * The parameters added for one scope, in the order they were added.
     *
     * @return names paired with their descriptions
     */
    public Map<String, String> forScope(String scope) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (scope == null) {
            return out;
        }
        String prefix = scope + SEP;
        for (Map.Entry<String, String> e : options.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                out.put(e.getKey().substring(prefix.length()), e.getValue());
            }
        }
        return out;
    }

    /** True when this parameter was added by hand. */
    public boolean isAdded(String scope, String flag) {
        return options.containsKey(key(scope, flag));
    }

    // ------------------------------------------------------------------
    // Hidden parameters
    // ------------------------------------------------------------------

    /** Hides a parameter the help describes. */
    public boolean hide(String scope, String flag) {
        if (scope == null || flag == null || flag.trim().isEmpty()) {
            return false;
        }
        List<String> list = hiddenList(scope);
        String name = flag.trim();
        if (list.contains(name)) {
            return false;
        }
        list.add(name);
        String hiddenKey = HIDDEN_PREFIX + scope;
        changed.put(hiddenKey, join(list));
        removed.remove(hiddenKey);
        save();
        return true;
    }

    /** Brings a hidden parameter back. */
    public boolean unhide(String scope, String flag) {
        if (scope == null || flag == null) {
            return false;
        }
        String hiddenKey = HIDDEN_PREFIX + scope;
        List<String> list = hidden.get(hiddenKey);
        if (list == null || !list.remove(flag.trim())) {
            return false;
        }
        changed.put(hiddenKey, join(list));
        removed.remove(hiddenKey);
        save();
        return true;
    }

    /** The parameters hidden for one scope. */
    public List<String> hiddenFor(String scope) {
        List<String> list = hidden.get(HIDDEN_PREFIX + scope);
        return list == null ? new ArrayList<String>() : new ArrayList<String>(list);
    }

    /** True when anything has been hidden for this scope. */
    public boolean hasHidden(String scope) {
        List<String> list = hidden.get(HIDDEN_PREFIX + scope);
        return list != null && !list.isEmpty();
    }

    private List<String> hiddenList(String scope) {
        String k = HIDDEN_PREFIX + scope;
        List<String> list = hidden.get(k);
        if (list == null) {
            list = new ArrayList<String>();
            hidden.put(k, list);
        }
        return list;
    }

    // ------------------------------------------------------------------
    // Order
    // ------------------------------------------------------------------

    /**
     * Records the order the parameters should be shown in.
     *
     * @param scope the tool and subcommand
     * @param flags every parameter's name, in the wanted order
     */
    public boolean setOrder(String scope, List<String> flags) {
        if (scope == null || flags == null) {
            return false;
        }
        List<String> copy = new ArrayList<String>(flags);
        String orderKey = ORDER_PREFIX + scope;
        List<String> previous = order.get(orderKey);
        if (previous != null && previous.equals(copy)) {
            return false;
        }
        order.put(orderKey, copy);
        changed.put(orderKey, join(copy));
        removed.remove(orderKey);
        save();
        return true;
    }

    /** The recorded order for one scope, or an empty list when none was set. */
    public List<String> orderFor(String scope) {
        List<String> list = order.get(ORDER_PREFIX + scope);
        return list == null ? new ArrayList<String>() : new ArrayList<String>(list);
    }

    /** Removes one name from the recorded order; true when the order actually changed. */
    private boolean forgetInOrder(String scope, String flag) {
        List<String> list = order.get(ORDER_PREFIX + scope);
        return list != null && list.remove(flag);
    }

    // ------------------------------------------------------------------
    // Applying to a schema
    // ------------------------------------------------------------------

    /**
     * Grafts the user's edits onto a schema: hidden parameters out, added ones in,
     * and the whole list in the recorded order.
     *
     * <p>Applied on every read, including one served from the cache, so an edit made
     * in one session is in force in the next.
     *
     * @return how many parameters the list gained
     */
    public int applyTo(Schema schema, String scope) {
        if (schema == null || scope == null) {
            return 0;
        }
        // Hidden first: a parameter the user rejected is dropped before anything
        // else looks at the list.
        List<String> gone = hiddenFor(scope);
        if (!gone.isEmpty()) {
            List<Schema.Option> keep = new ArrayList<Schema.Option>();
            for (Schema.Option o : schema.options) {
                // Matched by key, so a removed positional is recognised too; by flag
                // alone every positional would answer to the empty string and all of
                // them would be dropped together.
                if (!gone.contains(o.key())) {
                    keep.add(o);
                }
            }
            schema.options.clear();
            schema.options.addAll(keep);
        }

        int added = 0;
        for (Map.Entry<String, String> e : forScope(scope).entrySet()) {
            Schema.Option existing = schema.optionByKey(e.getKey());
            if (existing != null) {
                // A description the user supplied for a parameter the help names but
                // does not explain — one listed only in the usage line. There is no
                // line in the help to rewrite, so the text is kept here, and it is
                // applied over whatever the parse produced, which for such a
                // parameter is nothing.
                if (!e.getValue().isEmpty()) {
                    existing.description = e.getValue();
                }
                continue;
            }
            Schema.Option o = new Schema.Option();
            // A key beginning with @ names a positional: it stands for an argument
            // given by position, so it carries a label for the form and no flag for
            // the command line. The value is emitted on its own, in schema order.
            if (e.getKey().startsWith(POSITIONAL_MARK)) {
                o.arg = e.getKey().substring(POSITIONAL_MARK.length());
            } else {
                o.flag = e.getKey();
            }
            o.description = e.getValue();
            o.kind = Schema.Option.Kind.TEXT;
            o.userAdded = true;
            schema.options.add(o);
            added++;
        }

        applyOrder(schema, scope);
        return added;
    }

    /**
     * Reorders a schema's parameters to match the recorded order.
     *
     * <p>Parameters the order does not mention keep their relative places after the
     * ones it does, so an order recorded before a parameter was added still sorts
     * the rest sensibly.
     */
    private void applyOrder(Schema schema, String scope) {
        List<String> wanted = orderFor(scope);
        if (wanted.isEmpty() || schema.options.size() < 2) {
            return;
        }
        final Map<String, Integer> rank = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < wanted.size(); i++) {
            rank.put(wanted.get(i), i);
        }
        List<Schema.Option> sorted = new ArrayList<Schema.Option>(schema.options);
        java.util.Collections.sort(sorted, new java.util.Comparator<Schema.Option>() {
            @Override
            public int compare(Schema.Option a, Schema.Option b) {
                // Matched by key, not by flag. The order the editor records is made
                // of keys, and a positional has no flag at all — ranking by flag left
                // every positional unlisted, which sent it to the bottom however far
                // up it had been dragged.
                Integer ra = rank.get(a.key());
                Integer rb = rank.get(b.key());
                if (ra == null && rb == null) {
                    return 0;   // stable: unlisted ones keep their relative order
                }
                if (ra == null) {
                    return 1;   // unlisted ones go after the listed ones
                }
                if (rb == null) {
                    return -1;
                }
                return ra.intValue() - rb.intValue();
            }
        });
        schema.options.clear();
        schema.options.addAll(sorted);
    }

    // ------------------------------------------------------------------
    // Housekeeping
    // ------------------------------------------------------------------

    /** Every scope that has any edit, for a management view. */
    public List<String> scopes() {
        List<String> out = new ArrayList<String>();
        for (String k : options.keySet()) {
            addScope(out, k);
        }
        for (String k : hidden.keySet()) {
            addScope(out, k.substring(HIDDEN_PREFIX.length()));
        }
        for (String k : order.keySet()) {
            addScope(out, k.substring(ORDER_PREFIX.length()));
        }
        return out;
    }

    private static void addScope(List<String> out, String scope) {
        if (!out.contains(scope)) {
            out.add(scope);
        }
    }

    /** How many parameters have been added, across every scope. */
    public int size() {
        return options.size();
    }

    /** True when this scope has any edit at all. */
    public boolean hasEdits(String scope) {
        return !forScope(scope).isEmpty() || hasHidden(scope) || !orderFor(scope).isEmpty();
    }

    /**
     * Undoes every edit for one scope.
     *
     * @return how many entries were removed
     */
    public int reset(String scope) {
        int n = forScope(scope).size() + hiddenFor(scope).size()
                + orderFor(scope).size();
        boolean touched = false;
        for (String flag : new ArrayList<String>(forScope(scope).keySet())) {
            String optionKey = key(scope, flag);
            options.remove(optionKey);
            changed.remove(optionKey);
            removed.add(optionKey);
            touched = true;
        }
        String hiddenKey = HIDDEN_PREFIX + scope;
        if (hidden.remove(hiddenKey) != null) {
            changed.remove(hiddenKey);
            removed.add(hiddenKey);
            touched = true;
        }
        String orderKey = ORDER_PREFIX + scope;
        if (order.remove(orderKey) != null) {
            changed.remove(orderKey);
            removed.add(orderKey);
            touched = true;
        }
        if (touched) {
            save();
        }
        return n;
    }

    /** The key for one added parameter: scope and name, tab separated. */
    static String key(String scope, String flag) {
        return scope + SEP + flag;
    }

    private void load() {
        if (file == null || !file.isFile()) {
            return;
        }
        String text = AtomicFiles.read(file, StandardCharsets.UTF_8);
        if (text == null) {
            reportCorrupt();
            return;
        }
        Properties p = new Properties();
        try {
            p.load(new StringReader(text));
        } catch (Exception e) {
            reportCorrupt();
            return;
        }
        for (String name : p.stringPropertyNames()) {
            String value = p.getProperty(name, "");
            if (name.startsWith(HIDDEN_PREFIX)) {
                hidden.put(name, split(value));
            } else if (name.startsWith(ORDER_PREFIX)) {
                order.put(name, split(value));
            } else {
                options.put(name, value);
            }
        }
    }

    /**
     * 文件在、但读不出来：先留一份 {@code .bak}，再报一次，然后按"没有改动"继续。
     *
     * <p>用户手工加的参数、隐藏的决定、拖出来的顺序都在这一个文件里，所以原文件
     * 必须留下来，而不是被下一次保存直接覆盖。
     */
    private void reportCorrupt() {
        ConfigWarnings.readFailed(file, AtomicFiles.backup(file));
    }

    /**
     * 把这一份改动合并到磁盘上的当前内容里，再原子地写回去。
     *
     * <p>先读磁盘（拿别人刚写的）→ 应用删除（{@link #removed}）→ 应用本实例的修改
     * （{@link #changed}）→ 写盘。合并用原始属性键值，因此本版本读不懂的条目也不会
     * 被这次保存顺手抹掉。写失败时两本账都留着，下一次保存自动重试。
     */
    private void save() {
        if (file == null) {
            return;
        }
        if (changed.isEmpty() && removed.isEmpty()) {
            return;
        }
        Map<String, String> merged = readForMerge();
        if (merged == null) {
            return;   // 读不出来/解析不了：reportCorrupt 已上报，保留待写改动重试。
        }
        for (String key : removed) {
            merged.remove(key);
        }
        for (Map.Entry<String, String> e : changed.entrySet()) {
            merged.put(e.getKey(), e.getValue());
        }
        if (!writeMerged(merged)) {
            return;   // AtomicFiles 已上报；保留待写改动，等下一次保存。
        }
        changed.clear();
        removed.clear();
        // 视图刻意不按磁盘重建：getter 的约定是"本实例读到的东西 + 本实例的改动"。
    }

    /**
     * 读回磁盘当前内容，用于合并。
     *
     * @return 键值表；文件不存在时是空表，文件在但读不出来时是 null（已上报）
     */
    private Map<String, String> readForMerge() {
        Map<String, String> out = new LinkedHashMap<String, String>();
        String text = AtomicFiles.read(file, StandardCharsets.UTF_8);
        if (text == null) {
            if (file.isFile()) {
                reportCorrupt();
                return null;
            }
            return out;
        }
        Properties p = new Properties();
        try {
            p.load(new StringReader(text));
        } catch (Exception e) {
            reportCorrupt();
            return null;
        }
        for (String name : p.stringPropertyNames()) {
            out.put(name, p.getProperty(name, ""));
        }
        return out;
    }

    /** 把合并结果写盘；失败时 AtomicFiles 已上报，返回 false。 */
    private boolean writeMerged(Map<String, String> merged) {
        Properties p = new Properties();
        for (Map.Entry<String, String> e : merged.entrySet()) {
            p.setProperty(e.getKey(), e.getValue());
        }
        StringWriter sw = new StringWriter();
        try {
            p.store(sw, "BioEnv Caller parameter edits, per tool");
        } catch (Exception e) {
            ConfigWarnings.writeFailed(file, e);
            return false;
        }
        // 建目录、临时文件、原子替换都在 AtomicFiles 里；失败时它已经报过。
        return AtomicFiles.write(file, sw.toString(), StandardCharsets.UTF_8);
    }

    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (sb.length() > 0) {
                sb.append(LIST_SEP);
            }
            sb.append(s);
        }
        return sb.toString();
    }

    private static List<String> split(String value) {
        List<String> out = new ArrayList<String>();
        if (value == null || value.isEmpty()) {
            return out;
        }
        for (String s : value.split(LIST_SEP)) {
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }
}

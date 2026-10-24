package bioenv.core;

import java.io.File;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * The user's own notes about a command, kept beside the plugin's other records.
 *
 * <h2>These notes are never parsed</h2>
 *
 * <p>They are a place for the user to write down what the tool's own help does
 * not say: which parameters matter, what a value means in practice, a working
 * command line, a trap. Nothing here reads them to build a parameter form, and
 * nothing should. That is deliberate rather than unfinished:
 *
 * <ul>
 *   <li>A note is prose in whatever language and shape the user finds useful.
 *       Feeding it to the parser would make the form depend on how it happens to
 *       be written, so tidying a sentence could silently change a parameter's
 *       type or make it disappear.</li>
 *   <li>The parameter list is a claim about the tool, and its only honest source
 *       is what the tool printed ({@link HelpParser}). A note is a claim about
 *       what the user understood, which is useful to read and wrong to trust.</li>
 *   <li>It keeps the two failures separate. If a form is wrong, the help text is
 *       what to look at; if the help is unreadable, the notes are where the user
 *       works around it.</li>
 * </ul>
 *
 * <p>So this class is deliberately not referenced from {@link SchemaBuilder},
 * {@link HelpParser}, or {@link Schema}. Adding such a reference would break the
 * property that "my notes" cannot change the form, which is asserted by
 * {@code ToolNotesTest}.
 *
 * <h2>Scoping</h2>
 *
 * <p>Notes belong to a scope: the command alone for its own parameters, and the
 * command plus the subcommand for one of those, e.g. {@code "trimal"} and
 * {@code "bio fasta"}. Subcommands of one tool routinely take parameters with the
 * same names, so a note written for one must not surface under another. This is
 * the same scope key the correction store and the custom-option store use.
 */
public final class ToolNotesStore {

    private static final String FILE_NAME = "tool-notes.properties";

    private final File file;
    /** Scope to the note written for it (this instance's view). */
    private final Map<String, String> notes = new LinkedHashMap<String, String>();
    /**
     * 本实例自上次成功写盘以来改过/新增的键：{@code scope} 到文本。
     *
     * <p>保存时不再把内存里的整张表写回去，而是先读回磁盘、再把这一小份改动叠上去。
     * 这样另一个面板实例（TBtools 可能建不止一个，见 {@code PluginInject}）刚保存的
     * 内容，不会被这份过期快照删掉。
     */
    private final Map<String, String> changed = new LinkedHashMap<String, String>();
    /** 本实例删掉的键。删除也必须记下来，否则一次保存会把别人刚删的笔记又写回去。 */
    private final Set<String> removed = new LinkedHashSet<String>();

    public ToolNotesStore() {
        this(defaultFile());
    }

    public ToolNotesStore(File file) {
        this.file = file;
        load();
    }

    /** Where the notes live, beside the other stores. */
    public static File defaultFile() {
        String home = System.getProperty("user.home");
        File dir = new File(home, ".TBtools" + File.separator + "BioEnvCaller");
        return new File(dir, FILE_NAME);
    }

    public File getFile() {
        return file;
    }

    /**
     * The note written for one scope.
     *
     * @return the text, or an empty string when nothing was written. Never null,
     *         so callers can put it straight into a text area.
     */
    public String get(String scope) {
        if (scope == null) {
            return "";
        }
        String text = notes.get(scope);
        return text == null ? "" : text;
    }

    /** True when a scope has a note with something in it. */
    public boolean has(String scope) {
        return !get(scope).trim().isEmpty();
    }

    /** How many scopes carry a note. */
    public int size() {
        return notes.size();
    }

    /**
     * Writes the note for one scope, replacing what was there.
     *
     * <p>Text that is empty once trimmed removes the entry rather than storing an
     * empty one, so clearing a note and never writing one leave the same state -
     * and the file does not accumulate blank lines for every command ever opened.
     *
     * @param scope the tool and subcommand, e.g. {@code "trimal"}
     * @param text  what to keep; null or blank clears the entry
     * @return true when the stored text changed
     */
    public boolean set(String scope, String text) {
        if (scope == null || scope.trim().isEmpty()) {
            return false;
        }
        String trimmed = text == null ? "" : text.trim();
        String previous = notes.get(scope);
        if (trimmed.isEmpty()) {
            if (previous == null) {
                return false;
            }
            notes.remove(scope);
            forget(scope);
            save();
            return true;
        }
        if (trimmed.equals(previous)) {
            return false;
        }
        // The untrimmed text is what the user wrote and what they should see
        // again; only the emptiness test above uses the trimmed form. Trailing
        // blank lines are not worth discarding an intentional layout for.
        notes.put(scope, text);
        changed.put(scope, text);
        removed.remove(scope);
        save();
        return true;
    }

    /** Forgets the note for one scope. Returns true when there was one. */
    public boolean clear(String scope) {
        if (scope == null || notes.remove(scope) == null) {
            return false;
        }
        forget(scope);
        save();
        return true;
    }

    /** Forgets every note. Returns true when there was anything to forget. */
    public boolean clearAll() {
        if (notes.isEmpty()) {
            return false;
        }
        for (String key : notes.keySet()) {
            forget(key);
        }
        notes.clear();
        save();
        return true;
    }

    /**
     * 记下"这个键被删了"。
     *
     * <p>删掉的键不会进 {@link #changed}：两处都记的话，合并时后写入的一方会赢，
     * 而删除必须是删除。
     */
    private void forget(String scope) {
        changed.remove(scope);
        removed.add(scope);
    }

    /** A snapshot, for a management view or a test. */
    public Map<String, String> all() {
        return new LinkedHashMap<String, String>(notes);
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
            if (!value.trim().isEmpty()) {
                notes.put(name, value);
            }
        }
    }

    /**
     * 文件在、但读不出来：先留一份 {@code .bak}，再报一次，然后按"没有笔记"继续。
     *
     * <p>和以前的 {@code catch (Exception ignored)} 的区别在最后那一步：
     * 用户的笔记不会在被截断的文件上再被覆盖掉，而且他能在界面上看到这行提示。
     */
    private void reportCorrupt() {
        ConfigWarnings.readFailed(file, AtomicFiles.backup(file));
    }

    /**
     * 把这一份改动合并到磁盘上的当前内容里，再原子地写回去。
     *
     * <p>顺序是有意的：<b>先读磁盘</b>（拿别人刚写的东西）→ <b>应用删除</b>
     * （{@link #removed}，所以别人把同一键写回来也删得掉）→ <b>应用本实例的修改</b>
     * （{@link #changed}）→ 写盘。写失败时两本账都留着，下一次保存自动重试。
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
            // 文件在但读不出来/解析不了：reportCorrupt 已经报过，保留待写改动，
            // 下一次保存重试；这一次绝不拿"空内容"覆盖掉原文件。
            return;
        }
        for (String key : removed) {
            merged.remove(key);
        }
        for (Map.Entry<String, String> e : changed.entrySet()) {
            merged.put(e.getKey(), e.getValue());
        }
        if (!writeMerged(merged)) {
            return;   // AtomicFiles 已经报过；保留待写改动，等下一次保存。
        }
        changed.clear();
        removed.clear();
        // 视图刻意不按磁盘重建：getter 的约定是"本实例读到的东西 + 本实例的改动"，
        // 别的实例后来写的内容从磁盘读即可，不该悄悄替换这个实例正在显示的内容。
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
            p.store(sw, "BioEnv Caller tool notes, per tool (never parsed)");
        } catch (Exception e) {
            ConfigWarnings.writeFailed(file, e);
            return false;
        }
        // 建目录、临时文件、原子替换都在 AtomicFiles 里；失败时它已经报过。
        return AtomicFiles.write(file, sw.toString(), StandardCharsets.UTF_8);
    }
}

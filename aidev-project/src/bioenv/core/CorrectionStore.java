package bioenv.core;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Remembers type corrections the user has made, both for the specific option
 * and as a reusable rule.
 *
 * <p>This is layer 5 of the type strategy (DESIGN.md P10, P15). Inference
 * covers the common cases; the long tail is handled by learning from
 * corrections.
 *
 * <p>A correction is stored twice, at two levels of generality:
 *
 * <ul>
 *   <li><b>Exact</b> — {@code <command>\t<option>}, applying only to that one
 *       option. Always wins.</li>
 *   <li><b>Pattern</b> — derived from the option's placeholder or name, so the
 *       decision generalises. Correcting {@code <int>} once means every option
 *       documented the same way is treated as a number from then on.</li>
 * </ul>
 *
 * <p>The pattern level is what makes the plugin improve with use rather than
 * requiring the same correction for every tool. A pattern is only learned when
 * the option offers a genuine signal — an angle-bracketed placeholder or a
 * conventional name — because generalising from an arbitrary flag such as
 * {@code -x} would spread a decision to unrelated tools.
 *
 * <p>Corrections are stored per command and option in a plain properties file
 * under the TBtools home directory. Losing the file only means the user
 * re-corrects.
 */
public final class CorrectionStore {

    private static final String FILE_NAME = "type-corrections.properties";
    /** Separator inside a key; a tab cannot occur in a flag or command name. */
    private static final String SEP = "\t";
    /** Marker for a rule that applies to every command. */
    public static final String ANY_COMMAND = "*";
    private final File file;
    /** Chosen kinds: {@code command\toption} to kind. */
    private final Map<String, Schema.Option.Kind> corrections =
            new LinkedHashMap<String, Schema.Option.Kind>();
    private boolean dirty;

    public CorrectionStore() {
        this(defaultFile());
    }

    public CorrectionStore(File file) {
        this.file = file;
        load();
    }

    /** Where corrections live, using the same home as TBtools' own data. */
    public static File defaultFile() {
        String home = System.getProperty("user.home");
        File dir = new File(home, ".TBtools" + File.separator + "BioEnvCaller");
        return new File(dir, FILE_NAME);
    }

    public File getFile() {
        return file;
    }

    /**
     * Returns the remembered kind for an option, or null when none was chosen.
     *
     * <p>Only an entry made against this same command is returned: one tool's
     * decision never applies to another.
     */
    public Schema.Option.Kind lookup(String command, String option) {
        return lookup(command, option, null);
    }

    /**
     * Returns the kind remembered for one option of one command.
     *
     * <p>Only an entry recorded against this same command is returned. A choice
     * made elsewhere — because another tool happens to spell a flag the same way,
     * or documents it with the same placeholder — is deliberately not applied:
     * tools are independent, and a decision about one says nothing about another.
     */
    public Schema.Option.Kind lookup(String command, String option, String placeholder) {
        return corrections.get(key(command, option));
    }

    /** Convenience overload that reads the placeholder from the option itself. */
    public Schema.Option.Kind lookup(String command, Schema.Option option) {
        if (option == null) {
            return null;
        }
        return lookup(command, option.flag, option.arg);
    }

    /** Number of remembered choices. */
    public int size() {
        return corrections.size();
    }

    /** Remembered choices, for a management view. */
    public Map<String, Schema.Option.Kind> all() {
        return new LinkedHashMap<String, Schema.Option.Kind>(corrections);
    }

    /** Remembered choices belonging to one command, in stored order. */
    public Map<String, Schema.Option.Kind> forCommand(String command) {
        String prefix = (command == null ? ANY_COMMAND : command) + SEP;
        Map<String, Schema.Option.Kind> out =
                new LinkedHashMap<String, Schema.Option.Kind>();
        for (Map.Entry<String, Schema.Option.Kind> e : corrections.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    /**
     * Forgets one stored choice.
     *
     * <p>A remembered choice can turn out to be wrong — the user misclicked, or
     * changed their mind about what a parameter is — so removing it has to be
     * possible rather than leaving them stuck with it.
     *
     * @param storedKey a key from {@link #all()}
     * @return true when something was removed
     */
    public boolean forget(String storedKey) {
        if (storedKey == null) {
            return false;
        }
        boolean removed = corrections.remove(storedKey) != null;
        if (removed) {
            dirty = true;
            save();
        }
        return removed;
    }

    /** Forgets every remembered choice. */
    public int forgetAll() {
        int n = corrections.size();
        corrections.clear();
        dirty = true;
        save();
        return n;
    }

    /** Forgets every choice remembered for one command. */
    public int forgetCommand(String command) {
        Map<String, Schema.Option.Kind> own = forCommand(command);
        for (String k : own.keySet()) {
            corrections.remove(k);
        }
        if (!own.isEmpty()) {
            dirty = true;
            save();
        }
        return own.size();
    }

    /**
     * A readable description of one stored entry, for the management view.
     *
     * <p>Expressed in terms of what the entry does rather than its internal key,
     * since the point of the view is to let the user judge whether the choice
     * still makes sense.
     */
    public static String describe(String storedKey, Schema.Option.Kind kind) {
        if (storedKey == null) {
            return "";
        }
        int i = storedKey.indexOf(SEP);
        if (i < 0) {
            return storedKey + " \u2192 " + kind;
        }
        String command = storedKey.substring(0, i);
        String option = storedKey.substring(i + 1);
        if (ANY_COMMAND.equals(command)) {
            return "any command, option " + option + " \u2192 " + kind;
        }
        return command + " " + option + " \u2192 " + kind;
    }

    /**
     * Records the kind a user chose for one option, and persists it.
     *
     * <p>The choice is kept against the command and the option together, and it
     * applies to nothing else — one tool's decision never changes how another
     * tool's options are shown.
     */
    public void remember(String command, String option, Schema.Option.Kind kind) {
        remember(command, option, kind, null);
    }

    /**
     * Records the kind a user chose for one option, and persists it.
     *
     * <p>The choice is kept against the command and the option together, and it
     * applies to nothing else. It used to also derive a rule from the option's
     * documentation and apply that rule to every other command — so choosing a
     * kind once could silently change how an unrelated tool's options were shown.
     * That is a surprise the user cannot see coming and cannot trace, and the
     * benefit does not justify it: each choice now costs one click, once.
     *
     * @param placeholder retained so existing callers keep working; ignored
     */
    public void remember(String command, String option, Schema.Option.Kind kind,
                         String placeholder) {
        if (option == null || kind == null) {
            return;
        }
        corrections.put(key(command == null ? ANY_COMMAND : command, option), kind);
        dirty = true;
        save();
    }

    /** The command part of a stored key, or the wildcard marker. */
    public static String commandOf(String storedKey) {
        int i = storedKey.indexOf(SEP);
        return i < 0 ? storedKey : storedKey.substring(0, i);
    }

    /** The option part of a stored key. */
    public static String optionOf(String storedKey) {
        int i = storedKey.indexOf(SEP);
        return i < 0 ? "" : storedKey.substring(i + 1);
    }

    private static String key(String command, String option) {
        return (command == null ? ANY_COMMAND : command) + SEP + option;
    }

    private void load() {
        if (file == null || !file.isFile()) {
            return;
        }
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), "UTF-8"));
            Properties p = new Properties();
            p.load(br);
            for (String name : p.stringPropertyNames()) {
                Schema.Option.Kind kind;
                try {
                    kind = Schema.Option.Kind.valueOf(p.getProperty(name).trim());
                } catch (IllegalArgumentException ignored) {
                    // A kind that no longer exists is skipped, not fatal.
                    continue;
                }
                // Entries written by an earlier version may include generalised
                // rules, whose keys carry a "pattern:" prefix. Those applied
                // across tools, which is exactly what this store no longer does,
                // so they are dropped rather than honoured.
                if (name.startsWith("pattern:")) {
                    continue;
                }
                corrections.put(name, kind);
            }
        } catch (Exception ignored) {
            // An unreadable store just means no corrections; never fatal.
        } finally {
            close(br);
        }
    }

    private void save() {
        if (file == null || !dirty) {
            return;
        }
        BufferedWriter bw = null;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            Properties p = new Properties();
            for (Map.Entry<String, Schema.Option.Kind> e : corrections.entrySet()) {
                p.setProperty(e.getKey(), e.getValue().name());
            }
            bw = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(file), "UTF-8"));
            p.store(bw, "BioEnv Caller parameter types, per tool");
            dirty = false;
        } catch (Exception ignored) {
            // Failing to persist must not break the session.
        } finally {
            close(bw);
        }
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

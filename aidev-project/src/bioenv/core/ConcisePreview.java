package bioenv.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The command preview as the pane shows it: the same command, with path
 * arguments written as their last name.
 *
 * <h2>Why the paths are shortened</h2>
 *
 * <p>A path chosen in the file chooser arrives as a full Windows path and is
 * translated to {@code /mnt/c/...} before it reaches the command, so two options
 * can read as a wall of folder names in which the arguments themselves are lost:
 *
 * <pre>
 *   orthofinder -o /mnt/c/Users/qw/Desktop/Results_Sep07_2 -f /mnt/c/Users/qw/Desktop/pep
 *   orthofinder -o Results_Sep07_2 -f pep
 * </pre>
 *
 * <p>The second line is the one a person would write; the first is the same
 * command with the interesting part buried. Only the last name is shown, so a
 * file argument reads as {@code input.fasta} and a directory as
 * {@code Results_Sep07_2} (DESIGN.md P13).
 *
 * <h2>The shown text is still a command</h2>
 *
 * <p>The preview is editable, and a command edited there is the command that
 * runs, verbatim. Shortening it would therefore quietly change what runs: the
 * tool would be handed {@code pep} relative to whatever directory the shell
 * happened to be in. {@link #expand} writes the full paths out again before the
 * text reaches the shell, so the pane stays readable without the run losing its
 * addresses.
 *
 * <h2>When a name is left alone</h2>
 *
 * <p>A name is shortened only when there is no doubt about what it stands for:
 * if the same word appears anywhere else in the command — another option's
 * value, something typed into the raw arguments box — or if two different paths
 * end in the same name, the argument is kept in full. A guess about which path
 * was meant would be worse than a long line, because the failure would be a tool
 * reading or overwriting the wrong file.
 */
public final class ConcisePreview {

    /** The command as the pane shows it. */
    private final String text;

    /** What each short name stands for, keyed by the short name. */
    private final Map<String, String> expansions;

    private ConcisePreview(String text, Map<String, String> expansions) {
        this.text = text;
        this.expansions = expansions;
    }

    /** The command line as the preview pane shows it. */
    public String text() {
        return text;
    }

    /** True when at least one argument was shown shorter than it will run. */
    public boolean isShortened() {
        return !expansions.isEmpty();
    }

    /**
     * The command line with every shortened path written out in full again.
     *
     * <p>A word the pane wrote short is replaced by what it stands for. Anything
     * else — a word the user typed, or one they changed — is passed through
     * exactly as it stands, so editing the preview still means what it says.
     */
    public String expand(String typed) {
        if (typed == null || expansions.isEmpty()) {
            return typed;
        }
        List<int[]> words = wordRanges(typed);
        if (words.isEmpty()) {
            return typed;
        }
        StringBuilder out = new StringBuilder();
        int at = 0;
        for (int[] range : words) {
            out.append(typed, at, range[0]);
            String word = typed.substring(range[0], range[1]);
            String full = expansions.get(unquote(word));
            out.append(full == null ? word : CommandBuilder.quoteValue(full));
            at = range[1];
        }
        out.append(typed, at, typed.length());
        return out.toString();
    }

    /**
     * Builds the shown command for one tool and the values the user expressed.
     *
     * @param tool    the tool being configured
     * @param schema  the schema the values were collected against
     * @param subPath subcommand path, or empty for none
     * @param values  user-expressed values keyed by option flag
     * @param rawArgs free-form arguments, appended verbatim and never shortened
     */
    public static ConcisePreview of(Tool tool, Schema schema, String subPath,
                                    Map<String, ParamValue> values, String rawArgs) {
        List<CommandBuilder.ShownArg> args = CommandBuilder.shownArgs(schema, values);
        List<String> spelledOut = new ArrayList<String>();
        for (CommandBuilder.ShownArg a : args) {
            spelledOut.add(a.text);
        }
        // The whole line as it will run, so the words around each candidate are
        // known: a short name that also occurs elsewhere cannot be put back
        // unambiguously, and is therefore not used.
        Set<String> words = wordsOf(CommandBuilder.assemble(
                tool.command, subPath, spelledOut, rawArgs));

        // First pass: which names could stand in, and which of those two different
        // paths claim. A name claimed twice is never short, whatever it looks like.
        Map<String, String> candidates = new LinkedHashMap<String, String>();
        Set<String> contested = new HashSet<String>();
        for (CommandBuilder.ShownArg a : args) {
            String spelled = unquote(a.text);
            String shortName = a.path ? lastName(spelled) : null;
            if (shortName == null || shortName.equals(spelled)
                    || words.contains(shortName)) {
                continue;
            }
            String claimed = candidates.put(shortName, spelled);
            if (claimed != null && !claimed.equals(spelled)) {
                contested.add(shortName);
            }
        }

        // Second pass: show the command. Every argument either keeps its own text
        // or the one name that stands for it.
        Map<String, String> expansions = new LinkedHashMap<String, String>();
        List<String> shown = new ArrayList<String>();
        for (CommandBuilder.ShownArg a : args) {
            String spelled = unquote(a.text);
            String shortName = a.path ? lastName(spelled) : null;
            if (shortName == null || contested.contains(shortName)
                    || !spelled.equals(candidates.get(shortName))) {
                shown.add(a.text);
                continue;
            }
            shown.add(CommandBuilder.quoteValue(shortName));
            expansions.put(shortName, spelled);
        }

        return new ConcisePreview(
                CommandBuilder.assemble(tool.command, subPath, shown, rawArgs),
                expansions);
    }

    // ------------------------------------------------------------------
    // Paths
    // ------------------------------------------------------------------

    /**
     * The name to show for a path, or null to show the path in full.
     *
     * <p>A comma-separated list — the shape a path-list option takes — is
     * shortened piece by piece, so each entry reads as its own name. One
     * unshowable piece keeps the whole list spelled out: half a list of names
     * would be harder to check against the form than the paths themselves.
     */
    private static String lastName(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        if (path.indexOf(',') < 0) {
            return lastSegment(path);
        }
        StringBuilder sb = new StringBuilder();
        for (String piece : path.split(",", -1)) {
            String name = lastSegment(piece);
            if (name == null) {
                return null;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(name);
        }
        return sb.toString();
    }

    /** The last name of one path, or null when it should be shown in full. */
    private static String lastSegment(String path) {
        String p = path == null ? "" : path.trim();
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        // No folder part: the value is already the name.
        if (p.isEmpty() || p.indexOf('/') < 0) {
            return null;
        }
        // A drive is named by a letter, not by a name, so "/mnt/c" written as "c"
        // would read as a file called c.
        if (p.length() == 6 && p.startsWith("/mnt/")) {
            return null;
        }
        int slash = p.lastIndexOf('/');
        String name = p.substring(slash + 1);
        return name.isEmpty() ? null : name;
    }

    // ------------------------------------------------------------------
    // Shell words
    // ------------------------------------------------------------------

    /**
     * The character ranges of the shell words of a command line, in order.
     *
     * <p>As much of the shell's lexer as is needed to keep a quoted word in one
     * piece: whitespace separates words, quotes group, and a backslash outside
     * single quotes protects the character after it.
     */
    private static List<int[]> wordRanges(String command) {
        List<int[]> out = new ArrayList<int[]>();
        int i = 0;
        int n = command.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(command.charAt(i))) {
                i++;
            }
            if (i >= n) {
                break;
            }
            int start = i;
            while (i < n) {
                char c = command.charAt(i);
                if (c == '\\' && i + 1 < n) {
                    i += 2;
                    continue;
                }
                if (c == '\'' || c == '"') {
                    i = afterQuote(command, i);
                    continue;
                }
                if (Character.isWhitespace(c)) {
                    break;
                }
                i++;
            }
            out.add(new int[]{start, i});
        }
        return out;
    }

    /** The index just past the quote opening at {@code open}. */
    private static int afterQuote(String command, int open) {
        char quote = command.charAt(open);
        int i = open + 1;
        while (i < command.length()) {
            char c = command.charAt(i);
            // Inside single quotes the shell reads every character literally, so a
            // backslash there is a character like any other.
            if (c == '\\' && quote == '"' && i + 1 < command.length()) {
                i += 2;
                continue;
            }
            if (c == quote) {
                return i + 1;
            }
            i++;
        }
        return command.length(); // unterminated: the rest of the line is one word
    }

    /** Every word of a command, as the shell would receive it. */
    private static Set<String> wordsOf(String command) {
        Set<String> out = new HashSet<String>();
        for (int[] range : wordRanges(command)) {
            out.add(unquote(command.substring(range[0], range[1])));
        }
        return out;
    }

    /**
     * A word with its quoting removed, which is the text the shell would pass on.
     *
     * <p>The preview is quoted by this plugin, whose forms are known here: single
     * quotes with {@code '\''} for an apostrophe inside them, and the shell's own
     * backslash escapes everywhere else.
     */
    private static String unquote(String word) {
        StringBuilder sb = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (quote == '\'') {
                // Literal until the closing quote: an apostrophe cannot appear
                // inside, which is why the plugin writes '\'' for one.
                if (c == '\'') {
                    quote = 0;
                } else {
                    sb.append(c);
                }
                continue;
            }
            if (quote == '"') {
                if (c == '"') {
                    quote = 0;
                    continue;
                }
                if (c == '\\' && i + 1 < word.length()) {
                    sb.append(word.charAt(++i));
                    continue;
                }
                sb.append(c);
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (c == '\\' && i + 1 < word.length()) {
                sb.append(word.charAt(++i));
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }
}

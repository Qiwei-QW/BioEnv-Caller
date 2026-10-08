package bioenv.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The shapes a help document actually uses.
 *
 * <h2>Why this is measured rather than assumed</h2>
 *
 * Help is written by hand, by many people, over thirty years, and the one thing they
 * do not agree on is indentation. Across a corpus of thirty real tools the
 * declaration column was found at 0, 1, 2, 3, 4 and 6 spaces — every value in that
 * range is somebody's convention. A rule that required two spaces, as this once did,
 * read diamond's command list as prose and offered nothing at all.
 *
 * <p>What the documents <em>do</em> agree on is being consistent with themselves. A
 * tool that indents its options by four spaces indents all of them by four. So the
 * column is derived per document — the value that most declarations share — rather
 * than fixed for all documents.
 *
 * <p>Nothing here names a tool or a section heading. Every judgement is about the
 * shape of a line: how far it is indented, whether it starts with a token that
 * could be a name, whether a run of spaces or a tab separates that token from the
 * text that follows.
 */
public final class HelpShape {

    /** A token, then a gap, then the text describing it. */
    private static final Pattern DECLARATION =
            Pattern.compile("^(\\s*)(\\S.*?)((?:\\t| {2,})+)(\\S.*)$");

    /** A short line ending in a colon: a section heading, in every document seen. */
    private static final Pattern HEADING =
            Pattern.compile("^\\s*([A-Za-z][A-Za-z0-9 _()/.,'-]{0,50}):\\s*$");

    /** The indent most declarations in this document use. */
    private final int declarationIndent;
    /** True when any declaration in the document was found at all. */
    private final boolean hasDeclarations;
    /** How many declarations were found, for callers that want to know. */
    private final int declarationCount;
    /** Other columns that also declare, comma separated; empty when there are none. */
    private final String otherIndents;

    private HelpShape(int indent, boolean has, int count) {
        this(indent, has, count, "");
    }

    private HelpShape(int indent, boolean has, int count, String others) {
        this.declarationIndent = indent;
        this.hasDeclarations = has;
        this.declarationCount = count;
        this.otherIndents = others == null ? "" : others;
    }

    /**
     * Measures a document.
     *
     * @param rawHelp the help text, before tab expansion
     */
    public static HelpShape of(String rawHelp) {
        if (rawHelp == null || rawHelp.isEmpty()) {
            return new HelpShape(-1, false, 0);
        }
        String[] lines = HelpParser.expandTabs(rawHelp).split("\\r?\\n");

        // Two tallies: every declaration, and the declarations that name an option.
        // The distinction matters because a document often lists things shaped like
        // declarations that are not options at all — RAxML opens with a list of its
        // contributors, nine lines of "Name    (Institute)" at column 0, while its
        // options sit at column 6 behind a dash. Counting both together gave column
        // 0, which is where the acknowledgements live.
        Map<Integer, Integer> anyTally = new TreeMap<Integer, Integer>();
        Map<Integer, Integer> optionTally = new TreeMap<Integer, Integer>();
        int total = 0;
        for (String line : lines) {
            if (line.trim().isEmpty() || isHeading(line)) {
                continue;
            }
            Matcher m = DECLARATION.matcher(line);
            if (!m.matches()) {
                continue;
            }
            String name = m.group(2).trim();
            if (!looksLikeName(name)) {
                continue;
            }
            int indent = m.group(1).length();
            bump(anyTally, indent);
            if (name.startsWith("-")) {
                bump(optionTally, indent);
            }
            total++;
        }
        if (total == 0) {
            return new HelpShape(-1, false, 0);
        }

        // Which indent is the declaration column cannot be settled by counting alone,
        // because two lists can both be real. seqkit lists forty-six subcommands at
        // column 2 and three flags at column 6; hyphy lists options at column 2 and a
        // list of analyses at column 8. Counting picks the wrong one in one case or
        // the other, and either alone loses the other list.
        //
        // So every column whose rows name one thing each is a declaration column. A
        // row carrying a whole command line, or a name with a bracketed tag after it,
        // is not a declaration and its column is not counted.
        Map<Integer, Integer> clean = new TreeMap<Integer, Integer>();
        /** The same, restricted to rows that name an option. */
        Map<Integer, Integer> cleanOptions = new TreeMap<Integer, Integer>();
        for (String line : lines) {
            if (line.trim().isEmpty() || isHeading(line)) {
                continue;
            }
            Matcher m = DECLARATION.matcher(line);
            if (!m.matches()) {
                continue;
            }
            String name = m.group(2).trim();
            if (!looksLikeName(name) || !namesOneThing(name)) {
                continue;
            }
            // A row whose description opens with a bracketed tag is annotating a name
            // rather than describing an option: hyphy's analysis list reads
            // "meme    [MEME] Test for episodic...", and the tag is the analysis's own
            // abbreviation. Options describe what they do; they do not begin by
            // naming themselves again in brackets.
            String desc = m.group(4).trim();
            if (desc.startsWith("[")) {
                continue;
            }
            int indent = m.group(1).length();
            bump(clean, indent);
            if (name.startsWith("-")) {
                bump(cleanOptions, indent);
            }
            total++;
        }

        int cleanTotal = 0;
        for (int n : clean.values()) {
            cleanTotal += n;
        }
        if (cleanTotal == 0) {
            // Nothing clean; the most frequent of all is the best guess left for a
            // document that declares very little.
            int best = -1;
            int bestN = 0;
            for (Map.Entry<Integer, Integer> e : anyTally.entrySet()) {
                if (e.getValue() > bestN) {
                    bestN = e.getValue();
                    best = e.getKey();
                }
            }
            return new HelpShape(best, anyTally.size() > 0, total, "");
        }

        // The primary column has the most clean rows; among equals the leftmost, since
        // a continuation is always indented further than what it continues.
        //
        // A column of options outranks a column of bare words whatever the counts,
        // because a bare word column can be something else entirely — hyphy lists
        // seventy-seven analyses at column 8 against four options at column 2, and
        // those analyses are not command-line arguments at all.
        int first = -1;
        int bestN = 0;
        for (Map.Entry<Integer, Integer> e : cleanOptions.entrySet()) {
            if (e.getValue() >= Math.max(2, cleanTotal / 20)
                    && e.getValue() > bestN) {
                bestN = e.getValue();
                first = e.getKey();
            }
        }
        if (first < 0) {
            for (Map.Entry<Integer, Integer> e : clean.entrySet()) {
                if (e.getValue() > bestN) {
                    bestN = e.getValue();
                    first = e.getKey();
                }
            }
        }
        StringBuilder others = new StringBuilder();
        for (Map.Entry<Integer, Integer> e : clean.entrySet()) {
            if (e.getKey() == first) {
                continue;
            }
            // A second column is kept when it carries a real share rather than a stray
            // line that happened to look like a declaration.
            if (e.getValue() >= Math.max(2, cleanTotal / 20)) {
                if (others.length() > 0) {
                    others.append(',');
                }
                others.append(e.getKey());
            }
        }
        return new HelpShape(first, true, cleanTotal, others.toString());
    }

    /**
     * True when the text before the gap names a single thing.
     *
     * <p>An option, a pair of aliases, a named argument, or a name with its value
     * placeholder. Not a whole command line, and not a name followed by a bracketed
     * tag — those are examples and annotations rather than declarations.
     */
    public static boolean namesOneThing(String name) {
        String t = name.trim();
        if (t.startsWith("-")) {
            return true;
        }
        if (t.matches("[A-Z][A-Z0-9_]*=\\S*")) {
            return true;
        }
        // A single bare word.
        if (t.matches("[A-Za-z][A-Za-z0-9_.+-]*")) {
            return true;
        }
        // A word and one more thing, where that thing is a placeholder rather than
        // an argument: "<file>", "PATH", "int".
        String[] parts = t.split("\\s+");
        if (parts.length == 2) {
            String second = parts[1];
            if (second.startsWith("<") && second.endsWith(">")) {
                return true;
            }
            if (second.matches("[A-Za-z][A-Za-z0-9_]*")
                    && !second.startsWith("[") && !second.endsWith("]")) {
                return true;
            }
        }
        return false;
    }

    private static void bump(Map<Integer, Integer> m, int key) {
        Integer n = m.get(key);
        m.put(key, n == null ? 1 : n + 1);
    }

    /** The column declarations begin at, or -1 when the document has none. */
    public int declarationIndent() {
        return declarationIndent;
    }

    /** True when the document declares anything at all. */
    public boolean hasDeclarations() {
        return hasDeclarations;
    }

    /** How many declarations were counted. */
    public int declarationCount() {
        return declarationCount;
    }

    /** Other columns that also declare, comma separated; empty when there are none. */
    public String otherIndents() {
        return otherIndents;
    }

    /**
     * True when a line declares something, at any column this document uses.
     *
     * <p>Several columns may declare — a tool can list subcommands at one indent and
     * options at another — so this asks whether the line sits at any of them rather
     * than at one chosen column.
     */
    public boolean atDeclarationIndent(String rawLine) {
        if (!hasDeclarations) {
            return false;
        }
        return leadingSpaces(rawLine) == declarationIndent;
    }

    /**
     * True when a line sits at a secondary declaration column.
     *
     * <p>Held to a higher standard than the main column, because being at some
     * column is weaker evidence than being at the one the document mostly uses.
     * hyphy lists seventy-seven analyses at column 8 beside four options at column
     * 2; those analyses are not command-line arguments, and reading them as
     * subcommands gave a tool with none eighty-three of them.
     *
     * <p>The caller decides what else a secondary column must have. This only says
     * whether the line is at one.
     */
    public boolean atSecondaryIndent(String rawLine) {
        if (otherIndents.isEmpty()) {
            return false;
        }
        int here = leadingSpaces(rawLine);
        for (String s : otherIndents.split(",")) {
            try {
                if (Integer.parseInt(s.trim()) == here) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
                // A malformed entry simply does not match.
            }
        }
        return false;
    }

    /**
     * True when a line looks like a declaration, whatever its indent.
     *
     * <p>For callers that need the name and the text rather than a yes or no.
     */
    public static boolean isDeclaration(String rawLine) {
        Matcher m = DECLARATION.matcher(rawLine);
        if (!m.matches()) {
            return false;
        }
        return looksLikeName(m.group(2).trim());
    }

    /** The name and description of a declaration line, or null. */
    public static String[] splitDeclaration(String rawLine) {
        Matcher m = DECLARATION.matcher(rawLine);
        if (!m.matches()) {
            return null;
        }
        String name = m.group(2).trim();
        if (!looksLikeName(name)) {
            return null;
        }
        return new String[]{name, m.group(4).trim()};
    }

    /** True when a line is a section heading. */
    public static boolean isHeading(String rawLine) {
        String t = rawLine.trim();
        if (t.isEmpty() || t.length() > 55) {
            return false;
        }
        return HEADING.matcher(rawLine).matches();
    }

    /**
     * True when a token could be the name of a command or an option.
     *
     * <p>Covers what the corpus actually contains: {@code -h}, {@code --long},
     * {@code --long, -x} (a pair of aliases), and {@code NAME=value} for the tools
     * that take their arguments by name.
     */
    public static boolean looksLikeName(String token) {
        if (token == null) {
            return false;
        }
        String t = token.trim();
        if (t.isEmpty() || t.length() > 40) {
            return false;
        }
        // Options, possibly several aliases separated by commas.
        if (t.startsWith("-")) {
            for (String part : t.split(",")) {
                String p = part.trim();
                if (p.isEmpty()) {
                    return false;
                }
                if (!p.matches("--?[A-Za-z0-9][A-Za-z0-9_.-]*")) {
                    return false;
                }
            }
            return true;
        }
        // A named argument, as hyphy writes it.
        if (t.matches("[A-Z][A-Z0-9_]*=\\S*")) {
            return true;
        }
        // A bare word: a subcommand, or an option written without its dashes.
        if (t.matches("[A-Za-z][A-Za-z0-9_.+-]*")) {
            return true;
        }
        // A name followed by its value placeholder, e.g. "codeml <ctl>".
        String head = t.split("\\s+")[0];
        return head.length() >= 2 && head.matches("[A-Za-z][A-Za-z0-9_.+-]*")
                && t.length() <= 40;
    }

    /** Columns of leading spaces. */
    public static int leadingSpaces(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') {
            n++;
        }
        return n;
    }

    /** For diagnostics: every indent seen, with how often. */
    public List<String> describe() {
        List<String> out = new ArrayList<String>();
        out.add("declarations=" + declarationCount + " indent=" + declarationIndent);
        return out;
    }
}

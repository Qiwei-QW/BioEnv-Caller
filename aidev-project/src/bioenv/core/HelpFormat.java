package bioenv.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Describes the structure of one help document.
 *
 * <p>Help output comes in several shapes, and a parser that assumes one shape
 * misreads the others. This class examines a document and reports which
 * structural features it actually has, so the rest of the pipeline can use
 * whatever is present instead of guessing.
 *
 * <h2>Components, not a single family</h2>
 *
 * <p>The original design called for classifying a document into exactly one
 * family and dispatching to a parser for that family. Measurement changed this.
 * Documents routinely combine features — a usage synopsis <em>and</em> alias
 * groups <em>and</em> named sections — and a single-choice classifier has to
 * discard two of the three. Worse, its one decision is a single point of failure:
 * classify wrongly and everything downstream is wrong.
 *
 * <p>So each feature is detected independently and reported as a boolean. A
 * document that has both a usage synopsis and alias groups is described as having
 * both, and the parser uses both. {@link Report#dominant} is kept only for
 * diagnostics and for deciding whether the document is usable at all.
 *
 * <p>Nothing here refers to any tool by name (DESIGN.md P1); every test is a
 * statement about the shape of text.
 */
public final class HelpFormat {

    /** How well a document can be read, for reporting and for degradation. */
    public enum Family {
        /** Declares option types in a dedicated column: the strongest source. */
        TYPE_COLUMN,
        /** Uses the two fixed section names a generated parser prints. */
        ARGPARSE,
        /** Groups options under named sections. */
        NAMED_SECTIONS,
        /** Lists options, several spellings per line. */
        ALIAS_GROUPS,
        /** A usage synopsis that names its parameters. */
        USAGE_DECLARED,
        /** A plain list of options and nothing else. */
        FLAT_LIST,
        /** No option list could be found; entry must fall back to raw arguments. */
        EMPTY
    }

    /** What a document turned out to contain. */
    public static final class Report {
        public boolean hasTypeColumn;
        public boolean hasArgparseSections;
        public boolean hasNamedSections;
        public boolean hasAliasGroups;
        public boolean hasUsageLine;
        /** True when the synopsis actually names parameters rather than only the program. */
        public boolean usageNamesParams;
        public boolean hasOptionList;
        /** Section headings found, in order of appearance. */
        public final List<String> sectionNames = new ArrayList<String>();
        /** Lines that look like options. */
        public int optionLineCount;
        /** Lines that yield a type column. */
        public int typeColumnLineCount;
        /** The best single description of the document, for diagnostics. */
        public Family dominant = Family.EMPTY;

        /** True when nothing usable was found and the caller should degrade. */
        public boolean isUnusable() {
            return !hasOptionList && !hasUsageLine && !hasTypeColumn;
        }

        /** A short human-readable summary, used in the UI and in logs. */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append(dominant);
            List<String> features = new ArrayList<String>();
            if (hasTypeColumn) {
                features.add("type column x" + typeColumnLineCount);
            }
            if (hasArgparseSections) {
                features.add("generated sections");
            }
            if (hasNamedSections) {
                features.add("sections:" + sectionNames.size());
            }
            if (hasAliasGroups) {
                features.add("alias groups");
            }
            if (hasUsageLine) {
                features.add("usage synopsis");
            }
            if (hasOptionList) {
                features.add(optionLines() + " option lines");
            }
            for (String f : features) {
                sb.append("  |  ").append(f);
            }
            return sb.toString();
        }

        private String optionLines() {
            return String.valueOf(optionLineCount);
        }
    }

    private HelpFormat() {
    }

    /** An option line: an indented flag, possibly bracketed. */
    private static final Pattern OPTION_LINE = Pattern.compile(
            "^\\s{0,10}\\[?-{1,2}[A-Za-z][A-Za-z0-9_.-]*");

    /**
     * A section heading: a short capitalised phrase on a line of its own, ending
     * in a colon.
     */
    private static final Pattern SECTION_HEADING = Pattern.compile(
            "^\\s{0,6}([A-Z][A-Za-z0-9 _/()'-]{1,45}):\\s*$");

    /** The section names a generated (argparse-style) parser always prints. */
    private static final Pattern GENERATED_SECTION = Pattern.compile(
            "(?i)^\\s*(positional arguments|optional arguments|required arguments|"
            + "named arguments|options|global options|subcommands|commands):\\s*$");

    /**
     * A usage synopsis.
     *
     * <p>Case-insensitive, because the capitalised spelling {@code Usage:} is at
     * least as common as the lower-case one. Matching only the lower-case form
     * meant the synopsis went unrecognised in those documents — and the synopsis
     * is the one place requiredness is stated.
     */
    private static final Pattern USAGE = Pattern.compile(
            "(?i)^\\s*(usage|synopsis)\\s*[:=]?\\s+\\S");

    /** Two spellings of one option on one line: "-h, --help" or "-a|--alpha". */
    private static final Pattern ALIAS_GROUP = Pattern.compile(
            "\\s{0,6}(-{1,2}[A-Za-z][A-Za-z0-9_.-]*)\\s*[,|]\\s*"
            + "(-{1,2}[A-Za-z][A-Za-z0-9_.-]*)");

    /** A parameter declared inside a usage synopsis. */
    private static final Pattern USAGE_PARAM = Pattern.compile(
            "(?:-{1,2}[A-Za-z][A-Za-z0-9_-]*|<[^>]{1,30}>)");

    /**
     * Examines a help document and reports its structure.
     *
     * <p>One pass over the lines. Nothing is run and nothing is assumed about
     * which tool produced the text.
     */
    public static Report analyse(String helpText) {
        Report r = new Report();
        if (helpText == null || helpText.trim().isEmpty()) {
            r.dominant = Family.EMPTY;
            return r;
        }
        String[] lines = helpText.split("\n", -1);
        int aliasGroups = 0;

        for (String raw : lines) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (USAGE.matcher(raw).find()) {
                r.hasUsageLine = true;
                // Whether the synopsis actually names parameters is a separate
                // fact: a synopsis of positional arguments alone states nothing
                // about the options, whereas one containing flags carries their
                // requiredness.
                if (USAGE_PARAM.matcher(raw).find()) {
                    r.usageNamesParams = true;
                }
            }
            Matcher gen = GENERATED_SECTION.matcher(raw);
            if (gen.matches()) {
                r.hasArgparseSections = true;
                if (!r.sectionNames.contains(trimmed)) {
                    r.sectionNames.add(stripColon(trimmed));
                }
                continue;
            }
            Matcher sec = SECTION_HEADING.matcher(raw);
            if (sec.matches()) {
                r.hasNamedSections = true;
                String name = sec.group(1).trim();
                if (!r.sectionNames.contains(name)) {
                    r.sectionNames.add(name);
                }
                continue;
            }
            if (OPTION_LINE.matcher(raw).find()) {
                r.hasOptionList = true;
                r.optionLineCount++;
                if (ALIAS_GROUP.matcher(raw).find()) {
                    aliasGroups++;
                }
                if (TypeColumn.detect(raw) != null) {
                    r.typeColumnLineCount++;
                }
            }
        }

        // One occurrence is enough. The shape "-x, --long" is specific enough
        // that it does not arise by accident, and every document has few options
        // to begin with — a generated-parser document may show exactly one group.
        r.hasAliasGroups = aliasGroups >= 1;
        // A document's own claim to a type column is only credible when the shape
        // repeats and accounts for most of it.
        //
        // Requiring a bare minimum count was wrong: the simplest tools in the
        // sample document only two options in total, both with a type column, and
        // a threshold of three rejected them. The combination used here — at
        // least two, and at least half of all option lines — accepts those while
        // still rejecting a document with one or two incidental matches among
        // many ordinary lines.
        r.hasTypeColumn = r.typeColumnLineCount >= 2
                && r.typeColumnLineCount * 2 >= r.optionLineCount;

        r.dominant = dominantOf(r, aliasGroups);
        return r;
    }

    /**
     * Names the single best description of a document, for diagnostics.
     *
     * <p>Ordered by how much the feature tells a parser, strongest first. This is
     * <b>not</b> used to choose a parser — parsing uses every component present —
     * only to summarise and to decide whether the document is usable.
     */
    private static Family dominantOf(Report r, int aliasGroups) {
        if (r.hasTypeColumn) {
            return Family.TYPE_COLUMN;
        }
        if (r.hasArgparseSections) {
            return Family.ARGPARSE;
        }
        if (r.hasNamedSections && r.hasOptionList) {
            return Family.NAMED_SECTIONS;
        }
        // A synopsis that names parameters is ranked above alias groups: it
        // states requiredness, which alias grouping does not.
        if (r.hasUsageLine && r.usageNamesParams && r.hasOptionList) {
            return Family.USAGE_DECLARED;
        }
        if (r.hasAliasGroups) {
            return Family.ALIAS_GROUPS;
        }
        if (r.hasUsageLine && r.hasOptionList) {
            return Family.USAGE_DECLARED;
        }
        if (r.hasOptionList) {
            return Family.FLAT_LIST;
        }
        return Family.EMPTY;
    }

    private static String stripColon(String s) {
        String t = s.trim();
        return t.endsWith(":") ? t.substring(0, t.length() - 1).trim() : t;
    }
}

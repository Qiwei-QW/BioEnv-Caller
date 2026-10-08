package bioenv.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns arbitrary command-line help text into a {@link Schema}.
 *
 * <p>This class is the framework's core. It is written against properties that
 * hold for essentially all GNU-style command-line tools, and it contains no
 * knowledge of any particular tool (DESIGN.md P1, P3).
 *
 * <h2>What it recognises</h2>
 *
 * The parser is structured around three observations that hold regardless of
 * which tool produced the text:
 *
 * <ol>
 *   <li><b>An option line starts with a flag.</b> A flag is a token beginning
 *       with {@code -} or {@code --} followed by a letter. Lines where the first
 *       token is a flag are option lines; everything else is prose, a heading,
 *       or a continuation of the previous option's description.</li>
 *   <li><b>A heading is a short line ending in a colon.</b> Headings group the
 *       options that follow. Some tools also use them to introduce command
 *       lists, which is how subcommand tools are detected.</li>
 *   <li><b>A description follows the flag and its metavariable.</b> The split
 *       point is found by column alignment: in virtually every help format the
 *       description begins at a fixed column, and the gap immediately before it
 *       is at least two spaces.</li>
 * </ol>
 *
 * <h2>How ambiguous cases are resolved</h2>
 *
 * The hard part is distinguishing a metavariable ({@code <inputfile>},
 * {@code FILE}, {@code NUM[G|M]}) from the first word of a description
 * ({@code Print}, {@code Specify}). The rule is deliberately conservative: a
 * token is only a metavariable if it is angle-bracketed, or all-caps with at
 * least two letters. This avoids the failure mode where {@code "-h  Print this"}
 * is parsed as flag {@code -h} with argument {@code P} and description
 * {@code "rint this"}, silently corrupting the text.
 *
 * <p>Where the format is genuinely unparseable the result is simply an empty
 * schema; the caller degrades to raw command entry (DESIGN.md P6) rather than
 * presenting wrong data.
 */
public final class HelpParser {

    // ------------------------------------------------------------------
    // Structural patterns (no tool-specific content)
    // ------------------------------------------------------------------

    /**
     * A flag token: {@code -x}, {@code --long}, or a comma-separated group.
     *
     * <p>A trailing {@code =value} is not part of the flag. It is captured
     * separately because its presence means the option takes a value even when
     * the author left the value blank — {@code -OUTFILE=} still needs a file
     * name, and treating it as a switch would leave the user unable to supply one
     * (DESIGN.md P25).
     *
     * <p>The value part is a single whitespace-delimited word. It must not run on
     * into the description: layouts write {@code -OUTFILE=    :file name}, and a
     * looser pattern captures {@code :file} as the value.
     */
    /**
     * A flag, or a run of aliases written together as one name.
     *
     * <p>Handles {@code -h, --help} and {@code -s '', --species ''}. Group 1 is the
     * optional {@code =value} on the last alias, used by the parser to tell a value
     * position from a description.
     */
    private static final Pattern FLAG_GROUP = Pattern.compile(
            "^-{1,2}[A-Za-z][A-Za-z0-9_.-]*"
            + "(?:\\s*[,|]\\s*-{1,2}[A-Za-z][A-Za-z0-9_.-]*)*"
            + "(\\s*=\\s*([^\\s:=][^\\s:]*)?)?");

    /** A short line ending in a colon that is plausibly a heading. */
    private static final Pattern HEADING = Pattern.compile(
            "^(\\S[^:]{0,60}):\\s*$");

    /**
     * A heading that introduces worked examples.
     *
     * <p>The trailing colon is optional because both spellings occur:
     * {@code Some Examples:} and {@code Examples} appear in real help text.
     *
     * <p>This is a statement about how help documents are organised, not about any
     * program: an author who separates declarations from demonstrations does so
     * with a heading, in every language that uses this convention.
     */
    private static final Pattern EXAMPLE_HEADING = Pattern.compile(
            "(?i)^\\s*(some\\s+|more\\s+|usage\\s+|the\\s+)?examples?\\s*:?\\s*$");

    /** A heading that introduces a usage synopsis. */
    private static final Pattern USAGE_HEADING = Pattern.compile(
            "(?i)^\\s*(basic\\s+|general\\s+|typical\\s+|common\\s+)?"
            + "(usage|synopsis)\\s*:?\\s*$");

    /**
     * Classifies a line that may be a section heading.
     *
     * <p>Called before anything else in the main loop, because a heading decides
     * how the lines after it are read.
     *
     * @return {@code TRUE} when the heading introduces examples, {@code FALSE}
     *         when it introduces any other section, or {@code null} when the line
     *         is not a heading
     */
    static Boolean headingRole(String trimmed) {
        if (trimmed.isEmpty() || trimmed.length() > 60) {
            return null;
        }
        // An option line is never a heading, whatever it looks like.
        if (startsWithFlag(trimmed)) {
            return null;
        }
        if (EXAMPLE_HEADING.matcher(trimmed).matches()) {
            return Boolean.TRUE;
        }
        if (USAGE_HEADING.matcher(trimmed).matches()) {
            return Boolean.FALSE;
        }
        // Any other short heading ends an examples section, which is what makes
        // the section's extent bounded even when the help is long.
        Matcher h = HEADING.matcher(trimmed);
        if (h.matches() && isPlausibleHeading(h.group(1).trim())) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** {@code name<spaces>description} — a command or subcommand listing row. */
    private static final Pattern LIST_ROW = Pattern.compile(
            "^([A-Za-z][A-Za-z0-9_.+-]{0,30})\\s{2,}(\\S.*)$");

    /**
     * {@code program subcommand<spaces>: description} — a listing that repeats the
     * program's name on every row.
     *
     * <p>Some tools introduce each subcommand with the program name, as in:
     * <pre>
     *   bio search    : search for information
     *   bio fetch     : download GenBank/ENSEMBL data
     * </pre>
     *
     * <p>The row is written as {@code program subcommand} with a single space, then
     * the aligned description. {@link #LIST_ROW} cannot match it: its first group
     * stops at the space, and the remainder is neither aligned nor a description.
     * Without this pattern the whole listing is discarded and the tool looks like
     * one that prints no options at all.
     *
     * <p>The second word must not begin with a dash. A row such as
     * {@code easel -h : show overall brief help summary} repeats the program name
     * too, but the second word is an option being explained, not a subcommand, and
     * treating it as one would invent a command named {@code -h}.
     */
    private static final Pattern PREFIXED_LIST_ROW = Pattern.compile(
            "^(\\S+)\\s+([A-Za-z][A-Za-z0-9_.+-]{0,30})\\s*:\\s*(\\S.*)$");

    /** A range stated as {@code [0 - 1]} or {@code (0-100)} or {@code range: [0-1]}. */
    private static final Pattern RANGE = Pattern.compile(
            "range[:\\s]*[\\[(]\\s*(-?\\d+(?:\\.\\d+)?)\\s*[-–]\\s*(-?\\d+(?:\\.\\d+)?)\\s*[\\])]",
            Pattern.CASE_INSENSITIVE);

    /** A default stated as {@code [10.0]} or {@code (default: 1)}. */
    private static final Pattern DEFAULT_BRACKET = Pattern.compile("\\[([^\\]]{1,24})\\]");
    private static final Pattern DEFAULT_PAREN = Pattern.compile(
            "\\(\\s*default\\s*:?\\s*([^)]{1,24})\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEFAULT_WORD = Pattern.compile(
            "\\bdefault\\s*[:=]?\\s*([A-Za-z0-9_.|/-]{1,20})", Pattern.CASE_INSENSITIVE);

    /** An enumerated choice list, e.g. {@code BIN, DNA, AA} or {@code dna|rna}. */
    private static final Pattern CHOICES = Pattern.compile(
            "\\b([A-Za-z][A-Za-z0-9_.-]{1,14}(?:\\s*[|,]\\s*[A-Za-z][A-Za-z0-9_.-]{1,14}){1,9})\\b");


    /** Words indicating an option may be repeated. */
    private static final Pattern REPEATABLE = Pattern.compile(
            "\\b(may be (?:given |used |specified )?(?:multiple times|repeated)|"
            + "can be (?:given |used )?(?:multiple times|repeated)|"
            + "repeatable|more than once)\\b", Pattern.CASE_INSENSITIVE);

    /** Metavariable shape: angle-bracketed, or all-caps with 2+ letters. */
    private static final Pattern METAVAR = Pattern.compile(
            "^(<[^>]{1,40}>|[A-Z][A-Z0-9_]{1,20}|[A-Z][A-Za-z0-9_.-]*"
            + "(?:\\[[^\\]]{0,20}\\])?(?:\\|[A-Za-z0-9_.-]+)*"
            + "(?:\\s*,\\s*\\.\\.\\.[A-Za-z0-9_.|\\[\\]]+)?)$");

    /** Upper bound on a plausible single-line help entry. */
    private static final int MAX_LINE = 4000;

    /**
     * A row inside a candidate listing: a short token followed by a
     * description, e.g. {@code "      msa          use MSA"}.
     */
    private static final Pattern CANDIDATE_ROW = Pattern.compile(
            "^\\s{2,}([A-Za-z][A-Za-z0-9_.-]{0,20})\\s{2,}(\\S.*)$");

    private HelpParser() {
    }

    /**
     * Width a tab is taken to occupy when a help text is read.
     *
     * <p>Tabs are expanded before parsing, because every layout rule in this class
     * counts characters: a "column" is a character offset and a gap is a run of
     * characters. A tab is one character but several columns wide, so a line
     * separating its description with a single tab looked like it had a one-column
     * gap — too narrow to be a separator — and its value placeholder was then read
     * as the first word of the description.
     *
     * <p>Eight is the conventional tab stop and matches what terminals use when
     * they display such a document, which is the layout the reader of the help text
     * sees.
     */
    private static final int TAB_WIDTH = 8;

    /**
     * Expands tabs to the next multiple of {@link #TAB_WIDTH}.
     *
     * <p>Counted per line, not over the whole text, because a tab stop is a column
     * position and each line starts again at column zero.
     */
    public static String expandTabs(String text) {
        if (text == null || text.indexOf('\t') < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length() + 64);
        int column = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                out.append(c);
                column = 0;
            } else if (c == '\t') {
                int width = TAB_WIDTH - (column % TAB_WIDTH);
                for (int k = 0; k < width; k++) {
                    out.append(' ');
                }
                column += width;
            } else {
                out.append(c);
                column++;
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // Entry point
    // ------------------------------------------------------------------

    /**
     * Parses help text into a schema.
     *
     * @param rawText  help output with stdout and stderr already merged
     * @param command  the command name, used only to strip a self-referential
     *                 usage line; never matched against a known-tool list
     */
    public static Schema parse(String rawText, String command) {
        Schema s = new Schema();
        // Terminal control sequences are removed here as well as at the WSL
        // boundary, because text also arrives from the schema cache — including
        // caches written before the boundary cleaned anything. Stripping twice
        // costs nothing and means the parser only ever sees plain text.
        s.rawHelp = AnsiText.strip(rawText);
        // Kept before anything can rewrite it: this is the copy a reset restores.
        s.originalHelp = s.rawHelp;
        if (s.rawHelp.trim().isEmpty()) {
            return s;
        }

        // Tabs are expanded before anything reads a column, so a tab-separated
        // document lays out the way the reader sees it rather than as single
        // characters. The stored raw help keeps its tabs, so the user still sees
        // what the tool actually printed.
        String[] lines = expandTabs(s.rawHelp).split("\\r?\\n");
        String currentGroup = "";
        Schema.Option pending = null;
        boolean inCommandList = false;
        // Column at which descriptions start, learned from the first clear
        // option line and then used to split subsequent ones.
        int descColumn = -1;

        // Whether this document declares types in a column is a property of the
        // whole document, not of one line: the shape has to repeat before it means
        // anything. Deciding it here keeps the per-line parser from treating a
        // single alignment accident as a declaration (see HelpFormat).
        HelpFormat.Report format = HelpFormat.analyse(s.rawHelp);
        s.format = format;
        boolean useTypeColumn = format.hasTypeColumn;

        // Where this document puts its declarations, measured from the document
        // itself. Fixed thresholds do not survive contact with real help: the column
        // is 0 in diamond, 6 in RAxML, 4 in trimAl and 2 in iqtree.
        HelpShape shape = HelpShape.of(s.rawHelp);

        /**
         * True while the lines being read are still the heading's own block.
         *
         * <p>A secondary column is trusted only inside such a block. What separates
         * the two cases that matter is not distance but interruption: seqkit's
         * "Commands for Basic Operation:" is followed immediately by its commands,
         * while hyphy's "Select a standard analysis from the list :" is followed by a
         * paragraph of prose before the analyses begin. A list the document
         * introduces directly is one it means to present; rows appearing after
         * intervening prose are that prose's continuation.
         */
        boolean directlyUnderHeading = false;

        List<String[]> commandRows = new ArrayList<String[]>();
        // Lines belonging to a usage synopsis, harvested after the main pass.
        List<String> usageLines = new ArrayList<String>();
        /** True while inside a synopsis run, so its wrapped lines are gathered. */
        boolean inSynopsisBlock = false;
        /** True once an option listing has begun, after which no synopsis is expected. */
        boolean inOptionSection = false;
        /** True while the rows being read belong to a table rather than a command list. */
        boolean lastRowWasTable = false;
        /**
         * True while the lines being read are worked examples.
         *
         * <p>An example is a demonstration, not a declaration. It shows one way to
         * combine options; it does not say those options exist, nor that they
         * belong together, nor that any of them is required. Reading examples as
         * declarations invents parameters that no part of the help defines — the
         * parameters then appear in the form with no description and no type.
         */
        boolean inExamples = false;

        for (String line : lines) {
            if (line.length() > MAX_LINE) {
                continue;
            }
            // Name decoration is removed once, here, so that every later test —
            // including "does this line start with a flag" and the arithmetic
            // that locates the description column — works on the same text.
            // Doing it later would leave the option-line test rejecting entries
            // whose names are bracketed.
            String rawLine = stripNameDecoration(line);
            String trimmed = rawLine.trim();
            if (trimmed.isEmpty()) {
                pending = null;
                continue;
            }
            if (WslBridge.isCondaNoise(trimmed)) {
                continue;
            }
            // --- heading, first of all ---
            //
            // A heading is decided before anything else, because every other rule
            // depends on knowing which section it is in. The tests below look at a
            // line's shape; a heading is short, ends in a colon and has nothing after
            // it, which no declaration, synopsis or wrapped description does. Running
            // them first is how seqkit's "Commands for Basic Operation:" came to be
            // swallowed as a continuation of the synopsis that "Usage:" had opened,
            // and with it the whole list of commands it introduced.
            if (!startsWithFlag(trimmed) && HEADING.matcher(trimmed).matches()) {
                String name = trimmed.substring(0, trimmed.length() - 1).trim();
                if (isPlausibleHeading(name)) {
                    currentGroup = name;
                    inCommandList = isCommandHeading(name);
                    directlyUnderHeading = true;
                    if (!s.groups.contains(name)) {
                        s.groups.add(name);
                    }
                    pending = null;
                    inSynopsisBlock = false;
                    // Entering or leaving the examples section is what the heading
                    // decides; its other duties are done above.
                    Boolean role = headingRole(trimmed);
                    if (role != null) {
                        inExamples = role.booleanValue();
                    }
                    continue;
                }
            }

            // A line inside an examples section is a demonstration, not a
            // declaration, and is skipped — until a line beginning with a flag, which
            // is a real declaration and therefore ends the section.
            if (inExamples) {
                if (!startsWithFlag(trimmed)) {
                    continue;
                }
                inExamples = false;
            }
            // A usage synopsis is not an option entry, but the parameters it
            // names still have to be captured: required inputs and outputs are
            // often declared only here (DESIGN.md P18).
            //
            // An option entry always takes precedence. A synopsis is only
            // considered when the line is not itself a valid option, because
            // both share the shape "starts with a flag" and a synopsis test that
            // ran first would swallow real entries — losing their descriptions
            // and, with them, their types and candidate values.
            boolean isOptionEntry = startsWithFlag(trimmed);
            boolean synopsisLine = false;
            if (!isOptionEntry) {
                // Labelled "usage:" forms, or the command name followed by
                // placeholders.
                synopsisLine = isUsageLine(trimmed, command)
                        // Unlabelled forms, recognised by shape, and only before
                        // any option listing has begun.
                        || (!inOptionSection
                            && looksLikeUnlabelledSynopsis(trimmed, command)
                            && !looksLikeExampleCommand(trimmed, command))
                        // Wrapped continuations of a synopsis already started.
                        || (inSynopsisBlock
                            && looksLikeSynopsisContinuation(trimmed));
            }
            if (synopsisLine) {
                // The original line is kept, not the normalised one. In a
                // synopsis the brackets are meaningful — "[-n N]" means the
                // parameter is optional — so stripping them here would destroy
                // the requiredness the synopsis is stating.
                usageLines.add(line);
                pending = null;
                inSynopsisBlock = true;
                continue;
            }
            // Any other line ends the synopsis run.
            inSynopsisBlock = false;

            // --- option line? ---
            if (startsWithFlag(trimmed)) {
                Schema.Option o = parseOptionLine(trimmed, rawLine, descColumn, useTypeColumn);
                if (o != null) {
                    if (descColumn < 0) {
                        descColumn = descriptionColumn(rawLine);
                    }
                    o.group = inCommandList ? "" : currentGroup;
                    // Classification is deferred until the whole description
                    // has been assembled. Help frequently wraps an option's
                    // text over several lines, and the decisive part — a
                    // candidate list such as "Options: a, b, c" — is often on a
                    // continuation line. Classifying here, before those lines
                    // are appended, would see only the first fragment.
                    s.options.add(o);
                    pending = o;
                    inOptionSection = true;
                    continue;
                }
            }

            // --- command listing row? ---
            // The prefixed form is tried first: it is the more specific shape, and
            // its rows would otherwise be dropped entirely (see PREFIXED_LIST_ROW).
            Matcher prefixed = PREFIXED_LIST_ROW.matcher(trimmed);
            if (prefixed.matches()
                    && startedWithProgram(trimmed, prefixed.group(1), command)) {
                String sub = prefixed.group(2);
                String desc = prefixed.group(3).trim();
                if (sub.equalsIgnoreCase(command)
                        || prefixedIsOptionLike(trimmed, sub)) {
                    // Repeats the program's own name, or explains an option: not a
                    // subcommand. Fall through so other tests still see the line.
                } else if (!isTableRow(commandRows, sub, desc)) {
                    if (s.sub(sub) == null) {
                        commandRows.add(new String[]{sub, desc});
                    }
                    pending = null;
                    continue;
                }
            }

            Matcher row = LIST_ROW.matcher(trimmed);
            if (row.matches()) {
                String token = row.group(1);
                // Only treat as a command when we are in a command section, or
                // when the row sits where this document puts its declarations.
                //
                // Where that is has to be measured, not assumed. Across thirty real
                // tools the declaration column was found at 0, 1, 2, 3, 4, 6 and 8
                // spaces, and a fixed threshold read diamond's command list as prose
                // and offered nothing. A document is consistent with itself even when
                // it agrees with nothing else.
                if (inCommandList
                        || shape.atDeclarationIndent(rawLine)
                        || (shape.atSecondaryIndent(rawLine) && directlyUnderHeading)) {
                    // A row inside an indented table is not a command. Help
                    // often opens with a small aligned table (compression
                    // levels, exit codes, supported formats); its rows have the
                    // same shape as a command listing — a short token then a
                    // description — so they must be told apart structurally.
                    if (isTableRow(commandRows, token, row.group(2))) {
                        lastRowWasTable = true;
                        continue;
                    }
                    if (lastRowWasTable) {
                        // A table was in progress and has ended; the run of
                        // command rows starts fresh here.
                        lastRowWasTable = false;
                    }
                    commandRows.add(new String[]{token, row.group(2).trim()});
                    pending = null;
                    continue;
                }
            }
            lastRowWasTable = false;

            // --- continuation of the previous description? ---
            if (pending != null && descColumn > 0 && leadingSpaces(rawLine) >= descColumn) {
                appendContinuation(pending, trimmed);
                continue;
            }
            if (pending != null && !inCommandList && leadingSpaces(rawLine) >= 4) {
                appendContinuation(pending, trimmed);
                continue;
            }
            // A line that is none of the above ends the heading's block: what follows
            // is no longer the list the heading introduced. A blank line does not, or
            // a list separated from its heading by one would be lost.
            if (!trimmed.isEmpty()) {
                directlyUnderHeading = false;
            }
        }

        // Classify every option now that all descriptions are complete. Doing
        // it here rather than per line is what lets a candidate list on a
        // continuation line influence the option's type.
        for (Schema.Option o : s.options) {
            classify(o);
        }

        // Harvest parameters declared only in the usage synopsis. Done after
        // the option section so anything already documented there wins, and so
        // the synopsis can contribute only what is genuinely missing.
        for (String usageLine : usageLines) {
            harvestUsageLine(usageLine, command, s);
        }
        for (Schema.Option o : s.options) {
            if (o.fromUsageLine) {
                classify(o);
            }
        }

        // Subcommands are added only when the evidence is strong enough that
        // this really is a command-list style tool; otherwise ordinary prose
        // rows would pollute the schema.
        if (commandRows.size() >= 2 && commandRows.size() >= s.options.size()) {
            for (String[] r : commandRows) {
                if (s.sub(r[0]) == null) {
                    s.subcommands.add(new Schema.Subcommand(r[0], r[1]));
                }
            }
        }

        // Harvest candidate values that the help lists on their own lines,
        // beneath the option that accepts them.
        harvestIndentedCandidates(lines, s);

        s.parsed = s.hasOptions() || !s.subcommands.isEmpty();
        return s;
    }

    /**
     * Finds option values that the help presents as an indented list rather
     * than inline.
     *
     * <p>Some tools document a choice like this:
     * <pre>
     *   -M       Method to use
     *            msa          use MSA
     *            dendroblast  use dendroblast
     *            orthoxml     use orthoxml
     * </pre>
     * The candidates are not in the option's own description, so the inline
     * patterns cannot see them. Without this pass the option degrades to a text
     * box and the user has to guess the valid values (DESIGN.md P16).
     *
     * <p>The heuristic is deliberately conservative: a run of consecutive
     * indented {@code token + description} rows immediately following an option
     * is treated as that option's candidates, and only when there are at least
     * two of them. A single indented row is far more likely to be a wrapped
     * sentence.
     */
    private static void harvestIndentedCandidates(String[] lines, Schema s) {
        if (s.options.isEmpty()) {
            return;
        }
        Schema.Option current = null;
        List<String> candidates = new ArrayList<String>();

        for (String rawLine : lines) {
            String trimmed = rawLine.trim();
            if (trimmed.isEmpty() || WslBridge.isCondaNoise(trimmed)) {
                continue;
            }
            // A new option line ends the previous option's candidate block.
            if (startsWithFlag(trimmed)) {
                applyCandidates(current, candidates);
                candidates = new ArrayList<String>();
                String head = trimmed.split("\\s+")[0];
                head = head.split(",")[0].trim();
                Schema.Option found = null;
                for (Schema.Option o : s.options) {
                    if (o.flag.equals(head)) {
                        found = o;
                        break;
                    }
                }
                current = found;
                continue;
            }
            if (current == null) {
                continue;
            }
            // A heading ends the block too.
            Matcher h = HEADING.matcher(trimmed);
            if (h.matches() && isPlausibleHeading(h.group(1).trim())) {
                applyCandidates(current, candidates);
                candidates = new ArrayList<String>();
                current = null;
                continue;
            }
            Matcher row = CANDIDATE_ROW.matcher(rawLine);
            if (row.matches()) {
                String token = row.group(1);
                // Reject rows that look like continuation prose rather than a
                // value: a candidate is a single short token.
                if (isCandidateToken(token)) {
                    candidates.add(token);
                    continue;
                }
            }
            // Any other content means this was not a candidate list after all.
            if (!candidates.isEmpty()) {
                applyCandidates(current, candidates);
                candidates = new ArrayList<String>();
            }
        }
        applyCandidates(current, candidates);
    }

    /**
     * Attaches collected candidates to an option when there are enough of them
     * to be a real enumeration.
     */
    private static void applyCandidates(Schema.Option option, List<String> candidates) {
        if (option == null || candidates.size() < 2) {
            return;
        }
        // Do not overwrite candidates found inline; those came from a stronger
        // signal (the option's own line).
        if (option.choices.size() >= 2) {
            return;
        }
        for (String c : candidates) {
            if (!option.choices.contains(c)) {
                option.choices.add(c);
            }
        }
        // An option with a candidate list is a choice, whatever else was
        // inferred from its description.
        if (option.choices.size() >= 2
                && option.kind != Schema.Option.Kind.SWITCH) {
            option.kind = Schema.Option.Kind.CHOICE;
            option.confidence = TypeInference.Confidence.HIGH;
            option.typeReason = "values enumerated beneath the option in the help text";
        }
    }

    /** True when a token looks like an enumerated value rather than prose. */
    private static boolean isCandidateToken(String token) {
        if (token.length() < 2 || token.length() > 20) {
            return false;
        }
        // Values are usually lowercase identifiers or short labels.
        return token.matches("[a-z][a-z0-9_.-]*")
                || token.matches("[A-Z][A-Z0-9_.-]*");
    }

    // ------------------------------------------------------------------
    // Line classification helpers
    // ------------------------------------------------------------------

    /** True when the line's first token is an option flag. */
    static boolean startsWithFlag(String trimmed) {
        if (trimmed.isEmpty() || trimmed.charAt(0) != '-') {
            return false;
        }
        // Reject negative numbers and ranges: "-1", "-0.5", "-60".
        return trimmed.matches("^-{1,2}[A-Za-z].*");
    }

    /** Column at which the description starts, or -1 when not detectable. */
    public static int descriptionColumn(String rawLine) {
        // Descriptions are separated by a run of >= 2 spaces.
        Matcher m = Pattern.compile("\\S(\\s{2,})\\S").matcher(rawLine);
        if (m.find()) {
            return m.start(1) + m.group(1).length();
        }
        return -1;
    }

    private static int leadingSpaces(String s) {
        int n = 0;
        while (n < s.length() && s.charAt(n) == ' ') {
            n++;
        }
        return n;
    }

    /**
     * True when a line is part of a usage synopsis rather than an option
     * listing.
     *
     * <p>Such lines are not option entries — they carry no description — but
     * they are no longer discarded: {@link #harvestUsageLine} extracts the
     * parameters they name, because a tool's required inputs and outputs are
     * frequently declared only there (DESIGN.md P18).
     */
    private static boolean isUsageLine(String trimmed, String command) {
        String lower = trimmed.toLowerCase();
        if (lower.startsWith("usage:" ) || lower.startsWith("usage ")
                || lower.startsWith("usage")) {
            return true;
        }
        // A synopsis line usually contains the command name plus bracketed
        // placeholders and no description column.
        if (command != null && !command.isEmpty()
                && lower.startsWith(command.toLowerCase() + " ")
                && (trimmed.contains("[") || hasAnglePlaceholder(trimmed))
                && !trimmed.contains("  ")) {
            return true;
        }
        return false;
    }

    /**
     * True when the text contains an angle-bracketed placeholder.
     *
     * <p>The bracket must be followed immediately by content. A shell redirection
     * writes it with a space — {@code prog < input > output} — and is part of a
     * concrete command rather than a placeholder. Reading it as one let a
     * demonstration line pass as a declaration, and the options in it were then
     * taken for parameters.
     */
    static boolean hasAnglePlaceholder(String s) {
        return s != null && s.matches(".*<\\S.*");
    }

    /**
     * True when a line looks like a synopsis even though nothing labels it as
     * one.
     *
     * <p>Not every tool writes "usage:". Some introduce the synopsis with a
     * heading such as "Basic usage" and then simply indent a line containing the
     * command and its parameters. Requiring the literal word would miss those
     * (DESIGN.md P18).
     *
     * <p>The test is structural: the line names a flag, carries a placeholder,
     * and has no description column — which is what separates a synopsis from an
     * option entry.
     *
     * <p>Two further guards are needed because a sentence about the program looks
     * superficially similar. Measured across the sample, 71.7% of documents
     * contain a hyphenated compound and 1574 lines are prose that contains a
     * hyphen, so a loose test admits large amounts of description as parameters:
     *
     * <ul>
     *   <li><b>The line must begin with the program's name, followed by a
     *       separator.</b> A sentence that merely mentions the name puts
     *       punctuation after it — {@code "WGDI(Whole-Genome Duplication
     *       Integrated): A user-friendly toolkit for ..."} — and is not a
     *       synopsis.</li>
     *   <li><b>A sentence is not a synopsis.</b> A run of several ordinary words
     *       with connectives between them is prose, whatever else the line
     *       contains.</li>
     * </ul>
     *
     * <p>The cost of being too strict is that an unlabelled synopsis goes
     * unrecognised, and its parameters are then missed. That is the safer failure:
     * a missing parameter can be typed by hand, whereas an invented one appears as
     * a required field the user cannot satisfy.
     */
    private static boolean looksLikeUnlabelledSynopsis(String trimmed, String command) {
        if (trimmed.isEmpty() || !trimmed.contains("-")) {
            return false;
        }
        // A description column means this is an option entry, not a synopsis.
        if (descriptionColumn(trimmed) > 0) {
            return false;
        }
        // A synopsis starts with the program's name followed by a separator.
        // "prog -f <dir>" qualifies; "prog(...)" or "prog: ..." does not.
        if (command != null && !command.isEmpty()
                && !startsWithWord(trimmed, command)) {
            return false;
        }
        // A sentence is not a synopsis. Requiring three ordinary words in a row
        // with a connective among them recognises prose without depending on any
        // particular vocabulary.
        if (SENTENCE_LIKE.matcher(trimmed).find()) {
            return false;
        }
        // It must name at least one flag and at least one placeholder.
        if (!USAGE_PARAM.matcher(trimmed).find()) {
            return false;
        }
        return hasAnglePlaceholder(trimmed) || trimmed.contains("[")
                || trimmed.matches(".*\\s[A-Z][A-Z0-9_]{1,20}(\\s|$).*")
                || trimmed.matches(".*\\s[a-z][a-z0-9_]{3,20}(\\s|$).*");
    }

    /**
     * True when a line is a worked example rather than a declaration.
     *
     * <p>The two are told apart by what they put where a value goes:
     *
     * <pre>
     *   declaration:  prog -in &lt;inputfile&gt; -out &lt;outputfile&gt;
     *   example:      prog -in genomes.gb -out result.fa -gappyout
     * </pre>
     *
     * <p>A declaration describes the <em>shape</em> of the call and so writes
     * placeholders. A demonstration shows a call that was actually made and so
     * writes concrete values. Absence of any placeholder therefore marks a line as
     * a demonstration.
     *
     * <p>This covers the examples that carry no heading, which is why it is needed
     * in addition to {@link #headingRole}: a document may open with a few sample
     * commands and give no indication that they are samples.
     *
     * <p>It is a statement about how the two kinds of line are written, not about
     * any program. The check for an all-capitals token uses the same placeholder
     * convention as the rest of the parser, so {@code -out tree} is a value while
     * {@code -out FILE} is a placeholder.
     */
    private static boolean looksLikeExampleCommand(String trimmed, String command) {
        if (command == null || command.isEmpty()) {
            return false;
        }
        // A declaration begins with the program's name, as an example does.
        if (!startsWithWord(trimmed, command)) {
            return false;
        }
        if (!trimmed.contains("-")) {
            return false;
        }
        // An angle-bracketed placeholder means the line is describing a shape.
        //
        // The '<' must be followed immediately by content. A shell redirection
        // writes it with a space — "prog < input > output" — and is a concrete
        // demonstration, not a placeholder. Treating the redirection as a
        // placeholder would let an example containing one pass as a declaration.
        if (trimmed.matches(".*<\\S.*")) {
            return false;
        }
        // An all-capitals token means the same.
        if (trimmed.matches(".*\\s[A-Z][A-Z0-9_]{1,20}(\\s|$).*")) {
            return false;
        }
        // No placeholder anywhere, yet options are present: a demonstration.
        return true;
    }

    /**
     * True when a line begins with the given word as a whole word.
     *
     * <p>A separator must follow, so {@code "prog value"} matches while
     * {@code "prog(value)"} does not — which is the distinction between a synopsis
     * naming the program and a sentence mentioning it.
     */
    public static boolean startsWithWord(String line, String word) {
        if (line == null || word == null || word.isEmpty()
                || line.length() < word.length()) {
            return false;
        }
        if (!line.regionMatches(true, 0, word, 0, word.length())) {
            return false;
        }
        if (line.length() == word.length()) {
            return true;
        }
        char next = line.charAt(word.length());
        return Character.isWhitespace(next) || next == '-' || next == '<'
                || next == '[';
    }

    /**
     * A run of ordinary words containing a connective, which marks a sentence.
     *
     * <p>Synopses are made of flags, placeholders and the program's name; they do
     * not join clauses with "and", "for" or "that". Requiring both a connective
     * and several surrounding words avoids rejecting a synopsis that happens to
     * contain a word like "for" inside a placeholder's name.
     */
    private static final Pattern SENTENCE_LIKE = Pattern.compile(
            "\\b[a-z]{2,}\\s+(?:[a-z]{2,}\\s+){0,3}"
            + "(?:and|or|for|with|that|from|the|of|to|in|on|by|is|are|as|than)\\b"
            + "\\s+[a-z]{2,}",
            Pattern.CASE_INSENSITIVE);

    /**
     * True when a line is an indented continuation of a usage synopsis.
     *
     * <p>Long synopses wrap, as in blastn where the parameter list spans a dozen
     * lines. Such a line contains only flags and placeholders, so it is
     * recognised by the absence of prose: no sentence punctuation and no run of
     * several words without a flag between them.
     */
    private static boolean looksLikeSynopsisContinuation(String trimmed) {
        if (trimmed.isEmpty() || !trimmed.contains("-")) {
            return false;
        }
        // Prose would contain sentence punctuation.
        if (trimmed.matches(".*[.;:!?].*")) {
            return false;
        }
        // Count flag tokens; a synopsis continuation has at least one and few
        // words that are not part of a flag/value pair.
        int flags = 0;
        Matcher m = Pattern.compile("-{1,2}[A-Za-z][A-Za-z0-9_.-]*").matcher(trimmed);
        while (m.find()) {
            flags++;
        }
        return flags >= 2;
    }

    /** A parameter reference inside a synopsis: {@code -f <dir>} or {@code [-x VAL]}. */
    /**
     * A parameter named in a usage synopsis.
     *
     * <p>The leading {@code (?:^|[\s\[({,|])} is essential and was missing. Without
     * it the pattern matches a hyphen that is merely <em>inside</em> a word, so
     * ordinary prose in a synopsis yields parameters that do not exist:
     *
     * <pre>
     *   A user-friendly toolkit for whole-genome duplications
     *            └──────┬──┘         └────┬─────┘
     *             "-friendly toolkit"  "-genome duplications"
     * </pre>
     *
     * <p>A flag must begin a word. The boundary is expressed as "start of line, or
     * a character that separates tokens" rather than {@code \b}, because a hyphen
     * is itself a word boundary — {@code user-friendly} contains one between
     * {@code r} and {@code -} — so {@code \b} would not exclude it.
     *
     * <p>The prefix is a non-capturing group so that group 1 remains the flag and
     * group 2 its value.
     */
    private static final Pattern USAGE_PARAM = Pattern.compile(
            "(?:^|[\\s\\[({,|])"
            + "(-{1,2}[A-Za-z][A-Za-z0-9_.-]*)"
            + "(?:[=\\s]+(<[^>]+>|[A-Z][A-Z0-9_]{1,20}|[a-z][a-z0-9_]{2,20}))?");

    /**
     * Extracts parameters named in a usage synopsis.
     *
     * <p>Handles the three sub-forms seen in practice:
     * <pre>
     *   tool [options] -f &lt;dir&gt;                     bare flag, angle placeholder
     *   tool [-query input_file] [-out output_file]  each param in its own brackets
     *   tool -in &lt;inputfile&gt; -out &lt;outputfile&gt;     several on one line
     * </pre>
     *
     * <p>Brackets are ambiguous: {@code [options]} means "other parameters exist"
     * and must be skipped, while {@code [-query input_file]} names a parameter
     * and must be kept. The distinction is whether a flag token appears inside
     * (DESIGN.md P18).
     *
     * <p>Extracted parameters carry no description, so their entry records that
     * fact rather than looking like a parse failure.
     *
     * <p>Whether a parameter is mandatory is <b>not</b> decided here. The synopsis
     * brackets its optional parameters, but treating that as a requirement
     * statement misfires: examples, prose and conditional requirements all look
     * similar, and a wrong "required" blocks a call the program would have
     * accepted. The program states its own requirements accurately when something
     * is missing, so the plugin does not guess (see Schema.Option).
     */
    private static void harvestUsageLine(String rawLine, String command, Schema s) {
        // Drop generic placeholders that stand for "other parameters".
        String line = rawLine.replaceAll("\\[\\s*(options?|args?|arguments?|"
                + "flags?|parameters?|\\.\\.\\.)\\s*\\]", " ");

        Matcher m = USAGE_PARAM.matcher(line);
        while (m.find()) {
            String flag = m.group(1);
            String arg = m.group(2) == null ? "" : m.group(2).trim();
            // Ignore a lone dash used as a value.
            if (flag.equals("-") || flag.equals("--")) {
                continue;
            }
            // Skip the command's own name if it was captured as a flag-like token.
            if (command != null && flag.equalsIgnoreCase(command)) {
                continue;
            }
            if (s.option(flag) != null) {
                // Already declared in the option list, which carries a description
                // and a type; that entry is the better one and is kept.
                continue;
            }
            Schema.Option o = new Schema.Option();
            o.flag = flag;
            o.arg = arg;
            o.description = "";
            o.fromUsageLine = true;
            s.options.add(o);
        }
    }

    /**
     * True when a {@code token + description} row is part of a table rather
     * than a command listing.
     *
     * <p>Tables and command listings share a shape, so the distinction is made
     * from content:
     *
     * <ul>
     *   <li>a table's first row is a header naming its columns, so seeing words
     *       such as "range", "default", "comment" or "description" as a row's
     *       text marks the start of a table</li>
     *   <li>a table's rows are homogeneous: their leading tokens and the widths
     *       of their first column repeat, whereas command listings vary</li>
     *   <li>table cells frequently contain values that are not command names —
     *       numbers, ranges, URLs</li>
     * </ul>
     *
     * <p>Recognising a header is what makes this reliable in practice: help
     * tables are introduced by a header row, and a command listing never is.
     */
    private static boolean isTableRow(List<String[]> soFar, String token, String rest) {
        String body = rest == null ? "" : rest.toLowerCase();
        // A header row announces a table.
        int headerWords = 0;
        for (String w : new String[]{"range", "default", "comment", "description",
                "meaning", "value", "format", "level", "code", "effect", "note"}) {
            if (body.contains(w)) {
                headerWords++;
            }
        }
        if (headerWords >= 2 && token.length() <= 12) {
            return true;
        }
        // A row whose second cell is a URL or a bare range is table data.
        if (body.contains("http://") || body.contains("https://")
                || body.matches("^\\d+\\s*[-–]\\s*\\d+.*")) {
            return true;
        }
        return false;
    }

    /** Filters out prose that merely ends with a colon. */
    static boolean isPlausibleHeading(String name) {
        if (name.isEmpty() || name.length() > 60) {
            return false;
        }
        if (name.endsWith(".") || name.contains("  ")) {
            return false;
        }
        // Reject sentences: a heading rarely has sentence punctuation.
        if (name.contains(",") && name.length() > 30) {
            return false;
        }
        return true;
    }

    private static boolean isCommandHeading(String name) {
        String n = name.toLowerCase();
        return n.contains("command") || n.contains("subcommand")
                || n.contains("sub-command") || n.contains("action")
                || n.contains("operation") || n.contains("mode");
    }

    /**
     * True when an indented row is far more likely to be a command name than a
     * continuation of prose: single lowercase token, and the rest of the row is
     * a short description.
     */
    /**
     * True when a line's first word is the program's own name.
     *
     * <p>Only the program name anchors the prefixed listing form. Without this check
     * any {@code word word : text} line would qualify, and prose is full of those.
     */
    /**
     * True when a line's first word is the program's own name.
     *
     * <p>Only the program name anchors the prefixed listing form. Without this check
     * any {@code word word : text} line would qualify, and prose is full of those.
     */
    private static boolean startedWithProgram(String trimmed, String first,
                                              String command) {
        if (command == null || command.isEmpty()) {
            return false;
        }
        String head = first;
        int slash = head.lastIndexOf('/');
        if (slash >= 0) {
            head = head.substring(slash + 1);
        }
        return head.equalsIgnoreCase(command);
    }

    /**
     * True when the second word of a prefixed row is an option rather than a
     * subcommand.
     *
     * <p>Used for rows that explain an invocation, as in
     * {@code easel --version : show version number}. Those describe the program
     * itself, and taking the second word as a command name would create a
     * subcommand called {@code --version}.
     */
    private static boolean prefixedIsOptionLike(String trimmed, String second) {
        if (second.startsWith("-")) {
            return true;
        }
        // A metavariable stands for an argument, not a command: "prog <cmd> : run
        // a command" describes a form rather than naming one.
        return second.startsWith("<") || second.endsWith(">");
    }

    private static boolean looksLikeCommandRow(String rawLine, String token) {
        if (leadingSpaces(rawLine) < 2) {
            return false;
        }
        if (!token.matches("[a-z][a-z0-9_-]{1,24}")) {
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Option parsing
    // ------------------------------------------------------------------

    /**
     * Parses one option line.
     *
     * @param trimmed    line with surrounding whitespace removed
     * @param rawLine    untouched line, for column analysis
     * @param descColumn learned description column, or -1
     */
    /**
     * Removes decoration written around an option's name.
     *
     * <p>Two forms occur, and both are names rather than punctuation with meaning:
     *
     * <ul>
     *   <li>{@code [-infile]} — the whole name bracketed. The brackets mark the
     *       name, not optionality; treating them as part of the flag produced a
     *       flag that no program accepts.</li>
     *   <li>{@code -[no]sequence} — a boolean entered under both its positive and
     *       negative names. Kept as the positive name.</li>
     * </ul>
     *
     * <p>Applied to both the raw line and its trimmed form so that column
     * arithmetic on the description stays valid.
     */
    static String stripNameDecoration(String line) {
        if (line == null || line.indexOf('[') < 0) {
            return line;
        }
        // [-name]  ->  -name     (only when the brackets wrap the name itself)
        String s = BRACKETED_NAME.matcher(line).replaceFirst("$1$2");
        // -[no]name  ->  -name
        s = OPTIONAL_NAME.matcher(s).replaceFirst("$1-$2");
        return s;
    }

    /** A bracketed option name: {@code [-infile]}. */
    private static final Pattern BRACKETED_NAME = Pattern.compile(
            "(^|\\s)\\[(-{1,2}[A-Za-z][A-Za-z0-9_.-]*)\\]");

    /** A boolean written as both of its forms: {@code -[no]sequence}. */
    private static final Pattern OPTIONAL_NAME = Pattern.compile(
            "(^|\\s)-\\[no\\](-?[A-Za-z][A-Za-z0-9_.-]*)");

    /**
     * Parses one option line, without considering a type column.
     *
     * <p>Whether a document declares types in a column is decided for the document
     * as a whole, because the shape has to repeat to be meaningful. Callers that
     * have made that decision pass it in; this overload assumes it has not been
     * made, which is the conservative choice.
     */
    public static Schema.Option parseOptionLine(String trimmedLine, String rawLineIn,
                                                int descColumn) {
        return parseOptionLine(trimmedLine, rawLineIn, descColumn, false);
    }

    /**
     * Parses one option line.
     *
     * @param allowTypeColumn true when the document was found to declare types in
     *                        a column, so a matching line here is a declaration
     *                        rather than an alignment accident
     */
    public static Schema.Option parseOptionLine(String trimmedLine, String rawLineIn,
                                                int descColumn, boolean allowTypeColumn) {
        // Two spellings put decoration around the option name and must be
        // removed before the name is read:
        //
        //   [-infile]      the name is bracketed
        //   -[no]sequence  a boolean written as its positive and negative forms
        //
        // Neither means "optional" or "negated" in the sense the punctuation
        // suggests; both are simply how that document writes a name.
        //
        // The decoration is removed from the whole line before anything else, so
        // that every later step — the description column arithmetic included —
        // works on one version of the text.
        String rawLine = stripNameDecoration(rawLineIn);
        String trimmed = rawLine.trim();
        Matcher fm = FLAG_GROUP.matcher(trimmed);
        if (!fm.find()) {
            return null;
        }
        Schema.Option o = new Schema.Option();
        // group(1) holds the "=value" part when present; the flag itself is
        // everything before it.
        String flags = fm.group(1) == null
                ? fm.group() : fm.group().substring(0,
                        fm.group().length() - fm.group(1).length());
        flags = flags.trim();
        if (flags.endsWith(",") || flags.endsWith("|")) {
            flags = flags.substring(0, flags.length() - 1).trim();
        }

        // An alias group is the most common spelling in real help output: the
        // short and long forms of one option written on one line, "-h, --help".
        // All the forms are collected first, then the longest is chosen as the
        // primary name.
        //
        // Choosing the longest rather than the first is deliberate. The long form
        // is more descriptive, is what a reader recognises, and is accepted by
        // the same program — so a command line built from it behaves identically
        // while being readable. Measured across the sample, this shape occurs far
        // more often than any other (see docs/help-structure-survey.md).
        List<String> forms = new ArrayList<String>();
        for (String part : flags.split("\\s*[,|]\\s*")) {
            String f = part.trim();
            if (!f.isEmpty() && !forms.contains(f)) {
                forms.add(f);
            }
        }
        if (forms.isEmpty()) {
            return null;
        }
        String primary = forms.get(0);
        for (String f : forms) {
            if (f.length() > primary.length()) {
                primary = f;
            }
        }
        o.flag = primary;
        for (String f : forms) {
            if (!f.equals(primary)) {
                o.aliases.add(f);
            }
        }

        // A type column, when the document has one, is read here and settles the
        // type outright. The author wrote the type into a dedicated field;
        // nothing derived from the description may overrule that (DESIGN.md P28).
        TypeColumn.Match tc = allowTypeColumn ? TypeColumn.detect(rawLine) : null;
        if (tc != null && tc.flag.equals(o.flag)) {
            o.declaredKind = tc.kind;
            o.declaredWord = tc.word;
            // The declared word doubles as the value position's name, which
            // gives the rest of the pipeline something to work with.
            o.arg = tc.word;
            o.description = tc.description;
            harvestMetadata(o);
            return o;
        }

        // An equals sign means a value belongs here, whether or not the author
        // wrote one after it (DESIGN.md P25). The text after '=' is a hint at the
        // value's shape when present.
        String eqValue = "";
        if (fm.group(1) != null) {
            o.takesValue = true;
            eqValue = fm.group(2) == null ? "" : fm.group(2).trim();
        }

        // The remainder of the line, with the exact offset it starts at.
        //
        // The offset matters and must not be computed from a trimmed string:
        // trim() removes leading spaces, so a later index into "after" cannot be
        // derived from the difference in lengths. That mistake made the column
        // arithmetic below cut in the wrong place, so a value position like
        // "-d DOTPLOT    show a dotplot" produced "DOTPLOT            S" as the
        // candidate metavariable, which no shape test accepts — the option was
        // then read as taking no value at all.
        int afterStart = fm.end();
        while (afterStart < trimmed.length()
                && Character.isWhitespace(trimmed.charAt(afterStart))) {
            afterStart++;
        }
        String after = trimmed.substring(afterStart);

        // Split the metavariable from the description. The reliable signal is
        // the description column; fall back to a two-space gap.
        String meta = "";
        String desc = after;
        int split = -1;
        // The column is consulted whenever one is known. It must not be skipped
        // for a short line: a line whose description was written on the next one
        // is shorter than the column, and that is exactly the case where the
        // remainder is the value position.
        if (descColumn > 0) {
            int rawStart = rawLine.indexOf(trimmed);
            if (rawStart >= 0) {
                // Where the description column falls, as an index into "trimmed".
                int descAt = descColumn - rawStart;
                if (descAt > afterStart && descAt < trimmed.length()) {
                    // The column is a layout hint, not a fact about this line. A
                    // value longer than the column overflows past it, and cutting
                    // at the column would then split that value in half:
                    //
                    //   -icl IMPROVEDCOLLINEARITY
                    //              ^ column   ->  "IMPROVEDCOLLINEAR" + "ITY ..."
                    //
                    // So when the column lands inside a token, the cut moves
                    // forward to the next separator and the token stays whole.
                    int at = descAt;
                    if (!Character.isWhitespace(trimmed.charAt(at))
                            && !Character.isWhitespace(trimmed.charAt(at - 1))) {
                        while (at < trimmed.length()
                                && !Character.isWhitespace(trimmed.charAt(at))) {
                            at++;
                        }
                    }
                    split = at - afterStart;
                } else if (descAt >= trimmed.length()) {
                    // The column lies past the end of this line, which happens
                    // when the description was written on the following line:
                    //
                    //   -km KARYOTYPE_MAPPING
                    //                         Mapping from the known ...
                    //
                    // Everything here is the value position.
                    int sp = after.indexOf(' ');
                    split = sp < 0 ? after.length() : sp;
                }
            }
        }
        if (split <= 0) {
            // Before falling back to the description column, try the
            // "flag metavar: description" form, which some tools use on a
            // single line: "-c class: Sets the query class".
            Matcher inline = Pattern.compile(
                    "^(<[^>]+>|[A-Za-z][A-Za-z0-9_.|\\[\\]-]{0,30})\\s*:\\s*(.*)$")
                    .matcher(after);
            if (inline.matches() && isMetavariable(inline.group(1))) {
                meta = inline.group(1).trim();
                desc = inline.group(2).trim();
            } else {
                Matcher gap = Pattern.compile("^(\\S+)(\\s{2,})(.*)$").matcher(after);
                if (gap.matches()) {
                    String head = gap.group(1);
                    // A lone colon is the layout separator written as
                    // "--flag :   description"; treat it as punctuation rather
                    // than an argument.
                    if (head.equals(":")) {
                        desc = gap.group(3).trim();
                    } else {
                        meta = head;
                        desc = gap.group(3).trim();
                    }
                } else {
                    // No clear gap: treat a leading metavariable-shaped token
                    // separately, otherwise all of it is description.
                    int sp = after.indexOf(' ');
                    if (sp > 0) {
                        String head = after.substring(0, sp);
                        String rest = after.substring(sp + 1).trim();
                        if (isMetavariable(head)) {
                            meta = head;
                            desc = rest;
                        }
                    }
                }
            }
        } else {
            String head = after.substring(0, Math.min(split, after.length())).trim();
            String rest = split < after.length() ? after.substring(split).trim() : "";
            // A lone colon is the layout separator used by formats that write
            // "--flag :   description"; it is not an argument.
            if (head.equals(":")) {
                desc = rest;
            } else if (isMetavariable(head)) {
                meta = head;
                desc = rest;
            } else if (!head.isEmpty() && looksLikeValueWord(after, split)) {
                // A short word set apart from the description by a wide gap is the
                // value position, even when it is written in lower case.
                //
                // Tools built on cobra spell it as a type name — "-o, --out-file
                // string" — and the convention that a placeholder is upper case
                // (FILE) or bracketed (<file>) does not cover that. Reading it as
                // the first word of the description left the option looking like it
                // took no value at all, and every value typed into it was then
                // discarded.
                //
                // The gap is what distinguishes the two, and it is not a guess: a
                // value position is separated from its description by the column
                // the author aligned to, while the words of a description are one
                // space apart. Measured on real help, value positions sit 15-18
                // columns away and description words sit 1 apart.
                meta = head;
                desc = rest;
            } else if (!head.isEmpty()) {
                // The "metavariable" is really the first word of the prose.
                desc = head + (rest.isEmpty() ? "" : " " + rest);
            } else {
                desc = rest;
            }
        }

        // HMMER and others separate the description with a colon.
        if (desc.startsWith(":")) {
            desc = desc.substring(1).trim();
        }

        // A value written after an equals sign is a hint at the value's shape
        // when the author gave no metavariable of their own, e.g.
        // "-INFILE=file.ext" describes a file.
        if (meta.isEmpty() && !eqValue.isEmpty()
                && !eqValue.equals(".") && !eqValue.equals("=")) {
            meta = eqValue;
        }

        o.arg = meta;
        o.description = desc;
        harvestMetadata(o);
        // An equals sign settles the question: the option takes a value even
        // though the author wrote no placeholder (DESIGN.md P25).
        o.isSwitch = !o.takesValue && meta.isEmpty()
                && !descriptionImpliesValue(desc);
        return o;
    }

    /**
     * True when a token is a metavariable rather than a word.
     *
     * <p>Accepts, in order of how explicit they are:
     * <ul>
     *   <li>angle brackets — {@code <inputfile>}, the strongest signal</li>
     *   <li>a lone {@code -}, the conventional spelling of stdin/stdout</li>
     *   <li>{@code #} or a bare {@code N}, the traditional spelling of "a
     *       number" in hand-written help</li>
     *   <li>an all-caps token of two or more letters, e.g. {@code FILE},
     *       {@code NUM[G|M]}</li>
     * </ul>
     *
     * <p><b>Angle brackets alone are sufficient</b>, including for a single
     * letter: {@code <s>} and {@code <x>} are placeholders because the author
     * bracketed them. The earlier rule also demanded two characters, which
     * rejected those spellings and left whole families of options with no type
     * signal at all (DESIGN.md P26).
     *
     * <p>The two-character requirement still applies to <em>unbracketed</em>
     * tokens, where a single capital letter is far more often the first letter
     * of a description than a placeholder — accepting those corrupted
     * descriptions by consuming their first character.
     *
     * <p>A trailing colon is stripped first, because some layouts write
     * {@code "-h:  Prints this help"} where the colon belongs to the layout.
     */
    public static boolean isMetavariable(String token) {
        if (token == null) {
            return false;
        }
        String t = token.trim();
        if (t.isEmpty()) {
            return false;
        }
        // Angle brackets are unambiguous and are checked first. One character
        // inside them is enough: <s>, <x>, <n> are all placeholders.
        if (t.startsWith("<") && t.endsWith(">") && t.length() >= 3) {
            return true;
        }
        // A bare colon is layout punctuation, never a placeholder.
        if (t.equals(":")) {
            return false;
        }
        if (t.endsWith(":") && !t.startsWith("<")) {
            t = t.substring(0, t.length() - 1).trim();
        }
        if (t.isEmpty() || t.length() > 40) {
            return false;
        }
        if (t.equals("-")) {
            return true;
        }
        // Traditional numeric placeholders.
        if (t.equals("#") || t.equals("N") || t.equals("n")) {
            return true;
        }
        if (!METAVAR.matcher(t).matches()) {
            return false;
        }
        String letters = t.replaceAll("[^A-Za-z]", "");
        if (letters.length() < 2) {
            return false;
        }
        return letters.equals(letters.toUpperCase());
    }

    /**
     * True when a word set apart from the rest of the line is the value position
     * rather than the first word of a description.
     *
     * <p>Two facts have to hold, and neither is about any particular word:
     *
     * <ul>
     *   <li><b>The word is short and single.</b> A value position is a token — a
     *       type name or a metavariable. A description's first word is also short,
     *       so this alone decides nothing.</li>
     *   <li><b>A wide gap follows it.</b> The word sits alone on its side of the
     *       column the author aligned to; the words of a description are one space
     *       apart. Measured against real help text, value positions are separated
     *       from their description by 15-18 columns while description words are
     *       separated by 1.</li>
     * </ul>
     *
     * <p>The gap is read from the untrimmed line, because trimming it away is
     * exactly what destroys the signal.
     */
    private static boolean looksLikeValueWord(String after, int split) {
        if (split <= 0 || split > after.length()) {
            return false;
        }
        // The word is everything before the column, less the padding that reached
        // it: "string          " up to the column is just "string".
        String head = after.substring(0, split).trim();
        // A value position is one token, not a phrase.
        if (head.isEmpty() || head.indexOf(' ') >= 0 || head.length() > 24) {
            return false;
        }
        // It must be a plausible placeholder word rather than punctuation.
        if (!head.matches("[A-Za-z][A-Za-z0-9_<>/-]*")) {
            return false;
        }
        // Measure the gap between where the word ends and where the description
        // begins. Counting from the column would give zero, because the column sits
        // in the padding after the word — the padding is the signal.
        int wordEnd = after.indexOf(head) + head.length();
        int descStart = split;
        while (descStart < after.length() && after.charAt(descStart) == ' ') {
            descStart++;
        }
        if (descStart >= after.length()) {
            // Nothing follows on this line; the description is on the next one,
            // which the caller already handles.
            return false;
        }
        return descStart - wordEnd >= 2;
    }

    /**
     * True when the output indicates the command does not exist at all, as
     * opposed to existing but refusing to print help.
     *
     * <p>Distinguishing these matters: a shell reporting "command not found"
     * means the discovered entry is stale or shadowed and should be dropped,
     * whereas a tool that merely failed to print help should be kept and
     * offered for raw command entry.
     */
    public static boolean isCommandNotFound(String output) {
        if (output == null || output.isEmpty()) {
            return false;
        }
        String o = output.toLowerCase();
        return o.contains("command not found")
                || o.contains("no such file or directory")
                || o.contains("executable file not found")
                || o.contains("not recognized as an internal or external command");
    }

    /**
     * True when an option with no visible metavariable nonetheless takes a
     * value, inferred from its description mentioning a range, default or
     * numeric bound.
     */
    static boolean descriptionImpliesValue(String desc) {
        if (desc == null || desc.isEmpty()) {
            return false;
        }
        if (RANGE.matcher(desc).find()) {
            return true;
        }
        String d = desc.toLowerCase();
        return d.contains("(n>") || d.contains("(x>") || d.contains("<n>")
                || d.contains("<x>") || d.contains("number of")
                || d.matches(".*\\[\\s*-?\\d+(\\.\\d+)?\\s*\\].*")
                && !d.startsWith("don't");
    }

    /**
     * Extracts the facts that are not part of type inference: whether the
     * option may be repeated.
     *
     * <p>Ranges, defaults and candidate values are deliberately <b>not</b>
     * handled here. They belong to {@link TypeInference}, which applies the
     * evidence rules for them. An earlier version also harvested a default in
     * this method with a looser rule, and because it ran afterwards it silently
     * overwrote the guarded value — pulling prose such as {@code [specified]}
     * out of a description and into a command line. One owner per fact.
     *
     * <p>Requiredness was removed from here as well. Matching the word
     * "required" anywhere in a description does not identify what it refers to:
     * {@code provided "suffix" (required) matches the file suffix} is explaining
     * the meaning of a value, and {@code Required for any pixel format with an
     * alpha channel} states a condition rather than a requirement. Both were read
     * as "this option is mandatory". Whether an option is mandatory is the
     * program's business (see Schema.Option).
     */
    private static void harvestMetadata(Schema.Option o) {
        String d = o.description;
        if (d == null || d.isEmpty()) {
            return;
        }
        if (REPEATABLE.matcher(d).find()) {
            o.repeatable = true;
        }
    }

    private static void appendContinuation(Schema.Option o, String text) {
        if (o.description.isEmpty()) {
            o.description = text;
        } else {
            o.description = o.description + " " + text;
        }
        harvestMetadata(o);
    }

    // ------------------------------------------------------------------
    // Semantic classification (inference only, never authoritative)
    // ------------------------------------------------------------------

    /**
     * Infers an option's control type and primary input/output role.
     *
     * <p>Delegates the type decision to {@link TypeInference}, which applies the
     * layered strategy and reports its confidence (DESIGN.md P10). This method
     * only adds the input/output role used for the output-path suggestion.
     */
    public static void classify(Schema.Option o) {
        // A type the author declared in a type column is a statement, not an
        // inference. Nothing derived from the description may override it, so the
        // inference is skipped entirely and the declared type kept
        // (DESIGN.md P28).
        if (o.declaredKind != null) {
            o.kind = o.declaredKind;
            o.confidence = TypeInference.Confidence.HIGH;
            o.evidence = Schema.Option.Evidence.TYPE_COLUMN;
            o.typeReason = "declared in the type column as '" + o.declaredWord + "'";
            if (o.kind == Schema.Option.Kind.SWITCH) {
                o.isSwitch = true;
            } else {
                o.isSwitch = false;
            }
            // A declared type may still come with a stated range or candidate
            // list, which are orthogonal facts about the option.
            TypeInference.Result extra = TypeInference.infer(o.arg, o.description,
                    o.isSwitch);
            if (extra.hasRange()) {
                o.min = extra.min;
                o.max = extra.max;
            }
            if (!extra.choices.isEmpty()) {
                o.choices.clear();
                o.choices.addAll(extra.choices);
            }
            if (extra.defaultValue != null && !extra.defaultValue.isEmpty()) {
                o.defaultValue = extra.defaultValue;
            }
            return;
        }

        TypeInference.Result inf = TypeInference.infer(o.arg, o.description, o.isSwitch);

        o.kind = inf.kind;
        o.confidence = inf.confidence;
        o.typeReason = inf.reason;
        o.evidence = inf.evidence;
        if (inf.defaultValue != null && !inf.defaultValue.isEmpty()) {
            o.defaultValue = inf.defaultValue;
        }
        if (inf.hasRange()) {
            o.min = inf.min;
            o.max = inf.max;
        }
        if (!inf.choices.isEmpty()) {
            o.choices.clear();
            o.choices.addAll(inf.choices);
        }
        // An option the parser judged a switch but inference says takes a value
        // must accept one, or the UI would hide the value entirely.
        if (o.isSwitch && o.kind != Schema.Option.Kind.SWITCH) {
            o.isSwitch = false;
        }

        // Role hints, used only to pre-fill an output path from an input path.
        String desc = o.description == null ? "" : o.description.toLowerCase();
        if (o.kind == Schema.Option.Kind.INPUT_PATH
                && (desc.contains("input") || desc.contains("query")
                    || desc.contains("alignment file") || desc.contains("in file"))) {
            o.looksLikePrimaryInput = true;
        }
        if (o.kind == Schema.Option.Kind.OUTPUT_PATH
                && (desc.contains("output") || desc.contains("direct output")
                    || desc.contains("out file") || desc.contains("save"))) {
            o.looksLikePrimaryOutput = true;
        }
    }

    // ------------------------------------------------------------------
    // Help invocation strategy
    // ------------------------------------------------------------------

    /**
     * Candidate help invocations, in the order they should be tried.
     *
     * <p>Several are required because tools disagree: some accept only
     * {@code -h}, some only {@code --help}, and some produce more detail for one
     * than the other. The caller runs them all and keeps the richest result
     * (DESIGN.md P3).
     */
    public static List<String> helpInvocations(String command) {
        List<String> out = new ArrayList<String>();
        out.add(command + " --help");
        out.add(command + " -h");
        out.add(command + " -help");
        out.add(command + " help");
        out.add(command + " --usage");
        // A bare invocation often prints usage when the tool requires arguments.
        out.add(command);
        return out;
    }

    /**
     * Help invocations for a subcommand, e.g. {@code samtools stats --help}.
     */
    public static List<String> subcommandHelpInvocations(String command, String sub) {
        List<String> out = new ArrayList<String>();
        out.add(command + " " + sub + " --help");
        out.add(command + " " + sub + " -h");
        out.add(command + " " + sub);
        out.add(command + " help " + sub);
        return out;
    }

    /**
     * Chooses the richest schema from several candidate help outputs.
     *
     * <p>"Richest" is measured by the number of options understood, which is a
     * better signal than exit status: many tools exit non-zero for help, and
     * some print only a stub for one flag but full detail for another.
     */
    public static Schema bestOf(List<String> outputs, String command) {
        Schema best = new Schema();
        for (String out : outputs) {
            Schema s = parse(out, command);
            if (score(s) > score(best)) {
                best = s;
            }
        }
        return best;
    }

    /** Measured richness of a schema; options weigh more than subcommands. */
    private static int score(Schema s) {
        return s.options.size() * 3 + s.subcommands.size();
    }

    /** Collects unique help texts from outputs. */
    public static List<String> dedupe(List<String> outputs) {
        Set<String> seen = new LinkedHashSet<String>();
        List<String> out = new ArrayList<String>();
        for (String s : outputs) {
            if (s != null && !s.trim().isEmpty() && seen.add(s.trim())) {
                out.add(s);
            }
        }
        return out;
    }
}

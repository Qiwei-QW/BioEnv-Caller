package bioenv.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Infers a parameter's control type from its help text.
 *
 * <p>Command-line help carries no type information: there is no schema, only
 * prose written for humans. So inference is a best effort, and this class is
 * built around that reality (DESIGN.md P7, P10):
 *
 * <ul>
 *   <li>Signals are tried <b>in order of reliability</b>, and the first that
 *       fires wins. A weak signal never overrides a stronger one.</li>
 *   <li>Every result carries a {@link Confidence}, which the UI shows, so the
 *       user knows which rows to check.</li>
 *   <li>When nothing is recognisable the type falls back to free text, which is
 *       always safe.</li>
 * </ul>
 *
 * <h2>Signal layers</h2>
 *
 * <ol>
 *   <li><b>Placeholder shape</b> — the most reliable signal, because these are
 *       naming conventions rather than prose. Covers the NCBI style
 *       ({@code input_file}, {@code int_value}, {@code float_value}), the
 *       angle-bracket style ({@code <inputfile>}), and the traditional
 *       {@code #}/{@code N} for numeric.</li>
 *   <li><b>Leading noun of the description</b> — help descriptions start with
 *       the head noun ({@code "Path to ..."}, {@code "Number of ..."},
 *       {@code "Output directory"}), which is far more reliable than searching
 *       the whole sentence.</li>
 *   <li><b>Enumerated candidates</b> — {@code {a,b}}, {@code a|b},
 *       {@code one of:}, {@code can be:}. Treated separately because a candidate
 *       list is direct evidence of a choice type.</li>
 *   <li><b>Whole-description keywords</b> — the weakest layer, used only when
 *       nothing above fires.</li>
 * </ol>
 */
public final class TypeInference {

    /** How much to trust an inferred type. */
    public enum Confidence {
        /** Recognised from an explicit convention or candidate list. */
        HIGH,
        /** Inferred from the description; usually right, worth checking. */
        MEDIUM,
        /** No signal; the type is a safe placeholder for the user to correct. */
        LOW
    }

    /** The outcome of inference for one option. */
    public static final class Result {
        public Schema.Option.Kind kind = Schema.Option.Kind.TEXT;
        public Confidence confidence = Confidence.LOW;
        /** Candidate values, when the help enumerated them. */
        public final java.util.List<String> choices = new java.util.ArrayList<String>();
        /** Lower bound, when a range was stated. */
        public Double min;
        /** Upper bound, when a range was stated. */
        public Double max;
        /** Default value, only when stated with strong evidence. */
        public String defaultValue;
        /** Which layer produced the result; for the "how was this built" view. */
        public String reason = "";
        /**
         * The kind of signal that produced the result, strongest first.
         *
         * <p>Carried out of inference so the caller can record it on the option,
         * letting every conclusion be explained and traced back to the evidence
         * that produced it (DESIGN.md P28).
         */
        public Schema.Option.Evidence evidence = Schema.Option.Evidence.NONE;

        public boolean hasRange() {
            return min != null || max != null;
        }
    }

    // ------------------------------------------------------------------
    // Layer 1: placeholder shapes
    // ------------------------------------------------------------------

    /** Suffix/whole-word conventions that name a type directly. */
    private static final Object[][] PLACEHOLDER_RULES = {
            // {regex on the normalised placeholder, kind, confidence}
            //
            // Whole-name metavariables first: these are the conventional short
            // spellings, and they must be recognised before the suffix patterns
            // below, which are written for longer compound names.
            {"^(dir|directory|folder)$", Schema.Option.Kind.INPUT_PATH, Confidence.MEDIUM},
            {"^(outdir|out_dir|outputdir|output_dir|outfolder|out_folder)$",
                    Schema.Option.Kind.OUTPUT_PATH, Confidence.MEDIUM},
            // A numbered suffix is a common way to distinguish several paths in
            // one synopsis: <dir1>, <dir2>, <file1>, <file2>. The suffix carries
            // no type meaning, so it is stripped before matching.
            {"^(dir|directory|folder)\\d+$", Schema.Option.Kind.INPUT_PATH, Confidence.MEDIUM},
            {"^(file|filename|filepath|path|paths|f)\\d*$",
                    Schema.Option.Kind.INPUT_PATH, Confidence.MEDIUM},
            {"^(input|in|query|subject|ref|reference|read)(file|path|filename|filepath)?$",
                    Schema.Option.Kind.INPUT_PATH, Confidence.HIGH},
            {"^(output|out|result|save|dest|destination|write)(file|path|filename|filepath)?$",
                    Schema.Option.Kind.OUTPUT_PATH, Confidence.HIGH},
            {"^.*(input|in|query|subject|ref|reference|read).*(file|path|filename|filepath)$",
                    Schema.Option.Kind.INPUT_PATH, Confidence.HIGH},
            {"^.*(output|out|result|save|dest|destination|write).*(file|path|filename|filepath)$",
                    Schema.Option.Kind.OUTPUT_PATH, Confidence.HIGH},
            {"^.*(file|filename|filepath)$", Schema.Option.Kind.INPUT_PATH, Confidence.MEDIUM},
            // Numeric conventions
            {"^(int|integer|num|number|count|size|length|n|#)(_.*)?$",
                    Schema.Option.Kind.NUMBER, Confidence.HIGH},
            {"^.*(int|integer|num|number|count|size)(_.*)?(value|val)?$",
                    Schema.Option.Kind.NUMBER, Confidence.HIGH},
            {"^(float|double|real|prob|probability|fraction|ratio|alpha|beta|lambda)(_.*)?$",
                    Schema.Option.Kind.NUMBER, Confidence.HIGH},
            {"^.*(float|double|real|prob|probability|fraction|ratio)(_.*)?(value|val)?$",
                    Schema.Option.Kind.NUMBER, Confidence.HIGH},
            {"^(bool|boolean|flag|switch)$", Schema.Option.Kind.SWITCH, Confidence.HIGH},
            {"^(str|string|text|name|label|mode|type|format|txt)$",
                    Schema.Option.Kind.TEXT, Confidence.MEDIUM}
    };

    /** Placeholder spellings that mean "a number" in the traditional style. */
    private static final Pattern NUMERIC_PLACEHOLDER = Pattern.compile("^(#|N|n|num|NUM|\\d+)$");

    // ------------------------------------------------------------------
    // Layer 2: leading noun of the description
    // ------------------------------------------------------------------

    /** Words that open a description and name a path that is read. */
    private static final Pattern LEAD_INPUT = Pattern.compile(
            "^\\s*(input|in|reads?|loads?|query|source|from)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEAD_PATH = Pattern.compile(
            "^\\s*(path|paths|file|files|filename|directory|dir|folder)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LEAD_OUTPUT = Pattern.compile(
            "^\\s*(output|out|writes?|saves?|stores?|destination|dest|result|to)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LEAD_NUMBER = Pattern.compile(
            "^\\s*(number|num|count|amount|size|length|maximum|minimum|max|min|"
            + "how many|no\\.|total|frequency|times|iterations|threads|cores)\\b",
            Pattern.CASE_INSENSITIVE);
    /**
     * Descriptions that lead with a numeric quantity. Matches a bare quantity
     * noun as well as a hyphenated qualifier in front of it, so that forms like
     * {@code "P-value"}, {@code "p-value"}, {@code "q-value"} and
     * {@code "F-value"} are all recognised without enumerating them.
     */
    private static final Pattern LEAD_FLOAT = Pattern.compile(
            "^\\s*(([A-Za-z]{1,3}-)?(value|probability|fraction|ratio|rate|threshold|"
            + "alpha|beta|lambda|penalty|score|weight|coefficient|epsilon|tolerance|"
            + "pvalue|p-value|qvalue|cutoff|identity|coverage|abundance))\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LEAD_SWITCH = Pattern.compile(
            "^\\s*(enable|disable|use|turn|activate|switch|flag|whether|if set|"
            + "do not|don't|skip|ignore|print|show|keep|allow|force)\\b",
            Pattern.CASE_INSENSITIVE);
    // A "leading noun implies a choice" rule was removed here. It was a list of
    // English keywords ("mode", "method", "format" ...), which is the pattern
    // DESIGN.md P16 forbids, and measurement showed it was unreachable: a
    // documented placeholder always settled the type first, and disabling it
    // broke none of the test suite. Such descriptions now fall through to the
    // generic text case, which is harmless.

    // ------------------------------------------------------------------
    // Layer 3: enumerated candidates
    // ------------------------------------------------------------------

    /**
     * {@code {a,b,c}} — a brace-delimited list. Brace syntax is a grammatical
     * marker rather than an English word, so it is reliable across tools.
     */
    private static final Pattern CHOICE_BRACE = Pattern.compile(
            "\\{\\s*([A-Za-z0-9_.-]+(?:\\s*,\\s*[A-Za-z0-9_.-]+){1,15})\\s*\\}");

    /**
     * {@code a|b|c} — pipe-separated alternatives.
     *
     * <p>Also syntax rather than prose. Requires three or more alternatives,
     * because {@code a|b} is more often a range notation or a typo.
     */
    private static final Pattern CHOICE_PIPE = Pattern.compile(
            "\\b([A-Za-z][A-Za-z0-9_.-]{0,14}(?:\\s*\\|\\s*[A-Za-z][A-Za-z0-9_.-]{0,14}){2,15})\\b");

    /**
     * A run of two or more comma-separated short identifiers.
     *
     * <p>This is the structural signal for everything else: whatever words
     * surround it, a sequence of short comma-separated tokens with no spaces
     * inside them is a list of alternatives. Recognition therefore does not
     * depend on the author writing "Options", "Choices", "Available" or any
     * other particular word (DESIGN.md P16).
     */
    private static final Pattern SHORT_TOKEN_LIST = Pattern.compile(
            "\\b([A-Za-z][A-Za-z0-9_.-]{0,19}"
            + "(?:\\s*,\\s*[A-Za-z][A-Za-z0-9_.-]{0,19}){1,15})\\b");

    /**
     * A run of two or more alternatives joined by {@code &}, {@code and},
     * {@code /} or {@code ;}, optionally individually quoted.
     *
     * <p>Captured separately because the separator set differs; the quoted
     * form ({@code "a" & "b"}) is common when values contain characters the
     * author wants to make explicit.
     */
    private static final Pattern ALT_LIST = Pattern.compile(
            "[\"']?([A-Za-z][A-Za-z0-9_.-]{1,23})[\"']?"
            + "(?:\\s*(?:&|\\band\\b|/|;|\\|)\\s*"
            + "[\"']?([A-Za-z][A-Za-z0-9_.-]{1,23})[\"']?){1,15}",
            Pattern.CASE_INSENSITIVE);

    // ------------------------------------------------------------------
    // Ranges and defaults
    // ------------------------------------------------------------------

    /**
     * A numeric range in brackets, parentheses, or described in words.
     *
     * <p>Covers {@code [0 - 1]}, {@code (0-100)}, {@code range: [0,1]} and the
     * prose form {@code (between 0 and 1)} that hand-written help often uses.
     */
    private static final Pattern RANGE = Pattern.compile(
            "(?:range\\s*:?\\s*)?[\\[(]\\s*(-?\\d+(?:\\.\\d+)?)\\s*(?:-|–|to|\\.\\.|,)\\s*"
            + "(-?\\d+(?:\\.\\d+)?)\\s*[\\])]"
            + "|between\\s+(-?\\d+(?:\\.\\d+)?)\\s+and\\s+(-?\\d+(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE);

    /** An explicit default. Requires the word "default" to be present. */
    private static final Pattern DEFAULT_EXPLICIT = Pattern.compile(
            "default\\s*(?:value)?\\s*[:=]\\s*([\\w./-]{1,24})", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEFAULT_EXPLICIT2 = Pattern.compile(
            "\\(\\s*default\\s*[:=]?\\s*([\\w./-]{1,24})\\)", Pattern.CASE_INSENSITIVE);

    /**
     * A bracketed value that is a plausible default: numeric, or an identifier
     * with no spaces.
     *
     * <p>Deliberately requires a digit or a single word. An earlier version
     * accepted any bracketed token up to 24 characters, which pulled prose out
     * of descriptions and injected it into commands as a parameter value —
     * strictly worse than leaving the field empty, because a filled field looks
     * intentional.
     */
    private static final Pattern DEFAULT_BRACKET_SAFE = Pattern.compile(
            "\\[\\s*([-+]?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?|[A-Za-z_][\\w./-]{0,15})\\s*\\]");

    /** Values that are clearly prose rather than a usable default. */
    private static final java.util.Set<String> NON_DEFAULT_WORDS =
            new java.util.HashSet<String>(java.util.Arrays.asList(
                    "specified", "estimated", "see", "required", "optional", "default",
                    "none", "true", "false", "auto", "automatic", "depends", "varies",
                    "not", "no", "yes", "the", "a", "an", "this", "that", "above",
                    "below", "following", "given", "provided", "supplied", "user",
                    // Quantifiers and phrases that begin a sentence rather than
                    // name a value ("Default=All available cores").
                    "all", "any", "some", "both", "each", "every", "same", "current",
                    "unlimited", "infinite", "maximum", "minimum", "disabled",
                    "enabled", "on", "off", "empty", "unset", "unlimited"));

    private TypeInference() {
    }

    /**
     * Infers the type of one option.
     *
     * @param arg         metavariable from the help, possibly empty
     * @param description description text
     * @param isSwitch    whether the parser already judged this a bare switch
     */
    public static Result infer(String arg, String description, boolean isSwitch) {
        Result r = new Result();
        String a = arg == null ? "" : arg.trim();
        String d = description == null ? "" : description.trim();

        // Ranges and choices are harvested regardless of which layer decides
        // the type, since they are orthogonal facts about the option. The
        // default is read first because a value list is corroborated by the
        // default being one of its members.
        harvestRange(r, d);
        String def = deriveDefault("", d);
        harvestChoices(r, a, d, def);
        harvestDefault(r, a, d);

        // Layer 1: the value position's placeholder.
        //
        // This runs before the candidate list, because the placeholder is the
        // author's direct statement about what belongs at that position while a
        // candidate list is inferred from the wording around it. Taking the weaker
        // signal first is wrong in a way that is easy to miss: a description
        // reading "Input file in several formats (clustal, fasta, NBRF/PIR,
        // nexus, phylip3.2, phylip)" enumerates the formats the tool *accepts*,
        // not the values the option *takes*. Read as a candidate list it turned a
        // file argument into a dropdown of format names.
        Result l1 = fromPlaceholder(a);
        // A placeholder that merely says "some text" has excluded nothing, so it
        // must not override a candidate list. A list is a positive statement about
        // the values — the option takes one of these — whereas <txt> or <string>
        // only says the value is textual, which a choice also is.
        //
        // A placeholder naming a real kind does outrank the list, handled below.
        boolean placeholderIsUnqualifiedText = l1 != null
                && l1.kind == Schema.Option.Kind.TEXT
                && r.choices.size() >= 2;
        if (l1 != null && !placeholderIsUnqualifiedText) {
            r.kind = l1.kind;
            r.confidence = l1.confidence;
            r.reason = l1.reason;
            r.evidence = Schema.Option.Evidence.PLACEHOLDER;
            // A path is not selected from a list, so a run of names in the
            // description cannot be this option's values. It is far more likely
            // to describe what the tool supports. Clearing it also stops the form
            // from offering a dropdown where a file picker belongs.
            if (r.kind == Schema.Option.Kind.INPUT_PATH
                    || r.kind == Schema.Option.Kind.OUTPUT_PATH) {
                r.choices.clear();
            }
            return r;
        }

        // Layer 1b: an enumerated candidate list.
        //
        // Reached only when the placeholder names no kind, which is what a generic
        // placeholder such as <s> or <x> does. There the list is the strongest
        // evidence available, and it is what distinguishes an option taking one of
        // a few named values from one taking free text.
        if (r.choices.size() >= 2) {
            r.kind = Schema.Option.Kind.CHOICE;
            r.confidence = Confidence.HIGH;
            r.reason = "enumerated candidates in help text";
            r.evidence = Schema.Option.Evidence.CHOICES;
            return r;
        }

        // Layer 1b: the default value's own shape.
        //
        // A generic placeholder such as <s> says nothing about the type, but a
        // default of [0.62] or [Stockholm] says a great deal. This runs after the
        // placeholder because an explicit placeholder is the author's own
        // statement of shape and outranks a value they merely chose; it runs
        // before the descriptive layers because a formatted default is stronger
        // evidence than prose (DESIGN.md P27).
        Result l1b = fromDefaultShape(def);
        if (l1b != null) {
            r.kind = l1b.kind;
            r.confidence = l1b.confidence;
            r.reason = l1b.reason;
            r.evidence = Schema.Option.Evidence.DEFAULT_SHAPE;
            return r;
        }

        // Layer 1c: a stated range.
        //
        // A bound is a formatted fact about the parameter, not a reading of a
        // sentence, so it outranks the descriptive layers. The kind follows from
        // the bound: only a number has a numeric range.
        if (r.hasRange() && !a.isEmpty()) {
            r.kind = Schema.Option.Kind.NUMBER;
            r.confidence = Confidence.MEDIUM;
            r.reason = "the help states a numeric range";
            r.evidence = Schema.Option.Evidence.RANGE;
            return r;
        }

        // Layer 2: leading noun of the description. From here on the conclusion
        // rests on the author's wording, which is the weakest usable signal.
        Result l2 = fromLeadingNoun(d);
        if (l2 != null) {
            r.kind = l2.kind;
            r.confidence = l2.confidence;
            r.reason = l2.reason;
            r.evidence = Schema.Option.Evidence.HEURISTIC;
            return r;
        }

        // A bare option with no argument: normally a switch, but the
        // description may still reveal that a value is expected. Hand-written
        // help often omits the placeholder entirely, so this check runs before
        // concluding "switch".
        if (isSwitch && a.isEmpty()) {
            if (descriptionImpliesValue(d)) {
                // The option needs a value that the help failed to name.
                // A number is the safest guess for a described quantity, and
                // free text otherwise.
                Result l4 = fromKeywords(d);
                if (l4 != null) {
                    r.kind = l4.kind;
                    r.confidence = Confidence.LOW;
                    r.reason = l4.reason + " (no placeholder in help)";
                    r.evidence = Schema.Option.Evidence.HEURISTIC;
                    return r;
                }
                r.kind = Schema.Option.Kind.TEXT;
                r.confidence = Confidence.LOW;
                r.reason = "description implies a value but the help names no placeholder";
                r.evidence = Schema.Option.Evidence.HEURISTIC;
                return r;
            }
            r.kind = Schema.Option.Kind.SWITCH;
            // The switch conclusion is only as good as the evidence: with an
            // empty description there is nothing to go on. No evidence is
            // recorded, because "takes no value" is the absence of a value
            // position rather than a statement about a type.
            r.confidence = d.isEmpty() ? Confidence.LOW : Confidence.MEDIUM;
            r.reason = d.isEmpty()
                    ? "no argument and no description"
                    : "no argument and no value implied";
            r.evidence = Schema.Option.Evidence.NONE;
            return r;
        }

        // Layer 4: weakest signal — search the whole description.
        Result l4 = fromKeywords(d);
        if (l4 != null) {
            r.kind = l4.kind;
            r.confidence = Confidence.LOW;
            r.reason = l4.reason;
            r.evidence = Schema.Option.Evidence.HEURISTIC;
            return r;
        }

        // Nothing recognised: free text is always safe.
        r.kind = a.isEmpty() && isSwitch
                ? Schema.Option.Kind.SWITCH : Schema.Option.Kind.TEXT;
        r.confidence = Confidence.LOW;
        r.reason = "no type signal found";
        r.evidence = Schema.Option.Evidence.NONE;
        return r;
    }

    // ------------------------------------------------------------------
    // Layer implementations
    // ------------------------------------------------------------------

    /** Layer 1: recognise the placeholder's shape. */
    private static Result fromPlaceholder(String arg) {
        if (arg == null || arg.isEmpty()) {
            return null;
        }
        // Strip decoration: <x>, [x], X.
        String a = arg.replace("<", "").replace(">", "")
                      .replace("[", "").replace("]", "").trim();
        if (a.isEmpty()) {
            return null;
        }
        // Traditional numeric placeholders.
        if (NUMERIC_PLACEHOLDER.matcher(a).matches()) {
            Result r = new Result();
            r.kind = Schema.Option.Kind.NUMBER;
            r.confidence = Confidence.HIGH;
            r.reason = "placeholder '" + arg + "' conventionally means a number";
            return r;
        }
        String lower = a.toLowerCase().replace("-", "_");
        for (Object[] rule : PLACEHOLDER_RULES) {
            Pattern p = Pattern.compile((String) rule[0]);
            if (p.matcher(lower).matches()) {
                Result r = new Result();
                r.kind = (Schema.Option.Kind) rule[1];
                r.confidence = (Confidence) rule[2];
                r.reason = "placeholder '" + arg + "' matches a known naming convention";
                return r;
            }
        }
        return null;
    }

    /** Layer 2: the description's leading noun. */
    private static Result fromLeadingNoun(String d) {
        if (d == null || d.isEmpty()) {
            return null;
        }
        // Only the first clause is considered: later text often mentions
        // unrelated nouns ("... - see the manual file").
        String lead = firstClause(d);
        Result r = new Result();
        if (LEAD_OUTPUT.matcher(lead).find() && mentionsPathish(lead)) {
            r.kind = Schema.Option.Kind.OUTPUT_PATH;
            r.confidence = Confidence.MEDIUM;
            r.reason = "description begins by describing an output path";
            return r;
        }
        if (LEAD_INPUT.matcher(lead).find() && mentionsPathish(lead)) {
            r.kind = Schema.Option.Kind.INPUT_PATH;
            r.confidence = Confidence.MEDIUM;
            r.reason = "description begins by describing an input path";
            return r;
        }
        if (LEAD_PATH.matcher(lead).find()) {
            // A path whose direction is unstated: this is the case where
            // guessing read-vs-write would be unreliable, so a neutral file
            // picker is offered instead. See DESIGN.md P10.
            r.kind = Schema.Option.Kind.INPUT_PATH;
            r.confidence = Confidence.LOW;
            r.reason = "description names a path without stating its direction";
            return r;
        }
        if (LEAD_NUMBER.matcher(lead).find()) {
            r.kind = Schema.Option.Kind.NUMBER;
            r.confidence = Confidence.MEDIUM;
            r.reason = "description begins with a quantity noun";
            return r;
        }
        if (LEAD_FLOAT.matcher(lead).find()) {
            r.kind = Schema.Option.Kind.NUMBER;
            r.confidence = Confidence.MEDIUM;
            r.reason = "description begins with a numeric quantity";
            return r;
        }
        if (LEAD_SWITCH.matcher(lead).find()) {
            r.kind = Schema.Option.Kind.SWITCH;
            r.confidence = Confidence.MEDIUM;
            r.reason = "description begins with an action verb";
            return r;
        }
        return null;
    }

    /**
     * Layer 4: direction from an action word, combined with a structural path.
     *
     * <p>A verb such as "write" or "read" states the <em>direction</em>; it does
     * not establish that the value is a path at all. Both halves are therefore
     * required, and the second half must be structural — a token shaped like a
     * file name or an explicit filesystem path — rather than the presence of the
     * word "file".
     *
     * <p>Requiring both is what keeps {@code "write 3 files"} from being read as
     * an output path: the verb is there, but nothing in the clause is shaped like
     * a path. It is a count, and a later layer classifies it as one
     * (DESIGN.md P27).
     */
    private static Result fromKeywords(String d) {
        if (d == null || d.isEmpty()) {
            return null;
        }
        String s = d.toLowerCase();
        Result r = new Result();
        boolean pathShaped = mentionsPathish(d);
        if (pathShaped
                && (s.contains("output") || s.contains("write") || s.contains("save"))) {
            r.kind = Schema.Option.Kind.OUTPUT_PATH;
            r.confidence = Confidence.LOW;
            r.reason = "an action word and a path-shaped value";
            return r;
        }
        if (pathShaped
                && (s.contains("input") || s.contains("read") || s.contains("load"))) {
            r.kind = Schema.Option.Kind.INPUT_PATH;
            r.confidence = Confidence.LOW;
            r.reason = "an action word and a path-shaped value";
            return r;
        }
        if (s.contains("number of") || s.contains("integer") || s.contains("as an integer")
                || s.contains("count")) {
            r.kind = Schema.Option.Kind.NUMBER;
            r.confidence = Confidence.LOW;
            r.reason = "description mentions a whole number";
            return r;
        }
        if (s.contains("float") || s.contains("decimal") || s.contains("between 0 and 1")
                || s.contains("value to use") || s.contains("-value")
                || s.contains("probability") || s.contains("fraction")
                || s.contains("ratio") || s.contains("threshold")) {
            r.kind = Schema.Option.Kind.NUMBER;
            r.confidence = Confidence.LOW;
            r.reason = "description mentions a fractional value";
            return r;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Metadata harvesters
    // ------------------------------------------------------------------

    /**
     * Collects a numeric range stated in the description.
     *
     * <p>Covers two spellings:
     *
     * <ul>
     *   <li>a bracketed pair — {@code [0..2]}, {@code (0-100)}, {@code between 1
     *       and 5}</li>
     *   <li>a <b>single-sided bound</b> — {@code (n>=120)}, {@code (x<=1)},
     *       {@code n > 0}</li>
     * </ul>
     *
     * <p>The single-sided form is at least as common and was previously missed
     * entirely, so options documented that way lost their bound and often their
     * type as well.
     */
    private static void harvestRange(Result r, String d) {
        if (d == null || d.isEmpty()) {
            return;
        }
        Matcher m = RANGE.matcher(d);
        if (m.find()) {
            // The pattern has two alternatives; only one pair of groups is set.
            String lo = m.group(1) != null ? m.group(1) : m.group(3);
            String hi = m.group(2) != null ? m.group(2) : m.group(4);
            if (lo == null || hi == null) {
                return;
            }
            try {
                r.min = Double.valueOf(lo);
                r.max = Double.valueOf(hi);
                if (r.min.doubleValue() > r.max.doubleValue()) {
                    Double t = r.min;
                    r.min = r.max;
                    r.max = t;
                }
            } catch (NumberFormatException ignored) {
                r.min = null;
                r.max = null;
            }
            return;
        }
        // A one-sided bound: "(n>=120)", "(x<=1)", "n > 0", ">= 5".
        Matcher one = ONE_SIDED_RANGE.matcher(d);
        if (one.find()) {
            String value = one.group(2);
            try {
                double v = Double.parseDouble(value);
                boolean lower = one.group(1).startsWith(">");
                if (lower) {
                    r.min = Double.valueOf(v);
                } else {
                    r.max = Double.valueOf(v);
                }
            } catch (NumberFormatException ignored) {
                // Not a number after all; leave the range unset.
            }
        }
    }

    /**
     * A bound stated on one side only: {@code (n>=120)}, {@code x<=1}, {@code > 0}.
     *
     * <p>Requires an explicit comparison operator, so ordinary prose containing a
     * number is not mistaken for a bound.
     */
    private static final Pattern ONE_SIDED_RANGE = Pattern.compile(
            "[()\\[\\]{]?\\s*[A-Za-z_]?\\s*(>=|<=|>|<|\\u2265|\\u2264)\\s*=?\\s*"
            + "(-?\\d+(?:\\.\\d+)?)\\s*[)\\]}]?");

    /**
     * Collects candidate values from an option's help text.
     *
     * <p>Recognition is structural and self-consistent, never keyword-driven
     * (DESIGN.md P16). The signals, strongest first:
     *
     * <ol>
     *   <li>brace or pipe syntax — grammatical markers</li>
     *   <li>a comma-separated run of short identifiers whose text is
     *       corroborated elsewhere about the same option: one of them equals
     *       the default value, or the run sits next to a colon or bracket</li>
     *   <li>alternatives joined by {@code &}, {@code and}, {@code /}</li>
     * </ol>
     *
     * <p>The corroboration requirement in (2) is what keeps ordinary prose out.
     * Any sentence can contain two commas; what marks a value list is that its
     * contents agree with something else the help states about the same option.
     */
    private static void harvestChoices(Result r, String arg, String d, String explicitDefault) {
        // The placeholder may itself carry the alternatives, either as a brace
        // list ({a,b,c}) or as pipes (a|b|c). Both are syntax, so neither needs
        // corroboration.
        if (arg != null && !arg.isEmpty()) {
            Matcher brace = CHOICE_BRACE.matcher(arg);
            if (brace.find()) {
                addChoices(r, brace.group(1));
                if (r.choices.size() >= 2) {
                    return;
                }
            }
            if (arg.indexOf('|') >= 0) {
                addChoices(r, arg.replace('|', ','));
                if (r.choices.size() >= 2) {
                    return;
                }
            }
        }

        String text = d == null ? "" : d;

        Matcher braceInDesc = CHOICE_BRACE.matcher(text);
        if (braceInDesc.find()) {
            addChoices(r, braceInDesc.group(1));
            if (r.choices.size() >= 2) {
                return;
            }
        }

        // Pipe alternatives: syntax, so no corroboration needed.
        Matcher pipe = CHOICE_PIPE.matcher(text);
        if (pipe.find()) {
            addChoices(r, pipe.group(1).replace('|', ','));
            if (r.choices.size() >= 2) {
                return;
            }
        }

        // & / and / separated alternatives, optionally quoted.
        //
        // This shape is how prose joins two ordinary words just as often as it is
        // how a help text lists values. "show this help message and exit" and
        // "CLUSTAL, GCG and FASTA" have the same connective structure, so the
        // connective alone proves nothing and both checks below are required
        // (DESIGN.md P16, P27).
        Matcher alt = ALT_LIST.matcher(text);
        if (alt.find() && alt.group(2) != null) {
            List<String> vals = new ArrayList<String>();
            Matcher tok = Pattern.compile("[\"']?([A-Za-z][A-Za-z0-9_.-]{1,23})[\"']?")
                    .matcher(alt.group());
            while (tok.find()) {
                String v = tok.group(1);
                // Drop connective words that the separator pattern swept up.
                String lower = v.toLowerCase();
                if (lower.equals("and") || lower.equals("or") || lower.equals("the")) {
                    continue;
                }
                if (!vals.contains(v)) {
                    vals.add(v);
                }
            }
            if (vals.size() >= 2
                    && (isCorroborated(alt.group(), vals, text, arg, explicitDefault)
                        || looksLikeValueSet(vals))) {
                for (String v : vals) {
                    if (!r.choices.contains(v)) {
                        r.choices.add(v);
                    }
                }
                return;
            }
        }

        // Comma-separated short identifiers, accepted only when corroborated.
        Matcher list = SHORT_TOKEN_LIST.matcher(text);
        while (list.find()) {
            String run = list.group(1);
            List<String> vals = splitTokens(run);
            if (vals.size() < 2) {
                continue;
            }
            if (!isCorroborated(run, vals, text, arg, explicitDefault)
                    && !looksLikeValueSet(vals)) {
                continue;
            }
            for (String v : vals) {
                if (!r.choices.contains(v)) {
                    r.choices.add(v);
                }
            }
            return;
        }
    }

    /**
     * True when the candidates are shaped like values rather than like prose.
     *
     * <p>Values in an enumeration usually carry something an ordinary English
     * word does not: a digit, an underscore, a hyphen, a dot, or an all-capitals
     * spelling. Prose joined by a connective — "message and exit", "phase and
     * reduce" — has none of those.
     *
     * <p>This is a statement about the <em>shape of the tokens</em>, not a search
     * for words that tend to appear near enumerations, which DESIGN.md P16 rules
     * out. When the shape test fails the run is not necessarily wrong; it is
     * simply not evidenced, so it is left to the layers that read the option's
     * own fields.
     */
    private static boolean looksLikeValueSet(List<String> vals) {
        if (vals.isEmpty()) {
            return false;
        }
        int distinctive = 0;
        for (String v : vals) {
            boolean punctuation = v.matches(".*[0-9_.\\-].*");
            boolean allCaps = v.length() >= 2 && v.equals(v.toUpperCase());
            if (punctuation || allCaps) {
                distinctive++;
            }
        }
        // More than half must carry a value-like shape, so that one incidental
        // token cannot qualify a sentence.
        return distinctive * 2 > vals.size();
    }

    /**
     * Splits a comma-separated run into plausible values.
     *
     * <p>Returns an empty list when any part fails the shape test, since one bad
     * part means the whole run was prose rather than a value list.
     */
    private static List<String> splitTokens(String run) {
        List<String> out = new ArrayList<String>();
        for (String part : run.split("\\s*,\\s*")) {
            String c = part.trim();
            if (c.isEmpty() || c.length() > 20 || c.contains(" ")) {
                return new ArrayList<String>();
            }
            // An all-numeric run is a set of valid numbers, not a choice set.
            if (c.matches("-?\\d+(\\.\\d+)?")) {
                return new ArrayList<String>();
            }
            // Values are identifiers, not sentences: reject anything that is
            // obviously a word from running prose by requiring no sentence
            // punctuation and a plausible identifier shape.
            if (!c.matches("[A-Za-z][A-Za-z0-9_.-]*")) {
                return new ArrayList<String>();
            }
            if (NON_VALUE_WORDS.contains(c.toLowerCase())) {
                return new ArrayList<String>();
            }
            if (!out.contains(c)) {
                out.add(c);
            }
        }
        return out.size() >= 2 ? out : new ArrayList<String>();
    }

    /**
     * Decides whether a comma-separated run really enumerates values.
     *
     * <p>Corroboration comes from the rest of the help text agreeing that these
     * tokens are meaningful for this option:
     *
     * <ul>
     *   <li>one of them is the option's default value</li>
     *   <li>one of them appears in the option's placeholder or description
     *       as a quoted value</li>
     *   <li>the run is immediately preceded by a colon or introduced by a
     *       bracket</li>
     * </ul>
     *
     * <p>This is what distinguishes a value list from a sentence containing
     * commas, without consulting any list of English keywords.
     */
    private static boolean isCorroborated(String run, List<String> values,
                                          String text, String arg, String explicitDefault) {
        // The option's own default must be one of the candidates.
        String def = explicitDefault;
        if (def == null || def.isEmpty()) {
            def = deriveDefault(run, text);
        }
        if (def != null && !def.isEmpty()) {
            for (String v : values) {
                if (v.equalsIgnoreCase(def)) {
                    return true;
                }
            }
        }
        // The placeholder may name one of the values.
        if (arg != null && !arg.isEmpty()) {
            for (String v : values) {
                if (arg.equalsIgnoreCase("<" + v + ">") || arg.equalsIgnoreCase(v)) {
                    return true;
                }
            }
        }
        // A colon immediately before the run marks it as an enumeration.
        int at = text.indexOf(run);
        if (at > 0) {
            String before = text.substring(0, at).trim();
            if (before.endsWith(":") || before.endsWith("[")) {
                return true;
            }
            // Quoted values are marked as such by the author.
            if (before.endsWith("\"") || before.endsWith("'")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Infers a type from the shape of the stated default value.
     *
     * <p>Used when the placeholder is generic ({@code <s>}, {@code <x>}) or absent,
     * so the author's own word for the value tells us nothing. The default they
     * chose does:
     *
     * <ul>
     *   <li>{@code [0.62]} — a number, so the option takes a number</li>
     *   <li>{@code [120]} — likewise</li>
     *   <li>{@code [Stockholm]} — a word, so the option takes one of a small set
     *       of names rather than free text</li>
     *   <li>{@code [human.cut]} — a file name</li>
     * </ul>
     *
     * <p><b>Only formatted evidence is used</b>, never a word appearing somewhere
     * in the prose: a description mentioning "file" may well belong to a numeric
     * option ("write 3 files"). A default value has no such ambiguity, because the
     * author stated it as the value itself (DESIGN.md P27).
     *
     * <p>Returns null when the default says nothing usable, leaving the decision
     * to the descriptive layers.
     */
    private static Result fromDefaultShape(String def) {
        if (def == null || def.isEmpty()) {
            return null;
        }
        String v = def.trim();
        // Placeholder-shaped defaults carry no type information of their own.
        if (v.startsWith("<") || v.equals("-")) {
            return null;
        }
        // A number: integers, decimals, and negatives, optionally with units.
        if (v.matches("[-+]?\\d+(\\.\\d+)?([eE][-+]?\\d+)?%?")) {
            return result(Schema.Option.Kind.NUMBER, Confidence.MEDIUM,
                    "default value " + v + " is numeric");
        }
        // A range default such as "10-20" or "1..5" is a number with bounds.
        if (v.matches("\\d+\\s*[-–]\\s*\\d+")
                || v.matches("\\d+\\s*\\.\\.\\s*\\d+")) {
            return result(Schema.Option.Kind.NUMBER, Confidence.MEDIUM,
                    "default value " + v + " is a numeric range");
        }
        // A file-looking default: has an extension, or is a path.
        if (v.matches("[^\\s/\\\\]+\\.[A-Za-z][A-Za-z0-9]{0,5}")
                || v.startsWith("/") || v.startsWith("./")
                || v.startsWith("~/") || v.contains("\\")) {
            return result(Schema.Option.Kind.INPUT_PATH, Confidence.MEDIUM,
                    "default value " + v + " looks like a file");
        }
        // A single capitalised word can be the name of one member of a set, which
        // is how these tools spell a default among enumerated choices. Restricted
        // to a short word so that a sentence fragment picked up as a "default"
        // cannot qualify, and skipped for words that only read as prose.
        if (v.matches("[A-Z][A-Za-z0-9_-]{1,20}") && !PROSE_DEFAULT_WORDS.contains(v)) {
            return result(Schema.Option.Kind.CHOICE, Confidence.LOW,
                    "default value " + v + " names one of a set");
        }
        return null;
    }

    /**
     * Capitalised words that appear as defaults but name no value.
     *
     * <p>"Default=All available cores" yields the word {@code All}, which is the
     * start of a sentence rather than a member of any set. Treating it as a choice
     * turned a numeric option into a dropdown of one.
     */
    private static final java.util.Set<String> PROSE_DEFAULT_WORDS =
            new java.util.HashSet<String>(java.util.Arrays.asList(
                    "All", "None", "Same", "Auto", "Automatic", "Default", "Full",
                    "Empty", "Normal", "Standard", "Various", "Check", "See"));

    private static Result result(Schema.Option.Kind kind, Confidence c, String why) {
        Result r = new Result();
        r.kind = kind;
        r.confidence = c;
        r.reason = why;
        return r;
    }

    /**
     * Reads the default value stated in the text.
     *
     * <p>Two families of spelling occur and both are needed:
     *
     * <ul>
     *   <li>named — {@code default: msa}, {@code Default = 120}</li>
     *   <li><b>bare bracketed</b> — {@code [0.5]}, {@code [Stockholm]},
     *       {@code [Ehuman.cut]}</li>
     * </ul>
     *
     * <p>Only the named form used to be recognised, which left the default
     * invisible for a large share of options and, in turn, starved the inference
     * layer that reads the default of any evidence.
     *
     * <p>A bracketed token counts only when it is a single value — {@code [the
     * file to read]} is prose, not a default.
     */
    private static String deriveDefault(String run, String text) {
        Matcher m = DEFAULT_EXPLICIT.matcher(text);
        if (m.find()) {
            return m.group(1).trim();
        }
        m = DEFAULT_EXPLICIT2.matcher(text);
        if (m.find()) {
            return m.group(1).trim();
        }
        // "[Default = msa]" and "[Default: msa]" spellings.
        Matcher eq = Pattern.compile(
                "\\[\\s*default\\s*[:=]?\\s*([A-Za-z0-9_.-]{1,24})\\s*\\]",
                Pattern.CASE_INSENSITIVE).matcher(text);
        if (eq.find()) {
            return eq.group(1).trim();
        }
        // A bare bracketed value: "[0.5]", "[120]", "[Stockholm]", "[Ehuman.cut]".
        Matcher bare = BRACKETED_VALUE.matcher(text);
        if (bare.find()) {
            return bare.group(1).trim();
        }
        return "";
    }

    /**
     * A bracketed default value.
     *
     * <p>Matches a number, a dotted file name, a path, or a single word — the
     * shapes a default actually takes. Multi-word bracketed content is prose and
     * is deliberately not matched.
     */
    private static final Pattern BRACKETED_VALUE = Pattern.compile(
            "\\[\\s*([-+]?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?%?"
            + "|[A-Za-z0-9_][\\w.\\-/]{0,23}\\.[A-Za-z0-9]{1,6}"
            + "|/[\\w./\\-]{1,40}"
            + "|[A-Za-z][A-Za-z0-9_-]{1,20}"
            + ")\\s*\\]");

    /** Adds a comma-separated run of values, rejecting runs that are prose. */
    private static void addChoices(Result r, String list) {
        List<String> vals = splitTokens(list);
        if (vals.isEmpty()) {
            return;
        }
        for (String v : vals) {
            if (!r.choices.contains(v)) {
                r.choices.add(v);
            }
        }
    }

    /** Words too common in prose to be a value on their own. */
    private static final java.util.Set<String> NON_VALUE_WORDS =
            new java.util.HashSet<String>(java.util.Arrays.asList(
                    "and", "or", "the", "a", "an", "of", "to", "in", "on", "for",
                    "with", "by", "is", "are", "be", "it", "this", "that", "see",
                    "or", "not", "but", "as", "at", "from", "will", "can", "if"));

    /**
     * Harvests a default value, but only on strong evidence.
     *
     * <p>Accepts an explicit {@code default: X} / {@code (default X)} in any
     * position, or a bracketed token that is numeric or a single identifier
     * that is not ordinary prose.
     *
     * <p>Bracketed words are additionally rejected when the option has
     * enumerated candidates, because there the bracket describes the current
     * value rather than a usable default.
     *
     * <p>The returned value is always the cleaned form, so trailing sentence
     * punctuation never reaches a command line.
     */
    private static void harvestDefault(Result r, String arg, String d) {
        if (d == null || d.isEmpty()) {
            return;
        }
        Matcher m = DEFAULT_EXPLICIT.matcher(d);
        if (m.find()) {
            String v = cleanDefault(m.group(1));
            if (v != null) {
                r.defaultValue = v;
                return;
            }
        }
        m = DEFAULT_EXPLICIT2.matcher(d);
        if (m.find()) {
            String v = cleanDefault(m.group(1));
            if (v != null) {
                r.defaultValue = v;
                return;
            }
        }
        // Bracketed form, only when no candidate list was found.
        if (!r.choices.isEmpty()) {
            return;
        }
        Matcher br = DEFAULT_BRACKET_SAFE.matcher(d);
        while (br.find()) {
            String v = cleanDefault(br.group(1));
            if (v != null) {
                r.defaultValue = v;
                return;
            }
        }
    }

    /**
     * Normalises a candidate default and decides whether it is usable.
     *
     * @return the cleaned value, or null when the token is not a usable default
     */
    private static String cleanDefault(String raw) {
        if (raw == null) {
            return null;
        }
        // Strip surrounding punctuation that belongs to the sentence rather
        // than the value: "Default=0.05." ends a sentence.
        String v = raw.trim().replaceAll("^[.,;:]+", "").replaceAll("[.,;:]+$", "");
        if (v.isEmpty() || v.length() > 24) {
            return null;
        }
        if (NON_DEFAULT_WORDS.contains(v.toLowerCase())) {
            return null;
        }
        if (v.matches("[-+]?\\d+(\\.\\d+)?([eE][-+]?\\d+)?")) {
            return v;
        }
        if (v.contains(" ")) {
            return null;
        }
        return v.matches("[A-Za-z_][\\w./-]*") ? v : null;
    }

    /**
     * True when a clause names a path-like <em>object</em>, structurally.
     *
     * <p>This used to be a keyword test — the clause was checked for the words
     * "file", "path", "dir" and so on. That is exactly the kind of criterion
     * DESIGN.md P27 rules out, and it misfired in practice: {@code "write 3
     * files"} is a numeric story told with the word "file", yet it was treated as
     * a path description and the option became an output path.
     *
     * <p>The replacement asks a structural question instead: does the clause
     * contain a token that is shaped like a file name or a directory path? A
     * name with an extension ({@code out.fa}, {@code results.txt}), an absolute
     * path, a relative path, or a bare directory reference. Words alone no longer
     * qualify.
     *
     * <p>Two nouns are admitted as naming a path-shaped <em>object</em> rather
     * than as keywords: a directory is a location whatever its name, so
     * "Output directory" does describe a path even without a literal path in the
     * text. This is a statement about what the noun denotes, not a search for
     * words that tend to accompany paths.
     *
     * <p>The path may also be named by the placeholder, which is handled a layer
     * earlier, so missing it here loses nothing.
     */
    private static boolean mentionsPathish(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        // A token with a file extension: "out.fa", "results.txt", "a.b.c".
        if (PATH_TOKEN.matcher(s).find()) {
            return true;
        }
        String t = s.toLowerCase();
        // An explicit filesystem path.
        if (t.contains("/") || t.contains("\\")) {
            return true;
        }
        // A noun that denotes a location in the filesystem.
        return t.contains("directory") || t.contains("folder");
    }

    /**
     * A token shaped like a file name: a word, a dot, and a short extension.
     *
     * <p>Restricted to plausible extensions so that a sentence ending in a period
     * is not read as a file name, and so that a dotted version number such as
     * {@code 1.2.3} is not either.
     */
    private static final Pattern PATH_TOKEN = Pattern.compile(
            "(?:^|[\\s(\\[{,'\"])[\\w-]+\\.[A-Za-z][A-Za-z0-9]{0,5}"
            + "(?![\\w.])");

    /**
     * True when the description implies the option takes a value.
     *
     * <p>Deliberately does <b>not</b> treat the mere presence of a default as
     * implying a value. Hand-written help often documents a switch by contrast
     * with the default it overrides — {@code "--clustalout   Output: clustal
     * format, default: fasta"} takes no value at all — so a default is evidence
     * about the option's effect, not about its arity.
     */
    private static boolean descriptionImpliesValue(String d) {
        if (d == null || d.isEmpty()) {
            return false;
        }
        // A stated range means the option accepts a number.
        if (RANGE.matcher(d).find()) {
            return true;
        }
        String s = d.toLowerCase();
        return s.contains("(n>") || s.contains("(x>") || s.contains("<n>")
                || s.contains("<x>") || s.contains("number of")
                || s.contains("integer") || s.contains("float")
                || s.contains("as an argument") || s.contains("provide the")
                || s.contains("a value") || s.contains("value between");
    }

    /** The first clause of a description, stopping at a separator. */
    private static String firstClause(String d) {
        String s = d;
        int cut = s.length();
        for (String sep : new String[]{" - ", " -- ", "; ", ". ", ", which"}) {
            int i = s.indexOf(sep);
            if (i > 0 && i < cut) {
                cut = i;
            }
        }
        String first = s.substring(0, cut).trim();
        // A very long first clause is a paragraph, not a lead noun.
        if (first.length() > 90) {
            first = first.substring(0, 90);
        }
        return first;
    }
}

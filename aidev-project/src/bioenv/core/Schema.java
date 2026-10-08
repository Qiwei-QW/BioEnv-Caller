package bioenv.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The parameter schema of one command, learned entirely from its help output.
 *
 * <p>A schema is a flat list of {@link Option} plus a list of subcommands. When
 * subcommands exist, each one may itself carry a schema, discovered by running
 * the same probe one level deeper — see {@link SchemaBuilder}.
 *
 * <p>Nothing here is tool-specific: the same type describes every command,
 * single-level or multi-level (DESIGN.md P5).
 */
public final class Schema {

    /** One documented option or argument. */
    public static final class Option implements Comparable<Option> {

        /**
         * How the option is rendered and validated.
         *
         * <p>The names are the ones TBtools gives the same controls, so a choice made
         * here reads the same as a choice made there. Two are the plugin's own: a bare
         * flag, which TBtools has no control for because it has no notion of a
         * command-line switch, and a number, which none of its controls covers.
         *
         * <p>The plugin states no kind by default — every parameter starts as text —
         * so these exist to be chosen, not inferred.
         *
         * <h2>Three TBtools kinds are deliberately absent</h2>
         *
         * <p>{@code PasteAsInFilePath}, {@code TextAsOutFilePath} and
         * {@code FilePathListInFile} were offered and did not do what their names
         * promise. Their whole point in TBtools is the difference between a path and
         * the text that goes in the file; here, all three rendered and behaved exactly
         * as a plain input or output path, so choosing one changed nothing except the
         * label. Offering a choice that does nothing is worse than not offering it, so
         * they are gone and the remaining kinds are the ones with a real effect.
         *
         * <p>Removing them is safe for existing records:
         * {@link CorrectionStore} skips a stored name it no longer recognises rather
         * than failing, so a saved {@code PASTE_IN_PATH} is ignored and the parameter
         * simply keeps whatever {@link #kind} the parser gave it.
         */
        public enum Kind {
            /** No control: the parameter is not offered in the form. */
            NONE,
            /** A bare flag, no value. TBtools: CheckBox. */
            SWITCH,
            /** Free text value. TBtools: Text. */
            TEXT,
            /** A number; may carry a range. The plugin's own. */
            NUMBER,
            /** A path that must already exist. TBtools: InFilePath. */
            INPUT_PATH,
            /** A path that will be created. TBtools: OutFilePath. */
            OUTPUT_PATH,
            /** Several paths as one comma-separated argument. TBtools: FilePathList. */
            PATH_LIST,
            /** A directory. TBtools: DirectoryPath. */
            DIRECTORY_PATH,
            /** One of a small set of literal values. TBtools: Choice. */
            CHOICE
        }

        /** Primary flag, e.g. {@code -s} or {@code --tblout}. Empty for positionals. */
        public String flag = "";
        /** Alternative spellings, e.g. {@code --seqtype} for {@code -s}. */
        public final List<String> aliases = new ArrayList<String>();
        /** Metavariable from the help text, e.g. {@code <inputfile>} or {@code NUM}. */
        public String arg = "";
        /** Description text with the flag removed. */
        public String description = "";
        /** Section the option appeared under, when the help uses sections. */
        public String group = "";
        /** Default value stated in the help, when present. */
        public String defaultValue = "";
        /** Literal values the help lists, when it enumerates choices. */
        public final List<String> choices = new ArrayList<String>();
        /** Inclusive lower bound when the help states a range. */
        public Double min;
        /** Inclusive upper bound when the help states a range. */
        public Double max;
        /** True when the option takes no value. */
        public boolean isSwitch;

        /**
         * True when the help showed an equals sign between the flag and its
         * value, e.g. {@code -OUTFILE=}.
         *
         * <p>Kept separate from {@link #isSwitch} because the two say different
         * things: a switch takes nothing, whereas this marker asserts that a
         * value belongs here even when the author wrote none. Conflating them is
         * what made {@code -OUTFILE=} look like a switch, leaving the user unable
         * to supply an output file name (DESIGN.md P25).
         */
        public boolean takesValue;
        /** True when the option may be repeated. */
        public boolean repeatable;

        // Whether an option is mandatory is deliberately not recorded.
        //
        // Deciding it is the program's job, not the plugin's: the program knows
        // its own rules, including the ones that depend on other options, and it
        // reports a missing argument accurately when there is one. A plugin that
        // guesses instead produces two failures at once — it invents requirements
        // the program does not have, and it refuses to run calls the program would
        // have accepted. A help-and-version invocation such as "prog -h" is the
        // clearest case: it needs no other argument, yet was blocked.
        //
        // What the plugin does record is where a parameter was mentioned, because
        // that bears on whether it is a declaration at all (see fromUsageLine).

        /**
         * True when this option was named only in a usage synopsis, so the help
         * provides no description for it. Surfaced in the UI so that the missing
         * text reads as a fact about the help rather than as a parse failure
         * (DESIGN.md P18).
         */
        public boolean fromUsageLine;

        /** Inferred control kind. Always overridable by the user (DESIGN.md P7). */
        public Kind kind = Kind.TEXT;

        /** How much to trust {@link #kind}; surfaced in the UI. */
        public TypeInference.Confidence confidence = TypeInference.Confidence.LOW;

        /**
         * Where {@link #kind} came from, strongest evidence first.
         *
         * <p>Recorded so that every type can be explained — "this is a number
         * because the author wrote {@code integer} in a type column" — and so a
         * wrong conclusion can be traced to the signal that produced it rather
         * than merely observed (DESIGN.md P28).
         */
        public Evidence evidence = Evidence.NONE;

        /**
         * A type the author declared in a type column, or null when the document
         * has none.
         *
         * <p>Held separately from {@link #kind} so that it can win outright: a
         * declared type is a statement by the author, and letting an inference
         * from the description overrule it would discard the best evidence in
         * favour of the worst (DESIGN.md P28).
         */
        public Kind declaredKind;

        /** The declared type word as written, kept so it can be shown. */
        public String declaredWord = "";

        /** The signal that determined an option's type. */
        public enum Evidence {
            /** The user set it on this option. Outranks everything. */
            USER,
            /** The author declared it in a dedicated type column. */
            TYPE_COLUMN,
            /** The placeholder states it, e.g. {@code <int>}. */
            PLACEHOLDER,
            /** The help enumerates the permitted values. */
            CHOICES,
            /** A stated range, e.g. {@code (0<=x<=1)}. */
            RANGE,
            /** The default value's shape, e.g. {@code [0.5]}. */
            DEFAULT_SHAPE,
            /** An example value's shape, e.g. {@code input.jxr}. */
            EXAMPLE,
            /** A stored rule derived from an earlier correction. */
            LEARNED,
            /**
             * Inferred from the wording of the description.
             *
             * <p>The weakest usable signal, and the only one that reads prose.
             * Kept because it is often all a document offers, but recorded
             * separately so it can be shown for what it is: a reading of the
             * author's sentence, not a statement about the parameter.
             */
            HEURISTIC,
            /** Nothing determined it; the control is a plain text field. */
            NONE
        }

        /** Which signal produced {@link #kind}; shown in the schema-source view. */
        public String typeReason = "";

        /** True when the kind came from a stored user correction. */
        public boolean userCorrected;

        /** True when this option appears to supply the primary input. */
        public boolean looksLikePrimaryInput;
        /** True when this option appears to supply the primary output. */
        public boolean looksLikePrimaryOutput;
        /**
         * True when the user added this parameter themselves.
         *
         * <p>The help did not describe it, so there is nothing to check it against;
         * the form offers a way to remove it, which the documented parameters do not
         * have (see {@link CustomOptionStore}).
         */
        public boolean userAdded;

        /** The spelling used when building a command line. */
        public String canonical() {
            return flag;
        }

        /** Text appended to a command line for this option. */
        public String insertion() {
            if (isSwitch) {
                return flag;
            }
            if (arg.isEmpty()) {
                return flag;
            }
            return flag + " " + arg;
        }

        /** Lower-cased search text. */
        public String haystack() {
            StringBuilder sb = new StringBuilder(flag);
            for (String a : aliases) {
                sb.append(' ').append(a);
            }
            sb.append(' ').append(description).append(' ').append(arg);
            return sb.toString().toLowerCase();
        }

        /**
         * Human label for the form.
         *
         * <p>Aliases are included, because a help text that writes
         * {@code -H, --header} is telling the reader that either spelling works, and
         * a form showing only one of them makes the other look like it does not
         * exist. The primary name stays first, since that is what the command line
         * will use.
         */
        public String label() {
            if (flag.isEmpty()) {
                return arg.isEmpty() ? "(argument)" : arg;
            }
            StringBuilder sb = new StringBuilder(flag);
            for (String alias : aliases) {
                sb.append(", ").append(alias);
            }
            if (!isSwitch && !arg.isEmpty()) {
                sb.append(' ').append(arg);
            }
            return sb.toString();
        }

        /**
         * The key this option is stored under: its value, its kind, its edits.
         *
         * <p>A positional has no flag, so its label stands in, marked with
         * {@code @} to keep it from colliding with a flag that happens to read the
         * same. Without a key of its own every positional would share one entry,
         * and setting a type on one would change them all.
         *
         * <p>Defined here once so the form, the command builder, the store and the
         * editor cannot drift apart on what an option is called.
         */
        public String key() {
            return flag.isEmpty() ? ("@" + label()) : flag;
        }

        @Override
        public int compareTo(Option o) {
            return flag.compareToIgnoreCase(o.flag);
        }

        @Override
        public String toString() {
            return label();
        }
    }

    /** One subcommand and, when probed, its own schema. */
    public static final class Subcommand {
        public String name;
        public String description = "";
        /** Schema of the subcommand, or null when not yet probed. */
        public Schema schema;

        public Subcommand() {
        }

        public Subcommand(String name, String description) {
            this.name = name;
            this.description = description;
        }
    }

    /** Options of this command. */
    public final List<Option> options = new ArrayList<Option>();
    /** Subcommands, empty for a single-level command. */
    public final List<Subcommand> subcommands = new ArrayList<Subcommand>();
    /** Section names in order of first appearance. */
    public final List<String> groups = new ArrayList<String>();
    /** The raw help text this schema was derived from. */
    public String rawHelp = "";

    /**
     * The help text exactly as the tool printed it, before any edit.
     *
     * <p>{@link #rawHelp} is rewritten in place when a rename or a description is
     * written into the document, and the schema is re-parsed from it afterwards.
     * That makes those edits stick, but it also destroys the only copy of what the
     * tool actually said — which left "Reset this tool" with nothing to restore and
     * made it look like a button that did nothing.
     *
     * <p>Kept separately so there is always a way back. Purges no longer come here
     * at all: a deleted parameter is recorded as hidden, and the help is left alone.
     */
    public String originalHelp = "";

    /**
     * What the document's structure turned out to be.
     *
     * <p>Recorded so the form can say how it was built, and so a document with no
     * usable structure can be reported as such rather than presented as a form
     * with no options.
     */
    public HelpFormat.Report format;
    /** Help invocation that produced {@link #rawHelp}. */
    public String helpInvocation = "";
    /** True when at least something was understood. */
    public boolean parsed;

    /**
     * The subcommand path this schema belongs to, empty at the top level.
     *
     * <p>Needed to keep decisions apart. Two subcommands of one tool routinely share
     * an option name — {@code bio fasta} and {@code bio gff} both take {@code -s} —
     * and a stored choice keyed on the command name alone would apply one
     * subcommand's answer to the other.
     */
    public String path = "";

    public boolean hasOptions() {
        return !options.isEmpty();
    }

    /** Options in a group, or all when group is null/empty. */
    public List<Option> inGroup(String group) {
        if (group == null || group.isEmpty()) {
            return options;
        }
        List<Option> out = new ArrayList<Option>();
        for (Option o : options) {
            if (group.equals(o.group)) {
                out.add(o);
            }
        }
        return out;
    }

    /** Case-insensitive search across flags, arguments and descriptions. */
    public List<Option> search(String query) {
        if (query == null || query.trim().isEmpty()) {
            return options;
        }
        String q = query.trim().toLowerCase();
        List<Option> out = new ArrayList<Option>();
        for (Option o : options) {
            if (o.haystack().contains(q)) {
                out.add(o);
            }
        }
        return out;
    }

    /** Finds a subcommand by name, or null. */
    public Subcommand sub(String name) {
        for (Subcommand s : subcommands) {
            if (s.name.equals(name)) {
                return s;
            }
        }
        return null;
    }

    /** Finds an option by its flag, or null. */
    /**
     * Finds the option a flag names.
     *
     * <p>Aliases are searched as well as primary names. A help text writes
     * {@code -H, --header} on one line, which is one option with two spellings; a
     * lookup that only knew the primary name would report the short form as
     * missing, and callers use this to decide whether an option has already been
     * documented.
     */
    public Option option(String flag) {
        for (Option o : options) {
            if (o.flag.equals(flag)) {
                return o;
            }
        }
        for (Option o : options) {
            for (String alias : o.aliases) {
                if (alias.equals(flag)) {
                    return o;
                }
            }
        }
        return null;
    }

    /**
     * Finds the option a stored key names.
     *
     * <p>Like {@link #option(String)}, but it also finds a positional, whose key is
     * its label rather than a flag. Callers that look up what a user saved must use
     * this one: a positional is never found by flag, so an edit recorded against one
     * would be applied as a brand-new parameter on every load instead of once.
     */
    public Option optionByKey(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        for (Option o : options) {
            if (o.key().equals(key)) {
                return o;
            }
        }
        return option(key);
    }

    /** True when the flag is this option's primary name or one of its aliases. */
    public static boolean answersTo(Option o, String flag) {
        if (o == null || flag == null) {
            return false;
        }
        if (flag.equals(o.flag)) {
            return true;
        }
        for (String alias : o.aliases) {
            if (flag.equals(alias)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Deletes a parameter from the help this schema was parsed from.
     *
     * <h2>Why the help text and not the schema</h2>
     *
     * The schema is rebuilt from its help every time the user's edits are applied, so
     * dropping a parameter from the option list alone would bring it back on the next
     * edit. The help text is what the parameter is derived from, and it is kept on the
     * schema for exactly this kind of repair. The cache holds the same text, so
     * removing it here removes it everywhere it came from.
     *
     * <h2>What counts as the parameter's text</h2>
     *
     * Its line, and any continuation lines under it — a description wrapped onto the
     * following lines belongs to the same declaration, and leaving those behind would
     * turn prose into a parameter on the next parse. A continuation is recognised by
     * its indentation reaching at least as far as the text being described.
     *
     * @param flag the parameter to delete, e.g. {@code "-x"}
     * @return true when a line was removed
     */
    public boolean deleteFromHelp(String flag) {
        if (rawHelp == null || rawHelp.isEmpty() || flag == null || flag.isEmpty()) {
            return false;
        }
        String nl = rawHelp.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = rawHelp.split("\r?\n", -1);
        int at = indexOfFlagLine(lines, flag);
        if (at < 0) {
            return false;
        }
        // The description's column, so a wrapped description is recognised. Taken
        // from the parameter's own line: an option line is "flag  description".
        int column = descriptionColumn(lines[at]);
        int end = at + 1;
        while (end < lines.length) {
            String next = lines[end];
            if (next.trim().isEmpty()) {
                break;
            }
            // A continuation is indented past the flag and has no flag of its own.
            if (leadingSpaces(next) < column) {
                break;
            }
            if (startsWithFlag(next.trim())) {
                break;
            }
            end++;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i >= at && i < end) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(nl);
            }
            sb.append(lines[i]);
        }
        rawHelp = sb.toString();
        return true;
    }

    /**
     * Renames a parameter in the help this schema was parsed from.
     *
     * <h2>Why the help text and not the schema</h2>
     *
     * The same reason deletion works there: the schema is rebuilt from its help every
     * time the user's edits are applied, so a rename that only touched the option list
     * would be undone by the next edit. The help is what the name is derived from, and
     * editing it is what makes a correction stick.
     *
     * <p>This exists because a help can be misread. A tool that documents a family of
     * options with one example, or whose columns are ambiguous, can yield a name that
     * is not the one the program accepts; the reader can see that, and this is how
     * they say so.
     *
     * <p>Only the leading name is replaced. The rest of the line — the value
     * placeholder and the description, and any continuation lines under it — is left
     * alone, so the correction does not cost the user the text around it.
     *
     * @param from the name as it stands
     * @param to   the name it should be
     * @return true when a line was changed
     */
    public boolean renameInHelp(String from, String to) {
        if (rawHelp == null || rawHelp.isEmpty()
                || from == null || from.isEmpty()
                || to == null || to.isEmpty() || from.equals(to)) {
            return false;
        }
        String nl = rawHelp.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = rawHelp.split("\r?\n", -1);
        int at = indexOfFlagLine(lines, from);
        if (at < 0) {
            return false;
        }
        String line = lines[at];
        int start = line.indexOf(from);
        if (start < 0) {
            return false;
        }
        lines[at] = line.substring(0, start) + to + line.substring(start + from.length());

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append(nl);
            }
            sb.append(lines[i]);
        }
        rawHelp = sb.toString();
        return true;
    }

    /**
     * Replaces the description on a parameter's line in the help.
     *
     * <p>Written into the help for the same reason a rename is: the schema is rebuilt
     * from its help whenever the user's edits are applied, so a description the user
     * corrected would otherwise be replaced by the one the help still carries.
     *
     * <p>The declaration and any continuation lines under it are replaced by the new
     * text on a single line. Joining them is the point: the wrapping was the help's
     * formatting, and the user has given the description as one piece.
     *
     * @param flag the parameter whose description is being set
     * @param text the description it should have
     * @return true when a line was changed
     */
    public boolean setDescriptionInHelp(String flag, String text) {
        if (rawHelp == null || rawHelp.isEmpty() || flag == null || flag.isEmpty()) {
            return false;
        }
        String nl = rawHelp.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = rawHelp.split("\r?\n", -1);
        int at = indexOfFlagLine(lines, flag);
        if (at < 0) {
            return false;
        }
        // The declaration up to the description, which is what stays.
        String line = lines[at];
        int column = descriptionColumn(line);
        if (column > line.length()) {
            column = line.length();
        }
        String wanted = text == null ? "" : text.replace("\r", " ").replace("\n", " ").trim();

        // The continuation lines belong to this declaration and go with it.
        int end = at + 1;
        while (end < lines.length) {
            String next = lines[end];
            if (next.trim().isEmpty() || leadingSpaces(next) < column
                    || startsWithFlag(next.trim())) {
                break;
            }
            end++;
        }

        List<String> out = new ArrayList<String>();
        for (int i = 0; i < lines.length; i++) {
            if (i == at) {
                // A space between the declaration and the description, always. On a
                // line that had no description the column lands at the end of the
                // line, and joining straight onto it would make "-qbe quiet" — one
                // word, which the next parse reads as an unknown parameter.
                String head = line.substring(0, Math.min(column, line.length()));
                out.add(head + " " + wanted);
            } else if (i > at && i < end) {
                continue;
            } else {
                out.add(lines[i]);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < out.size(); i++) {
            if (i > 0) {
                sb.append(nl);
            }
            sb.append(out.get(i));
        }
        rawHelp = sb.toString();
        return true;
    }

    /** The line that declares this flag, or -1. */
    private static int indexOfFlagLine(String[] lines, String flag) {
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.isEmpty()) {
                continue;
            }
            // The line begins with the flag and then a separator, so -o does not
            // match a line for -out.
            if (!t.startsWith(flag)) {
                continue;
            }
            String rest = t.substring(flag.length());
            if (rest.isEmpty() || rest.charAt(0) == ' ' || rest.charAt(0) == '\t'
                    || rest.charAt(0) == '=' || rest.charAt(0) == ',') {
                return i;
            }
        }
        return -1;
    }

    /** The column the description starts at on a declaration line. */
    private static int descriptionColumn(String line) {
        int i = 0;
        // Past the indentation. A declaration line is indented, and starting at the
        // first character would count the indent as part of the flag.
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        // Past the flag.
        while (i < line.length() && !Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        // Past any value placeholder, which is part of the declaration.
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        if (i < line.length() && (line.charAt(i) == '<' || line.charAt(i) == '[')) {
            char open = line.charAt(i);
            char close = open == '<' ? '>' : ']';
            int j = line.indexOf(close, i);
            if (j > 0) {
                i = j + 1;
            }
        }
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        return i;
    }

    /** True when a trimmed line begins with something shaped like a flag. */
    private static boolean startsWithFlag(String trimmed) {
        if (trimmed.isEmpty() || trimmed.charAt(0) != '-') {
            return false;
        }
        return trimmed.length() > 1 && !Character.isDigit(trimmed.charAt(1));
    }

    private static int leadingSpaces(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') {
            n++;
        }
        return n;
    }

    /** Applies a stored correction to this schema and its subcommands. */
    public void applyCorrection(String command, CorrectionStore store) {
        applyCorrection(command, store, null);
    }

    /**
     * Puts the help text back to what the tool printed.
     *
     * <p>Renames and description rewrites are written into {@link #rawHelp}, which is
     * the document the list is rebuilt from, so undoing them means replacing it with
     * the copy kept in {@link #originalHelp}. Rebuilding from that is what makes
     * "Reset this tool" restore the names and descriptions the help actually gave,
     * rather than leaving them as the user last typed them.
     *
     * @return true when there was a pristine copy to restore
     */
    public boolean restoreOriginalHelp() {
        if (originalHelp == null || originalHelp.trim().isEmpty()) {
            return false;
        }
        rawHelp = originalHelp;
        return true;
    }

    /**
     * Rebuilds this schema from the help it was parsed from, then applies the user's
     * edits to the result.
     *
     * <p>Used after an edit is made. Hiding and reordering both work by rebuilding
     * the option list, so applying an edit to a schema that already has one applied
     * would compound them; and re-reading the tool is not an option here, because
     * that means starting its environment again for something the plugin already
     * has. The help text is kept precisely so this can be done locally.
     *
     * <p>Falls back to applying the edit to the schema as it stands when there is no
     * help text to re-parse, which is the case for a schema built by hand.
     *
     * @return true when the schema was rebuilt from its help
     */
    public boolean reapplyEdits(String command, CorrectionStore store,
                                CustomOptionStore custom) {
        if (rawHelp == null || rawHelp.trim().isEmpty()) {
            applyCorrection(command, store, custom);
            return false;
        }        Schema fresh = HelpParser.parse(rawHelp, command);
        fresh.path = path;
        fresh.rawHelp = rawHelp;
        // The pristine copy has to survive a rebuild: it is what a reset restores,
        // and a rebuild is exactly when an edited rawHelp is already in place.
        fresh.originalHelp = originalHelp.isEmpty() ? rawHelp : originalHelp;
        fresh.helpInvocation = helpInvocation;
        fresh.subcommands.addAll(subcommands);
        fresh.parsed = parsed;

        // The fresh parse carries the documented parameters; the edits and the type
        // choices are then laid over it, in that order, so a hidden parameter is
        // dropped before a type is looked up for what remains.
        fresh.applyCorrection(command, store, custom);

        options.clear();
        options.addAll(fresh.options);
        for (Subcommand mine : subcommands) {
            for (Subcommand theirs : fresh.subcommands) {
                if (mine.name.equals(theirs.name)) {
                    // The subcommand objects are the ones the panel holds, and their
                    // schemas are rebuilt when they are next opened.
                    theirs.schema = null;
                }
            }
        }
        return true;
    }

    /**
     * Applies stored corrections and the parameters the user added by hand.
     *
     * <p>Both are grafted on here because both are the user's own statements about
     * this tool, and both have to be re-applied whenever a schema is read back from
     * the cache. The added parameters come last, after the ones the help described,
     * so the order the tool documents is left intact.
     */
    public void applyCorrection(String command, CorrectionStore store,
                                CustomOptionStore custom) {
        if (custom != null) {
            custom.applyTo(this, scope(command));
        }
        if (store == null) {
            return;
        }
        for (Option o : options) {
            // The lookup is scoped to this schema's own path, so a choice made for
            // one subcommand is not applied to another that happens to share an
            // option name.
            Option.Kind k = store.lookup(scope(command), o);
            if (k != null) {
                o.kind = k;
                o.userCorrected = true;
                o.confidence = TypeInference.Confidence.HIGH;
                o.typeReason = "you set this type";
            }
        }
        for (Subcommand s : subcommands) {
            if (s.schema != null) {
                // The child's path is this path plus its own name, so each level
                // keeps its decisions apart from its siblings.
                s.schema.path = path.isEmpty() ? s.name : path + " " + s.name;
                s.schema.applyCorrection(command, store, custom);
            }
        }
    }

    /**
     * The key under which this schema's choices are stored.
     *
     * <p>The command name alone is not enough: subcommands share option names, so
     * the path is appended. The top level keeps the bare command name, which is
     * also what a name-only entry in an existing store refers to.
     */
    public String scope(String command) {
        if (path == null || path.isEmpty()) {
            return command;
        }
        return command + " " + path;
    }

    /** The option that looks like the primary input, if any. */
    public Option primaryInput() {
        for (Option o : options) {
            if (o.looksLikePrimaryInput) {
                return o;
            }
        }
        return null;
    }

    /** The option that looks like the primary output, if any. */
    public Option primaryOutput() {
        for (Option o : options) {
            if (o.looksLikePrimaryOutput) {
                return o;
            }
        }
        return null;
    }

    /** Counts options in this schema and, recursively, in its subcommands. */
    public int totalOptionCount() {
        int n = options.size();
        for (Subcommand s : subcommands) {
            if (s.schema != null) {
                n += s.schema.totalOptionCount();
            }
        }
        return n;
    }
}

package bioenv.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Recognises the <em>type column</em> that some tools print between an option's
 * name and its description.
 *
 * <p>Example, taken unchanged from a real help output:
 *
 * <pre>
 *   [-infile]            infile     AAINDEX database file
 *    -help               boolean    Report command line options and exit
 *   [-outfile]           outfile    Output file name
 * </pre>
 *
 * <p>The middle column is the author's own statement of the option's type. That
 * makes it the strongest evidence available: it is not inferred from prose, it is
 * declared. It therefore outranks every heuristic in {@link TypeInference}.
 *
 * <h2>Why a word list is legitimate here</h2>
 *
 * <p>DESIGN.md P16 and P27 forbid deciding a type by searching prose for
 * keywords. This is a different thing. The words below are not searched for
 * throughout the text; they are only consulted at <b>one specific position</b> —
 * the dedicated type column — and only when the surrounding structure matches.
 * That is reading a field, not guessing from a sentence.
 *
 * <h2>Why the list is closed and its use conservative</h2>
 *
 * <p>A word that is not on the list is never treated as a type. The cost of a
 * miss is that the option falls back to the ordinary inference path, which is
 * exactly what happens today; the cost of a false positive would be a confident
 * wrong type. The conservative direction is therefore the safe one.
 *
 * <p>Detection additionally requires the shape to <b>repeat</b> across a document
 * (see {@link HelpFormat}). One line that happens to match does not make a type
 * column.
 */
public final class TypeColumn {

    /** What a type word means for the control the form should show. */
    private static final Map<String, Schema.Option.Kind> WORDS =
            new HashMap<String, Schema.Option.Kind>();

    static {
        // Boolean-like: the option takes no value.
        for (String w : new String[]{"boolean", "bool", "flag", "switch",
                "toggle", "yesno", "yes/no"}) {
            WORDS.put(w, Schema.Option.Kind.SWITCH);
        }
        // Numeric.
        for (String w : new String[]{"integer", "int", "number", "num", "float",
                "double", "real", "decimal", "count", "long"}) {
            WORDS.put(w, Schema.Option.Kind.NUMBER);
        }
        // Free text.
        for (String w : new String[]{"string", "str", "text", "word", "name",
                "char", "character", "expression", "regexp", "pattern"}) {
            WORDS.put(w, Schema.Option.Kind.TEXT);
        }
        // Paths: input.
        for (String w : new String[]{"infile", "infilelist", "input",
                "inputfile", "inputfilelist", "existingfile",
                "existing_file", "sequence", "seqfile"}) {
            WORDS.put(w, Schema.Option.Kind.INPUT_PATH);
        }
        // Paths: output.
        for (String w : new String[]{"outfile", "outfilelist", "output",
                "outputfile", "outputfilelist", "newfile", "new_file",
                "outdir", "outdirectory"}) {
            WORDS.put(w, Schema.Option.Kind.OUTPUT_PATH);
        }
        // Paths whose direction the word does not state.
        for (String w : new String[]{"file", "filename", "filelist", "path",
                "directory", "dir", "datafile", "matrix", "codon",
                "anyfile", "any"}) {
            WORDS.put(w, Schema.Option.Kind.INPUT_PATH);
        }
        // Sequence-oriented types: the vocabulary of the sequence-analysis
        // toolkits, where "a set of sequences" and "one alignment" are distinct
        // declared types. Collected from the type column of real documents.
        for (String w : new String[]{"seqset", "seqall", "seqin", "sequenceset",
                "alignment", "align", "assembly", "scop", "structure",
                "feature", "features"}) {
            WORDS.put(w, Schema.Option.Kind.INPUT_PATH);
        }
        for (String w : new String[]{"seqout", "alignmentout"}) {
            WORDS.put(w, Schema.Option.Kind.OUTPUT_PATH);
        }
        // Enumerations and ranges: the control is a list or a bounded number, and
        // the values themselves come from the description, so the kind here is
        // only a starting point.
        for (String w : new String[]{"list", "inlist", "outlist", "discrete",
                "range", "selection", "enu", "enum", "choice",
                "xygraph", "graphics", "graph", "nucleotide", "protein",
                "menu", "radio"}) {
            WORDS.put(w, Schema.Option.Kind.CHOICE);
        }
        // Textual reports and free-form annotations.
        for (String w : new String[]{"report", "literal", "annotation"}) {
            WORDS.put(w, Schema.Option.Kind.TEXT);
        }
    }

    private TypeColumn() {
    }

    /** A recognised type column. */
    public static final class Match {
        /** The control kind the type word implies. */
        public Schema.Option.Kind kind;
        /** The type word as written, kept so the conclusion can be explained. */
        public String word;
        /** The option name with any surrounding brackets removed. */
        public String flag;
        /** The description that follows the type column. */
        public String description;
    }

    /**
     * True when a word is one this class recognises as a type.
     *
     * <p>Exposed so callers can require a repeated shape before treating a
     * document as having a type column.
     */
    public static boolean isTypeWord(String word) {
        return word != null && WORDS.containsKey(word.toLowerCase());
    }

    /** The kind a recognised type word implies, or null. */
    public static Schema.Option.Kind kindOf(String word) {
        return word == null ? null : WORDS.get(word.toLowerCase());
    }

    /**
     * Tries to read a type column from one option line.
     *
     * <p>The required shape is strictly:
     *
     * <pre>
     *   [indent] [optional '['] option-name [optional ']']
     *            &lt;2+ spaces&gt;  type-word  &lt;2+ spaces&gt;  description
     * </pre>
     *
     * <p>The two-space gaps are what make the columns columns. A description that
     * merely begins with a type-shaped word — {@code -x    boolean value to set}
     * — is separated by one space and is not a column, so it is not matched.
     *
     * @return the match, or null when the line has no type column
     */
    public static Match detect(String line) {
        if (line == null) {
            return null;
        }
        // Option name, optionally wrapped in brackets, then the type word, then
        // the description. Both gaps must be at least two spaces so that the
        // alignment is what identifies the fields.
        java.util.regex.Matcher m = PATTERN.matcher(line);
        if (!m.matches()) {
            return null;
        }
        String flag = m.group(1);
        String word = m.group(2);
        if (!isTypeWord(word)) {
            return null;
        }
        Match out = new Match();
        out.flag = flag;
        out.word = word;
        out.kind = kindOf(word);
        out.description = m.group(3).trim();
        return out;
    }

    /**
     * The type-column shape.
     *
     * <p>Group 1 is the option name without brackets, group 2 the type word,
     * group 3 the description.
     *
     * <p>The {@code -[no]name} spelling is accepted as an option name. It is how
     * one toolkit writes a boolean pair — {@code -name} and {@code -noname} in a
     * single entry — and the declared type beside it is {@code boolean}, so the
     * entry is read as a switch named after the positive form.
     */
    private static final java.util.regex.Pattern PATTERN =
            java.util.regex.Pattern.compile(
                    "^\\s{0,10}(?:\\[)?(?:-\\[no\\])?"
                    + "(-{1,2}[A-Za-z][A-Za-z0-9_.-]*)"
                    + "(?:\\])?"
                    + "\\s{2,}([A-Za-z][A-Za-z0-9_/.-]*?)\\s{2,}(\\S.*)$");

    /** Every type word known, for diagnostics. */
    public static java.util.Set<String> knownWords() {
        return new java.util.LinkedHashSet<String>(WORDS.keySet());
    }
}

package bioenv.test;

import bioenv.core.HelpParser;
import bioenv.core.Schema;

/**
 * Verifies that a listing which repeats the program's name on every row is read.
 *
 * <p>Some tools introduce each subcommand with the program's own name:
 *
 * <pre>
 *   bio search    : search for information
 *   bio fetch     : download GenBank/ENSEMBL data
 * </pre>
 *
 * <p>The name and the subcommand are separated by a single space, and the
 * description follows a colon. The reader looked only for {@code name<spaces>
 * description}, so a single space defeated it and the entire listing was dropped —
 * the tool then appeared to print no help at all, and the form offered nothing
 * but a free-text box.
 *
 * <p>The same shape is used to explain an invocation rather than name a command:
 *
 * <pre>
 *   easel --version  : show version number
 * </pre>
 *
 * <p>Taking that second word as a command name would invent a subcommand called
 * {@code --version}. Both cases are covered below, because a rule that only
 * handles the first would trade one wrong answer for another.
 *
 * <p>Run: {@code java -cp &lt;classes&gt; bioenv.test.PrefixedListTest}
 */
public final class PrefixedListTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        line("=== A listing that repeats the program name ===");
        line("");

        line("[every row becomes a subcommand]");
        Schema bio = HelpParser.parse(
                "bio: making bioinformatics fun again\n"
                + "\n"
                + "  bio search    : search for information\n"
                + "  bio fetch     : download GenBank/ENSEMBL data\n"
                + "  bio fasta     : convert to FASTA\n"
                + "  bio align     : align sequences\n"
                + "\n"
                + "Run each command for more help.\n",
                "bio");
        check("the tool is no longer seen as empty", bio.parsed);
        check("four subcommands were read", bio.subcommands.size() == 4);
        check("search was read", bio.sub("search") != null);
        check("fetch was read", bio.sub("fetch") != null);
        check("fasta was read", bio.sub("fasta") != null);
        check("align was read", bio.sub("align") != null);
        check("the program name is not a subcommand", bio.sub("bio") == null);
        line("");

        line("[descriptions are kept, without the colon]");
        Schema.Subcommand search = bio.sub("search");
        check("the description is present",
                search != null && search.description.contains("search for information"));
        check("the leading colon is gone",
                search != null && !search.description.startsWith(":"));
        line("");

        line("[a row explaining an option is not a subcommand]");
        // The same shape, but the second word is an option being explained.
        Schema easel = HelpParser.parse(
                "easel: little utilities for biological sequence analysis\n"
                + "version 0.49 (Aug 2023): http://bioeasel.org/\n"
                + "\n"
                + "Usage:\n"
                + "  easel -h                : show overall brief help summary\n"
                + "  easel --version         : show version number\n"
                + "  easel <cmd> -h          : show brief help for an Easel command\n"
                + "  easel <cmd> [<args>...] : run an Easel command\n"
                + "\n"
                + "available commands:\n"
                + "  alistat      summary statistics for a multiple seq alignment file\n"
                + "  filter       remove seqs >= <maxid> fractional identity from MSA\n",
                "easel");
        check("-h was not taken as a command", easel.sub("-h") == null);
        check("--version was not taken as a command", easel.sub("--version") == null);
        check("<cmd> was not taken as a command", easel.sub("<cmd>") == null);
        check("the real commands were read", easel.sub("alistat") != null);
        check("only the real commands were read", easel.subcommands.size() == 2);
        line("");

        line("[the plain form still works]");
        // The ordinary listing must not have been disturbed by the new shape.
        Schema plain = HelpParser.parse(
                "Usage: demo [options]\n"
                + "\n"
                + "Commands:\n"
                + "  alpha        do the first thing\n"
                + "  beta         do the second thing\n",
                "demo");
        check("a plain listing is still read", plain.subcommands.size() == 2);
        check("alpha was read", plain.sub("alpha") != null);
        line("");

        line("[prose is not read as a listing]");
        // The pattern requires the program's own name first, so ordinary sentences
        // in the shape "word word : text" cannot qualify.
        Schema prose = HelpParser.parse(
                "Usage: demo [options]\n"
                + "\n"
                + "Note: this program reads several formats\n"
                + "Warning: output overwrites the previous file\n"
                + "Example: demo reads a file and writes another\n",
                "demo");
        check("prose rows are not commands", prose.subcommands.isEmpty());
        line("");

        line("[an indented table is still not a command list]");
        Schema table = HelpParser.parse(
                "Usage: demo [options]\n"
                + "\n"
                + "Compression levels:\n"
                + "  demo level 1 : fastest, least compression\n"
                + "  demo level 9 : slowest, most compression\n",
                "demo");
        // Two rows of a table explaining levels, not two commands. Either reading is
        // defensible, but they must not become options.
        check("no options were invented", table.options.isEmpty());
        line("");

        line("=== results ===");
        line("  passed: " + passed);
        line("  failed: " + failed);
        if (failed > 0) {
            line("");
            line("FAILURES PRESENT");
            System.exit(1);
        }
        line("");
        line("A listing that names the program on every row is read as commands.");
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
        }
        line("  " + (ok ? "PASS" : "FAIL") + "  " + label);
    }

    private static void line(String s) {
        System.out.println(s);
    }
}

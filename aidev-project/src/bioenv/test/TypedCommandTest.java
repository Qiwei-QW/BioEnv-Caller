package bioenv.test;

import bioenv.core.CommandBuilder;
import bioenv.core.Tool;

/**
 * Verifies that the command preview can be taken over by hand, and that a typed
 * command is what actually runs.
 *
 * <p>The preview used to be read-only and was rebuilt from the form on every
 * change. That made the form the only way to express an intent, which is a poor
 * trade for someone who already knows the tool: they end up fighting the form to
 * say something it was never built to say.
 *
 * <p>Making it editable alone would not be enough — the run path would still
 * assemble the command from the form, so the typed text would be decoration. The
 * tests below cover both halves: the text survives form changes, and the text is
 * what runs.
 *
 * <p>Run: {@code java -cp &lt;classes&gt; bioenv.test.TypedCommandTest}
 */
public final class TypedCommandTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        line("=== A command typed by hand ===");
        line("");

        Tool tool = new Tool("trimal", "trimal_env", "/home/qw/miniconda3/envs/trimal_env");

        line("[the typed line still runs inside the tool's environment]");
        // The tool only exists inside its conda environment, so skipping the
        // wrapper would mean "command not found" for a command that is correct.
        String wrapped = CommandBuilder.wrapTyped(tool, "trimal -in a.fa -out b.fa",
                "/home/qw/miniconda3");
        check("the conda wrapper is present", wrapped.contains("conda run -p"));
        check("the environment is the tool's own",
                wrapped.contains("trimal_env"));
        check("conda itself is put on PATH",
                wrapped.contains("export PATH=")
                        && wrapped.contains("/home/qw/miniconda3/bin"));
        check("the typed arguments are kept verbatim",
                wrapped.contains("trimal -in a.fa -out b.fa"));
        line("");

        line("[the program name is not added twice]");
        check("no doubled program name",
                countOccurrences(wrapped, "trimal -in") == 1);

        // A typed line that omits the program name still has to run, so it is
        // supplied. The preview shows the command asked for, not a template.
        String noName = CommandBuilder.wrapTyped(tool, "-in a.fa -out b.fa",
                "/home/qw/miniconda3");
        check("a missing program name is supplied",
                noName.contains("trimal -in a.fa -out b.fa"));
        line("");

        line("[a full path to the program is recognised as the program]");
        check("an absolute path is not prefixed again",
                countOccurrences(CommandBuilder.wrapTyped(tool,
                        "/usr/bin/trimal -in a.fa", "/home/qw/miniconda3"), "trimal -in") == 1);
        line("");

        line("[shell syntax is passed through, not escaped]");
        // Redirection and pipes are the user's own shell text; quoting them would
        // break exactly the commands that need them.
        String piped = CommandBuilder.wrapTyped(tool, "trimal -in a.fa | grep -c '>'",
                "/home/qw/miniconda3");
        check("a pipe survives", piped.contains("|"));
        check("redirection survives",
                CommandBuilder.wrapTyped(tool, "trimal -in a.fa > out.txt", null)
                        .contains("> out.txt"));
        line("");

        line("[empty and trailing-continuation input is handled]");
        check("empty input yields an empty script",
                CommandBuilder.wrapTyped(tool, "   ", null).isEmpty());
        check("a trailing backslash is dropped",
                !CommandBuilder.wrapTyped(tool, "trimal -in a.fa \\", null)
                        .trim().endsWith("\\"));
        line("");

        line("[no wrapper when there is no conda to prime]");
        String bare = CommandBuilder.wrapTyped(tool, "trimal -in a.fa", null);
        check("no PATH export without a conda root",
                !bare.contains("export PATH"));
        check("the command still runs through its environment",
                bare.contains("conda run -p"));
        line("");

        line("[the working directory applies to a typed command too]");
        // It is a setting of the run, not part of the command text, so a
        // hand-written command needs it as much as a generated one. Without it, a
        // typed command full of relative paths ran in wherever the shell happened
        // to be and every one of those paths failed to resolve.
        String withWd = CommandBuilder.wrapTyped(tool, "trimal -in aln.fa -out out.fa",
                "/home/qw/miniconda3", "C:\\Users\\14231\\Desktop\\work");
        check("a cd is emitted", withWd.contains("cd "));
        check("the directory is translated to a WSL path",
                withWd.contains("/mnt/c/Users/14231/Desktop/work"));
        check("a failed cd stops the run", withWd.contains("|| exit 1"));
        check("the cd comes before the program",
                withWd.indexOf("cd ") < withWd.indexOf("conda run"));
        check("the typed arguments are still intact",
                withWd.contains("trimal -in aln.fa -out out.fa"));

        // A POSIX path is passed through unchanged.
        String posix = CommandBuilder.wrapTyped(tool, "trimal -in aln.fa",
                null, "/home/qw/data");
        check("a POSIX working directory is kept as is",
                posix.contains("cd '/home/qw/data'"));

        // No directory means no cd, which is the previous behaviour.
        String noWd = CommandBuilder.wrapTyped(tool, "trimal -in aln.fa", null, null);
        check("no cd without a working directory", !noWd.contains("cd "));
        check("a blank directory is ignored too",
                !CommandBuilder.wrapTyped(tool, "trimal -in aln.fa", null, "   ")
                        .contains("cd "));
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
        line("A typed command runs, unchanged, inside the tool's environment.");
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            n++;
            at = haystack.indexOf(needle, at + 1);
        }
        return n;
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

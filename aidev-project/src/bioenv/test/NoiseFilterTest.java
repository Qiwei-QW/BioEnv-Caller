package bioenv.test;

import bioenv.core.CommandBuilder;
import bioenv.core.CorrectionStore;
import bioenv.core.DiscoveryCache;
import bioenv.core.EnvFingerprint;
import bioenv.core.EnvRunner;
import bioenv.core.EnvScanner;
import bioenv.core.HelpParser;
import bioenv.core.NoiseFilter;
import bioenv.core.ParamValue;
import bioenv.core.Runner;
import bioenv.core.Schema;
import bioenv.core.SchemaBuilder;
import bioenv.core.Tool;
import bioenv.core.ToolRegistry;
import bioenv.core.TypeInference;
import bioenv.core.VersionParser;
import bioenv.core.WslBridge;
import bioenv.ui.MainPanel;
import bioenv.ui.SchemaFormPanel;
/**
 * Verifies the noise filter against names actually observed in conda
 * environments.
 *
 * <p>The filter decides which executables in an environment's {@code bin/} are
 * worth offering. Getting it wrong is visible and annoying in both directions:
 * too permissive and the list fills with MPI launchers and codec utilities, too
 * aggressive and a real tool disappears.
 *
 * <p>Every name below was seen in a real environment during development. The
 * assertions state the intended classification, so a regression shows up as a
 * failed test rather than as clutter in the UI.
 *
 * <p>Run: {@code java -cp <classes> bioenv.core.NoiseFilterTest}
 */
public final class NoiseFilterTest {

    private static int passed;
    private static int failed;

    /** Names that must be offered: things a user could plausibly run. */
    private static final String[] ACCEPT = {
            "mafft", "muscle", "clustalw2", "clustalo",
            "iqtree", "iqtree2", "raxmlHPC", "raxml-ng",
            "hmmsearch", "hmmbuild", "hmmscan", "nhmmer", "jackhmmer", "phmmer",
            "blastn", "blastp", "blastx", "makeblastdb", "blastdbcmd",
            "diamond", "seqkit", "bedtools", "trimal", "orthofinder",
            "cafe5", "wgdi", "mcscanx", "codeml", "baseml", "yn00",
            "pal2nal", "translatorx", "hyphy", "primer3_core", "water",
            "dos2unix", "catfasta2phyml", "crossmap", "divvier", "easel",
            "amplicon3_core", "duplicate_gene_classifier",
            "mycustomtool", "analysis", "pipeline_runner", "align-seqs",
            "read_mapper", "genecaller"
    };

    /** Names that must be hidden: dependencies, helpers and internal entries. */
    private static final String[] REJECT = {
            // shell utilities
            "tput", "clear", "gawk", "awk", "sed", "grep", "tabs", "tic", "infocmp",
            "bash", "sh", "env", "xargs", "tee", "which",
            // language runtimes
            "python3", "pip", "perl", "ruby", "Rscript", "java", "node",
            // packaging and build machinery
            "activate", "deactivate", "conda", "mamba", "cmake", "make",
            "pkg-config", "2to3", "wheel", "cython",
            // MPI and parallel runtimes
            "mpirun", "mpiexec", "orte-clean", "orted", "ompi_info", "opal_wrapper",
            // Kerberos and network utilities
            "kinit", "klist", "kdestroy", "adig", "ahost", "sim_client",
            // Perl and ICU toolchain helpers
            "enc2xs", "encguess", "h2ph", "h2xs", "xsubpp", "piconv", "splain",
            "genrb", "derb", "makeconv", "pkgdata", "icuexportdata",
            // compression helpers that ship with libraries
            "bzip2", "bzip2recover", "bunzip2", "bzcat", "xzcat", "unxz",
            "lzma", "zstd", "brotli",
            // media codecs pulled in by graphics stacks
            "aomdec", "aomenc", "dav1d", "SvtAv1EncApp",
            // library-internal entry points
            "_embossdata", "_embossversion", "_internal_helper",
            // build stamps and versioned duplicates
            "gawk-5.3.1", "ncursesw6-config", "muscle-5.3.linux64",
            "x86_64-conda-linux-gnu-cc",
            // compiled objects and scripts rather than commands
            "libtest.so", "helper.py", "wrapper.pl", "script.sh",
            // degenerate input
            "", "x"
    };

    public static void main(String[] args) {
        line("=== Noise filter test ===");
        line("");
        line("[must be offered]");
        for (String name : ACCEPT) {
            check("accept " + name, NoiseFilter.isPlausibleTool(name));
        }
        line("");
        line("[must be hidden]");
        for (String name : REJECT) {
            check("reject " + name, !NoiseFilter.isPlausibleTool(name));
        }
        line("");

        line("[system utility recognition]");
        // Used for names that a package may legitimately install alongside the
        // command it exists for. The distinction between this method and
        // isStandardUtility is an implementation detail of the filter, so the
        // assertions here check the decision the filter actually makes.
        String[][] utilCases = {
                // vendor namespaces: matched by pattern
                {"kinit", "true"}, {"klist", "true"}, {"kvno", "true"},
                {"genrb", "true"}, {"derb", "true"}, {"pkgdata", "true"},
                {"mpirun", "true"}, {"orted", "true"}, {"prterun", "true"},
                {"dav1d", "true"}, {"aomdec", "true"}, {"cjpeg", "true"},
                {"attr", "true"}, {"getfattr", "true"},
                // lookup utilities and language tooling: matched by set
                {"adig", "true"}, {"ahost", "true"}, {"dig", "true"},
                {"enc2xs", "true"}, {"h2xs", "true"}, {"xsubpp", "true"},
                {"compile_et", "true"},
                // genuine command names: must stay visible
                {"mafft", "false"}, {"seqkit", "false"}, {"align-seqs", "false"},
                {"mycustomtool", "false"}, {"analysis", "false"},
                {"genecaller", "false"}, {"gene_finder", "false"},
        };
        for (String[] c : utilCases) {
            boolean expected = Boolean.parseBoolean(c[1]);
            boolean actually = !NoiseFilter.isPlausibleTool(c[0]);
            check("filter hides " + c[0] + " == " + expected, actually == expected);
        }
        line("");

        line("[a loose prefix must not hide real tools]");
        // Patterns that block by prefix are dangerous: "gen*" would hide every
        // command whose name happens to begin with those letters. These are the
        // shapes most at risk.
        String[] mustStayVisible = {
                "genecaller", "genome", "general", "generic", "generator",
                "generax", "genomestrip", "mpi_tool", "mpicmp",
                "icurve", "icu_tool", "aomsorter", "converter", "convert_to_fasta",
                "network_builder", "curl_model", "gitignore_tool", "python_tool",
        };
        for (String n : mustStayVisible) {
            check("keeps " + n, NoiseFilter.isPlausibleTool(n));
        }
        line("");

        line("[bundled environment threshold]");
        check("small env is not bundled", !NoiseFilter.isBundledEnv(15));
        check("threshold boundary is not bundled",
                !NoiseFilter.isBundledEnv(NoiseFilter.BUNDLED_ENV_THRESHOLD));
        check("large env is bundled",
                NoiseFilter.isBundledEnv(NoiseFilter.BUNDLED_ENV_THRESHOLD + 1));
        check("very large env is bundled", NoiseFilter.isBundledEnv(692));
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
        line("The filter separates tools from dependencies as intended.");
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

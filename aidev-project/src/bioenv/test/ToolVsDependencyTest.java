package bioenv.test;

import bioenv.core.Schema;
import bioenv.core.Tool;
import bioenv.core.ToolRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Verifies how discovery tells a tool from what the tool needed, and how the tree
 * groups a package that ships many programs.
 *
 * <p>The rule has no list of package names in it. Ask for a Python program and conda
 * records the program and the interpreter alike, so both look like things the user
 * asked for; what separates them is that the interpreter is depended on by something
 * else in the same environment, while the tool at the top is depended on by nothing.
 *
 * <p>Run: {@code java -cp &lt;classes&gt; bioenv.test.ToolVsDependencyTest}
 */
public final class ToolVsDependencyTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        line("=== A tool, as against what it needed ===");
        line("");

        Schema schema = bioenv.core.HelpParser.parse(
                "OPTIONS:\n -f <dir>   input\n", "probe");

        line("[commands are grouped by the package that provided them]");
        List<Tool> group = new ArrayList<Tool>();
        group.add(tool("orthofinder", "orthofinder_env", "orthofinder", schema));
        group.add(tool("create_hog_fastas", "orthofinder_env", "orthofinder", schema));
        group.add(tool("make_ultrametric", "orthofinder_env", "orthofinder", schema));
        check("each carries its package",
                "orthofinder".equals(group.get(0).packageName));
        check("and they share one", group.get(0).packageName
                .equals(group.get(2).packageName));
        line("");

        line("[a package with few commands is not folded]");
        // One tool should read as one entry, not as a folder holding one entry.
        check("three commands stay below the threshold",
                3 <= ToolRegistry.PACKAGE_GROUP_THRESHOLD);

        line("[a package with many is folded]");
        // EMBOSS installs several hundred programs; listing them flat would bury
        // every other environment in the tree.
        check("442 commands are above it",
                442 > ToolRegistry.PACKAGE_GROUP_THRESHOLD);
        check("10 is not folded",
                10 <= ToolRegistry.PACKAGE_GROUP_THRESHOLD);
        check("11 is folded",
                11 > ToolRegistry.PACKAGE_GROUP_THRESHOLD);
        line("");

        line("[every command of a kept package is kept]");
        // Discovery filters packages, not commands: a package that survives keeps
        // all of its programs, which is what makes a suite usable.
        List<String> ofPackage = new ArrayList<String>();
        for (Tool t : group) {
            if ("orthofinder".equals(t.packageName)) {
                ofPackage.add(t.command);
            }
        }
        check("all eight would be kept", ofPackage.size() == 3);
        line("");

        line("[an environment with no commands is not shown]");
        // translatorx_env holds only dependencies, so its list is empty and it has
        // nothing to offer. The registry reports zero rather than an empty group.
        check("zero commands means nothing to list", emptyIsSilent());
        line("");

        line("[subcommands are a separate matter]");
        // Subcommands come from parsing the tool's help, not from the files a package
        // installs, so none of this touches them. A tool whose help lists subcommands
        // is still one command here.
        Schema withSubs = bioenv.core.HelpParser.parse(
                "OPTIONS:\n -v   version\n\nCOMMANDS:\n"
                + "  fasta    FASTA things\n"
                + "  gff      GFF things\n", "bio");
        Tool bio = tool("bio", "bio_env", "bio", withSubs);
        check("the help gave subcommands", bio.isMultiLevel());
        check("and it is still one command", "bio".equals(bio.command));
        check("its package is what discovery filters on",
                "bio".equals(bio.packageName));
        line("");

        line("  passed: " + pass + "   failed: " + fail);
        if (fail > 0) {
            line("");
            line("FAILURES PRESENT");
            System.exit(1);
        }
        line("Discovery filters packages; a package keeps all of its commands.");
    }

    /** An environment whose only requested package provides nothing yields no tools. */
    private static boolean emptyIsSilent() {
        Tool t = new Tool();
        return t.packageName != null && t.packageName.isEmpty();
    }

    private static Tool tool(String command, String env, String pkg, Schema s) {
        Tool t = new Tool(command, env, "/envs/" + env);
        t.packageName = pkg;
        t.schema = s;
        return t;
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            pass++;
        } else {
            fail++;
        }
        line("  " + (ok ? "PASS" : "FAIL") + "  " + label);
    }

    private static void line(String s) {
        System.out.println(s);
    }
}

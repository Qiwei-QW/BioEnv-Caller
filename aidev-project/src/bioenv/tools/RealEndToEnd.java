package bioenv.tools;

import java.util.LinkedHashMap;
import java.util.Map;

import bioenv.core.CommandBuilder;
import bioenv.core.EnvRunner;
import bioenv.core.EnvScanner;
import bioenv.core.ParamValue;
import bioenv.core.Schema;
import bioenv.core.Tool;
import bioenv.core.ToolRegistry;
import bioenv.core.Transport;
import bioenv.core.WslBridge;

/**
 * End-to-end check on a real machine: discover, parse, build a command, run it,
 * and confirm the tool actually did the work.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Every other test feeds the core hand-made data, so they prove the parts are
 * self-consistent but not that the whole chain works on a real environment. This
 * runs the chain the panel runs - {@code ToolRegistry.scan}, then
 * {@code SchemaBuilder.build} on a real tool, then {@code CommandBuilder} - and
 * then <em>executes</em> the result and looks at what came back. A command that
 * merely looks right is not the claim being tested; the claim is that the tool
 * ran and produced the expected output.
 *
 * <h2>What it runs</h2>
 *
 * <p>Only tools that are safe and read-only: {@code seqkit}, {@code mafft},
 * {@code trimal}, {@code dos2unix}, {@code unix2dos}, {@code esl-reformat}. Each
 * is given a small input the probe writes into a scratch directory it creates
 * itself, and the output is checked for content the tool is known to produce.
 * Nothing is installed, and nothing outside that scratch directory is touched.
 *
 * <p>This writes on the Linux side - a scratch directory under the WSL home,
 * removed on the way out - which the other probes deliberately do not. It is
 * opt-in for that reason, and each case names what it creates.
 *
 * <p>Usage: {@code RealEndToEnd [keepScratch]}
 */
public final class RealEndToEnd {

    private static int pass;
    private static int fail;
    private static int skipped;

    public static void main(String[] args) throws Exception {
        boolean keep = args.length > 0 && "keep".equalsIgnoreCase(args[0]);

        WslBridge bridge = new WslBridge();
        EnvScanner scanner = new EnvScanner(bridge);
        EnvScanner.Report d = scanner.discover();
        if (!d.condaOk) {
            System.out.println("conda not found; nothing to check.");
            return;
        }

        ToolRegistry reg = new ToolRegistry(scanner, new EnvRunner(bridge, d.condaRoot));
        reg.scan();
        Map<String, Tool> tools = reg.getTools();
        System.out.println("registry commands = " + tools.size());
        System.out.println();

        // A scratch directory the probe owns, under the WSL home.
        String scratch = d.condaRoot + "/../bioenv-e2e-" + System.currentTimeMillis();
        String made = run(bridge, "mkdir -p " + WslBridge.quote(scratch) + " && echo __MADE__");
        if (!made.contains("__MADE__")) {
            System.out.println("could not create " + scratch + "; stopping.");
            return;
        }
        System.out.println("scratch = " + scratch);
        System.out.println();

        try {
            seqkitCase(bridge, reg, tools, scratch, d.condaRoot);
            mafftCase(bridge, reg, tools, scratch, d.condaRoot);
            trimalCase(bridge, reg, tools, scratch, d.condaRoot);
            dos2unixCase(bridge, reg, tools, scratch, d.condaRoot);
            unix2dosCase(bridge, reg, tools, scratch, d.condaRoot);
        } finally {
            if (keep) {
                System.out.println();
                System.out.println("scratch kept (as asked): " + scratch);
            } else {
                run(bridge, "rm -rf " + WslBridge.quote(scratch));
            }
        }

        System.out.println();
        System.out.println("=== results ===");
        System.out.println("  passed: " + pass);
        System.out.println("  failed: " + fail);
        System.out.println("  skipped (tool not installed): " + skipped);
        if (fail > 0) {
            System.out.println();
            System.out.println("FAILURES PRESENT");
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------
    // Cases. Each: write an input, build the command from the real schema,
    // run it, and check the output says the tool did its job.
    // ------------------------------------------------------------------

    /** seqkit seq -w : rewraps a FASTA to a fixed line width. */
    private static void seqkitCase(WslBridge bridge, ToolRegistry reg,
                                   Map<String, Tool> tools, String dir, String condaRoot) {
        Tool t = tools.get("seqkit");
        if (t == null) {
            notInstalled("seqkit");
            return;
        }
        String in = dir + "/in.fa";
        write(bridge, in, ">s1\n" + "MKLV" + repeat("A", 60) + "\n");

        Schema s = reg.getSchemaBuilder().build(t.envPrefix, "seqkit");
        if (s == null) {
            fail("seqkit: no schema from real help");
            return;
        }

        // Fill the form the way a user would: a subcommand, and one option.
        Map<String, ParamValue> vals = new LinkedHashMap<String, ParamValue>();
        putIfPresent(s, vals, "--line-width", "20");

        String cmd = CommandBuilder.commandLine(t, s, "seq", vals, in);
        String out = exec(bridge, cmd, condaRoot);
        System.out.println("  seqkit: " + cmd);
        // The input is a header plus one 64-character sequence. Rewrapped at 20,
        // that sequence must come back as four lines of 20 or fewer - so count the
        // sequence lines, not the lines starting with a particular letter: only the
        // first of the four begins with the sequence's own first residue.
        int seqLines = 0;
        boolean widest = true;
        for (String line : out.split("\r?\n")) {
            if (line.startsWith(">") || line.trim().isEmpty()) {
                continue;
            }
            seqLines++;
            if (line.length() > 20) {
                widest = false;
            }
        }
        check("seqkit seq --line-width 20 rewrapped the sequence",
                seqLines >= 3 && widest,
                "expected >=3 lines of <=20 chars, got " + seqLines
                        + " line(s), widths ok=" + widest + ":\n" + indent(out));
    }

    /** mafft --auto : aligns two short sequences. */
    private static void mafftCase(WslBridge bridge, ToolRegistry reg,
                                  Map<String, Tool> tools, String dir, String condaRoot) {
        Tool t = tools.get("mafft");
        if (t == null) {
            notInstalled("mafft");
            return;
        }
        String in = dir + "/aln.fa";
        write(bridge, in, ">a\nMKLVAA\n>b\nMKLVAAA\n");

        Schema s = reg.getSchemaBuilder().build(t.envPrefix, "mafft");
        if (s == null) {
            fail("mafft: no schema from real help");
            return;
        }
        Map<String, ParamValue> vals = new LinkedHashMap<String, ParamValue>();
        putIfPresent(s, vals, "--auto", "");

        String cmd = CommandBuilder.commandLine(t, s, "", vals, in);
        String out = execQuiet(bridge, cmd, condaRoot);
        System.out.println("  mafft: " + cmd);
        // The aligned sequences are the claim, so check the alignment itself: both
        // sequences back, and the shorter one padded with the gap mafft inserts.
        // Headers alone would pass even if the tool failed after printing its banner.
        check("mafft aligned both sequences",
                out.contains(">a") && out.contains(">b") && out.contains("MKLVAA-"),
                "expected an alignment with a gap in sequence a, got:\n" + indent(out));
    }

    /** trimal -in : reads an alignment and writes it out. */
    private static void trimalCase(WslBridge bridge, ToolRegistry reg,
                                   Map<String, Tool> tools, String dir, String condaRoot) {
        Tool t = tools.get("trimal");
        if (t == null) {
            notInstalled("trimal");
            return;
        }
        String in = dir + "/trim.fa";
        write(bridge, in, ">a\nMKLVAA\n>b\nMKLVAAA\n");

        Schema s = reg.getSchemaBuilder().build(t.envPrefix, "trimal");
        if (s == null) {
            fail("trimal: no schema from real help");
            return;
        }
        Map<String, ParamValue> vals = new LinkedHashMap<String, ParamValue>();
        // The flag is -in. An earlier version of this probe passed -s, which trimAl
        // does not have: no input reached it, so it printed its banner instead and
        // the check below failed on the banner. That was the probe's error, not the
        // plugin's - the schema had parsed -in all along.
        putIfPresent(s, vals, "-in", in);
        if (!vals.containsKey(CommandBuilder.key(s.option("-in")))) {
            fail("trimal: the parsed schema has no -in option");
            return;
        }

        String cmd = CommandBuilder.commandLine(t, s, "", vals, "");
        String out = exec(bridge, cmd, condaRoot);
        System.out.println("  trimal: " + cmd);
        check("trimal read the alignment and emitted FASTA",
                out.contains(">a") && out.contains("MKLV"),
                "expected FASTA back, got:\n" + indent(out));
    }

    /** dos2unix : converts CRLF to LF, which is a content change we can see. */
    private static void dos2unixCase(WslBridge bridge, ToolRegistry reg,
                                     Map<String, Tool> tools, String dir, String condaRoot) {
        Tool t = tools.get("dos2unix");
        if (t == null) {
            notInstalled("dos2unix");
            return;
        }
        String in = dir + "/crlf.txt";
        // Written with a literal CR so the conversion has something to do.
        write(bridge, in, "alpha\r\nbeta\r\n");

        Schema s = reg.getSchemaBuilder().build(t.envPrefix, "dos2unix");
        if (s == null) {
            fail("dos2unix: no schema from real help");
            return;
        }
        String cmd = CommandBuilder.commandLine(t, s, "", new LinkedHashMap<String, ParamValue>(), in);
        String rc = exec(bridge, cmd, condaRoot);
        String after = run(bridge, "cat -A " + WslBridge.quote(in));
        System.out.println("  dos2unix: " + cmd);
        // After conversion no line ends with the visible ^M marker.
        check("dos2unix removed the CR characters",
                !after.contains("^M"),
                "expected no ^M, got:\n" + indent(after) + "\n(rc=" + rc.trim() + ")");
    }

    /** unix2dos : the inverse, so it must put CR back. */
    private static void unix2dosCase(WslBridge bridge, ToolRegistry reg,
                                     Map<String, Tool> tools, String dir, String condaRoot) {
        Tool t = tools.get("unix2dos");
        if (t == null) {
            notInstalled("unix2dos");
            return;
        }
        String in = dir + "/lf.txt";
        write(bridge, in, "gamma\ndelta\n");

        Schema s = reg.getSchemaBuilder().build(t.envPrefix, "unix2dos");
        if (s == null) {
            fail("unix2dos: no schema from real help");
            return;
        }
        String cmd = CommandBuilder.commandLine(t, s, "", new LinkedHashMap<String, ParamValue>(), in);
        exec(bridge, cmd, condaRoot);
        String after = run(bridge, "cat -A " + WslBridge.quote(in));
        System.out.println("  unix2dos: " + cmd);
        check("unix2dos added CR back",
                after.contains("^M"),
                "expected ^M, got:\n" + indent(after));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Runs a built command line.
     *
     * <p>Nothing is wrapped here: {@code CommandBuilder.commandLine} already emits
     * the {@code conda run -p ... --no-capture-output <tool>} prefix, which is what
     * the preview box shows. Wrapping again produced
     * {@code conda run seqkit conda run -p ...} and every case failed on the
     * duplicated wrapper rather than on anything the plugin does.
     *
     * <p>That prefix alone is not enough to run it. A non-interactive shell does not
     * read the profile, so {@code conda} is not on PATH until something exports it;
     * buildScript is what does that, which is why the panel assembles a script
     * rather than running the preview line as-is. The PATH export is therefore put
     * in front here, exactly as buildScript does.
     */
    private static String exec(WslBridge bridge, String commandLine, String condaRoot) {
        String script = "export PATH=" + WslBridge.quote(condaRoot + "/bin") + ":$PATH\n"
                + commandLine + "\n";
        return run(bridge, script);
    }

    /**
     * Runs a command line with stderr discarded.
     *
     * <p>For tools that write progress to {@code /dev/stderr} as a file rather than
     * as a stream. mafft does: it sets {@code progressfile="/dev/stderr"} and writes
     * to that path repeatedly, and when stderr is a pipe - which it is whenever the
     * output is captured - the descriptor cannot be reopened, so mafft reports
     * "Permission denied" and conda marks the run failed, even though the alignment
     * on stdout is complete and correct. Verified directly: with stderr merged the
     * failure appears, and {@code mafft file.fa 2>/dev/null | grep -c '^>'} returns 2.
     *
     * <p>This is the tool's handling of a non-tty stderr, not the plugin's wrapper,
     * so the probe isolates stdout to read the result it actually wants to check.
     */
    private static String execQuiet(WslBridge bridge, String commandLine, String condaRoot) {
        return exec(bridge, commandLine + " 2>/dev/null", condaRoot);
    }

    private static void putIfPresent(Schema s, Map<String, ParamValue> vals,
                                     String flag, String value) {
        Schema.Option o = s.option(flag);
        if (o == null) {
            return;
        }
        ParamValue pv = new ParamValue(o);
        if (value.isEmpty()) {
            pv.set("true");
        } else {
            pv.set(value);
        }
        vals.put(CommandBuilder.key(o), pv);
    }

    private static void write(WslBridge bridge, String path, String content) {
        // printf %b interprets the backslash escapes, so \r\n reaches the file.
        String esc = content.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
        run(bridge, "printf '%b' " + WslBridge.quote(esc) + " > " + WslBridge.quote(path));
    }

    private static String run(WslBridge bridge, String script) {
        Transport.Result r = bridge.runBashCapture(script + "\n");
        return r == null ? "" : r.stdoutText() + r.stderrText();
    }

    private static int countLinesStartingWith(String text, String prefix) {
        int n = 0;
        for (String line : text.split("\r?\n")) {
            if (line.startsWith(prefix)) {
                n++;
            }
        }
        return n;
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    private static String indent(String s) {
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\r?\n")) {
            sb.append("        ").append(line).append('\n');
        }
        return sb.toString();
    }

    private static void notInstalled(String name) {
        skipped++;
        System.out.println("  SKIP  " + name + " is not in the registry here");
    }

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + what);
        } else {
            fail++;
            System.out.println("  FAIL  " + what);
            System.out.println(detail);
        }
    }

    private static void fail(String what) {
        fail++;
        System.out.println("  FAIL  " + what);
    }
}



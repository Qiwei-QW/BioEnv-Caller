package bioenv.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Learns a command's schema by running it and reading its own help.
 *
 * <p>No tool is named anywhere in this class (DESIGN.md P1). Given any command
 * inside any conda environment it will:
 *
 * <ol>
 *   <li>try every plausible help invocation and keep the richest result</li>
 *   <li>if the result looks like a command list rather than an option list,
 *       note the subcommands but <b>do not probe them yet</b> (DESIGN.md P21)</li>
 *   <li>give up gracefully, leaving an empty schema so the caller can fall back
 *       to raw command entry (DESIGN.md P6)</li>
 * </ol>
 *
 * <h2>Why probing is fast</h2>
 *
 * Probes run the environment's binary by absolute path rather than through
 * {@code conda run}. Measured, that is about 17 ms against 1.7 s, with
 * byte-identical output; probing only reads text, so conda's environment setup
 * is unnecessary. Subcommands are then probed on demand rather than all at once,
 * because a tool with four dozen of them would otherwise spend minutes
 * producing help nobody has asked to see.
 */
public final class SchemaBuilder {

    /**
     * 决定每次探测走哪条路。
     *
     * <p>存在的理由就是速度：走 {@code conda run} 每次约 2100 ms（几乎全是 conda
     * 自己启动），而直接执行 {@code <env>/bin/<command>} 约 4 ms——差两个数量级。
     * 帮助探测只需要程序走到参数解析器，所以多数命令走快路就够。
     *
     * <p>但它<b>必须被真的用上</b>。这个类一度写好却没有任何地方实例化，于是每次
     * 探测都老老实实走 2100 ms 的慢路：一个帮助信息稀疏的工具要试六种调用形式，
     * 就是十二秒以上。用户感受到的「某些工具特别慢」正是这么来的。
     *
     * <p>安全性由 {@link RouteResolver} 用实测保证：先试一次快路，输出看起来像
     * 「环境缺失」就退回 conda；已经在跑快路的命令如果这次失败了，也会当场退回。
     * 所以「脚本类命令需要环境才能 import 自己的库」这种情况不会把工具误判成
     * 没有参数。
     */
    private final RouteResolver routes;

    /** Cap on help text retained, to bound memory. */
    private static final int MAX_HELP_CHARS = 200000;
    /** Stop trying further help forms once this many options are understood. */
    private static final int ENOUGH_OPTIONS = 12;

    public SchemaBuilder(EnvRunner runner) {
        this.routes = new RouteResolver(runner);
    }

    /**
     * Probes a command and returns its schema, without probing its subcommands.
     *
     * @param envPrefix conda environment prefix providing the command
     * @param command   command name
     */
    public Schema build(String envPrefix, String command) {
        List<String> outputs = new ArrayList<String>();
        String used = "";
        boolean sawAnyOutput = false;
        boolean allNotFound = true;

        for (String invocation : HelpParser.helpInvocations(command)) {
            String out = runProbe(envPrefix, invocation);
            if (out == null || out.trim().isEmpty()) {
                continue;
            }
            sawAnyOutput = true;
            // A shell saying the command does not exist means the entry is
            // stale; that is different from a tool declining to print help.
            if (!HelpParser.isCommandNotFound(out)) {
                allNotFound = false;
            }
            outputs.add(out);
            if (used.isEmpty()) {
                used = invocation;
            }
            // Stop early once enough has been understood: each further probe
            // costs a process launch and rarely adds detail.
            Schema probe = HelpParser.parse(out, command);
            if (probe.options.size() >= ENOUGH_OPTIONS) {
                break;
            }
        }

        if (sawAnyOutput && allNotFound) {
            return null;
        }

        Schema best = HelpParser.bestOf(HelpParser.dedupe(outputs), command);
        best.helpInvocation = used;
        if (best.rawHelp.length() > MAX_HELP_CHARS) {
            best.rawHelp = best.rawHelp.substring(0, MAX_HELP_CHARS);
        }
        return best;
    }

    /**
     * True when every probe result was an environment failure rather than help.
     *
     * <p>Used to distinguish "this tool prints nothing we understand" from "this
     * tool could not run". The latter must not be reported as a tool with no
     * options, because the cause is a probing problem rather than the tool's
     * help.
     */
    public static boolean allProbesFailedEnvironmentally(List<String> outputs) {
        if (outputs.isEmpty()) {
            return false;
        }
        for (String o : outputs) {
            if (!EnvRunner.looksLikeEnvironmentFailure(o)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Probes one subcommand's help, on demand.
     *
     * <p>Called when the user selects a subcommand, not during discovery. The set
     * of subcommands is known from the parent's help; only the chosen one needs
     * its options learned.
     *
     * @return the subcommand's schema, or null when its help yielded nothing
     */
    public Schema buildSubcommand(String envPrefix, String command,
                                  Schema.Subcommand sub) {
        if (sub == null) {
            return null;
        }
        if (sub.schema != null) {
            return sub.schema;
        }
        List<String> outputs = new ArrayList<String>();
        for (String invocation : HelpParser.subcommandHelpInvocations(command, sub.name)) {
            String out = runProbe(envPrefix, invocation);
            if (out != null && !out.trim().isEmpty()) {
                outputs.add(out);
            }
            Schema probe = HelpParser.parse(out, command + " " + sub.name);
            if (probe.options.size() >= ENOUGH_OPTIONS) {
                break;
            }
        }
        Schema child = HelpParser.bestOf(HelpParser.dedupe(outputs),
                command + " " + sub.name);
        if (!child.hasOptions()) {
            return null;
        }
        child.helpInvocation = command + " " + sub.name + " --help";
        if (child.rawHelp.length() > MAX_HELP_CHARS) {
            child.rawHelp = child.rawHelp.substring(0, MAX_HELP_CHARS);
        }
        sub.schema = child;
        return child;
    }

    /**
     * Runs one help probe, on whichever route {@link RouteResolver} has settled on.
     *
     * <p>走快路还是 conda 由 {@link RouteResolver} 决定，不再一律走 conda。
     * 后者每次约 2100 ms，而一次探测往往要试好几种调用形式；快路约 4 ms。
     * 脚本类命令需要环境才能正常输出帮助，这一点由 resolver 实测后自动退回
     * conda 来兜住，所以快路不是「拿正确性换速度」。
     */
    private String runProbe(String envPrefix, String invocation) {
        return routes.run(envPrefix, invocation);
    }

    /**
     * Probes a command's version string.
     *
     * <p>Each probe costs a full environment start, so the number of attempts is
     * kept small and a version already discovered from help is reused rather
     * than probed again. Tools overwhelmingly print their version in the first
     * line of help output, which is why {@code build} passes whatever it found
     * here.
     *
     * @param knownFromHelp a version already seen in help output, or empty
     */
    public String probeVersion(String envPrefix, String command, String knownFromHelp) {
        if (knownFromHelp != null && !knownFromHelp.isEmpty()) {
            return knownFromHelp;
        }
        String[] probes = {command + " --version", command + " -version"};
        for (String p : probes) {
            String out = runProbe(envPrefix, p);
            String v = VersionParser.extract(out);
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return "";
    }
}

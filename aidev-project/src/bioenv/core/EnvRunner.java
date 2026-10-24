package bioenv.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs commands inside conda environments, purely for probing.
 *
 * <p>Separated from {@link Runner} (which executes user jobs with streaming and
 * cancellation) because their needs differ: probing wants a short timeout, no
 * streaming, and never fails loudly; execution wants streaming and cancellation.
 *
 * <p>conda is the only tool source by design (DESIGN.md P2).
 */
public final class EnvRunner {

    private final Transport wsl;
    private final String condaRoot;

    /** Timeout for a probe, in seconds. */
    private static final int PROBE_TIMEOUT_S = 20;

    /**
     * @param wsl       how to reach the Linux side; typed as {@link Transport} so
     *                  probing is identical over WSL and SSH
     * @param condaRoot the conda installation to prime {@code PATH} with
     */
    public EnvRunner(Transport wsl, String condaRoot) {
        this.wsl = wsl;
        this.condaRoot = condaRoot;
    }

    /**
     * Runs a command inside an environment and returns merged stdout+stderr.
     *
     * <p>Both streams are merged because tools disagree about where help goes;
     * {@code --version} typically uses stdout while usage errors use stderr, and
     * some tools write help to stderr outright.
     *
     * <p>The exit status is deliberately ignored: many tools exit non-zero when
     * asked for help, so it carries no information about success.
     */
    public String runCapture(String envPrefix, String command) {
        StringBuilder sb = new StringBuilder();
        if (condaRoot != null && !condaRoot.isEmpty()) {
            sb.append("export PATH=").append(WslBridge.quote(condaRoot + "/bin"))
              .append(":$PATH\n");
        }
        sb.append("timeout ").append(PROBE_TIMEOUT_S).append(' ');
        sb.append("conda run -p ").append(WslBridge.quote(envPrefix))
          .append(" --no-capture-output ").append(command).append(" 2>&1\n");
        Transport.Result r = wsl.runBashCapture(sb.toString());
        return r.stdoutText();
    }

    /**
     * Runs a probe by executing the environment's binary directly.
     *
     * <p>The fast route: measured at about 4 ms against conda's 2100 ms. Safe for
     * <em>probing</em> only — reading help or observing an error — because that
     * needs no more than reaching the program's argument parser. Actual execution
     * goes through {@link #runCapture} so the environment is set up.
     *
     * <p>Whether a command tolerates this route is decided by testing it, never by
     * inspecting the file; see {@link RouteResolver} and DESIGN.md P22.
     */
    public String runDirect(String envPrefix, String command) {
        String head = firstWord(command);
        String bin = envPrefix + "/bin/" + head;
        String args = command.length() > head.length()
                ? command.substring(head.length()) : "";
        StringBuilder sb = new StringBuilder();
        sb.append("timeout ").append(PROBE_TIMEOUT_S).append(' ');
        sb.append(WslBridge.quote(bin)).append(args).append(" 2>&1\n");
        return wsl.runBashCapture(sb.toString()).stdoutText();
    }

    /**
     * True when the environment exposes this command as an executable file,
     * the precondition for the direct route.
     */
    public boolean hasBinary(String envPrefix, String command) {
        String bin = envPrefix + "/bin/" + firstWord(command);
        String cmd = "test -x " + WslBridge.quote(bin) + " && echo YES || echo NO";
        return wsl.runBashCapture(cmd).stdoutText().contains("YES");
    }

    /**
     * True when a probe's output indicates the command could not run.
     *
     * <p>Recognising these shapes lets the plugin tell "this tool returned
     * nothing useful" apart from "this tool could not start". The distinction
     * matters because the two lead to different messages: the first is a
     * property of the tool's help, the second is a probing problem.
     *
     * <p>It also guards against a failure being mistaken for a tool with no
     * options, which would present the tool as unusable for reasons that have
     * nothing to do with the tool.
     */
    public static boolean looksLikeEnvironmentFailure(String output) {
        if (output == null || output.isEmpty()) {
            return false;
        }
        String o = output.toLowerCase();
        return o.contains("modulenotfounderror")
                || o.contains("no module named")
                || o.contains("importerror")
                || o.contains("can't open perl script")
                || o.contains("cannot load such file")
                || o.contains("traceback (most recent call last)")
                || o.contains("command not found")
                || o.contains("error while loading shared libraries")
                || o.contains("cannot open shared object file")
                || o.contains("undefined symbol")
                // An interpreter or loader that is not on the path, as reported
                // by env(1) or the dynamic linker when the environment is not
                // active.
                || (o.contains("no such file or directory")
                    && (o.contains("python") || o.contains("perl")
                        || o.contains("ruby") || o.contains("interpreter")
                        || o.contains("env:")))
                || o.contains("bad interpreter");
    }

    /** The leading token of a command string. */
    private static String firstWord(String command) {
        String c = command.trim();
        int sp = c.indexOf(' ');
        return sp < 0 ? c : c.substring(0, sp);
    }

    /**
     * Like {@link #runCapture} but distinguishes an empty result from a
     * failure, which callers need when deciding whether a command exists.
     */
    public boolean exists(String envPrefix, String command) {
        StringBuilder sb = new StringBuilder();
        if (condaRoot != null && !condaRoot.isEmpty()) {
            sb.append("export PATH=").append(WslBridge.quote(condaRoot + "/bin"))
              .append(":$PATH\n");
        }
        sb.append("conda run -p ").append(WslBridge.quote(envPrefix))
          .append(" --no-capture-output sh -c ")
          .append(WslBridge.quote("command -v " + command + " >/dev/null 2>&1"));
        return wsl.runBashCapture(sb.toString()).ok();
    }

    /** Lists executable files directly under an environment's {@code bin}. */
    public List<String> listExecutables(String envPrefix) {
        String cmd = "find " + WslBridge.quote(envPrefix + "/bin")
                + " -maxdepth 1 -type f -executable -printf '%f\\n' 2>/dev/null | sort";
        return wsl.runBashCapture(cmd).stdout();
    }

    /**
     * Lists the packages the user actually asked for in an environment.
     *
     * <p>This is the decisive signal for tool discovery: an environment's
     * {@code bin/} holds everything its dependency tree needed, but only the
     * explicit set is what the environment exists for. An environment with one
     * requested package can carry several hundred executables belonging to
     * libraries pulled in along the way (image codecs, font utilities, ICU
     * tooling), none of which the user wants listed as a command
     * (DESIGN.md P15).
     *
     * <p>Read from conda's own record of the request, so no tool, package or
     * environment name appears in this code.
     */
    public List<String> explicitPackages(String envPrefix) {
        String cmd = "conda env export -p " + WslBridge.quote(envPrefix)
                + " --from-history 2>/dev/null "
                + "| sed -n '/^dependencies:/,/^prefix:/p' "
                + "| sed -n 's/^  - //p' "
                + "| sed 's/[<>=!].*$//' "
                + "| sed 's#.*::##' "
                + "| sort -u";
        List<String> out = new java.util.ArrayList<String>();
        for (String line : wsl.runBashCapture(cmdOf(cmd)).stdout()) {
            String t = line.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * Everything needed to tell a tool from what it needed, in one call.
     *
     * <h2>Why one call</h2>
     *
     * Each of these facts used to be asked for separately, and the commands of each
     * package separately again — an environment with forty requested packages cost
     * forty round trips. Starting a WSL process takes about a second, so a scan
     * across thirty environments spent most of a minute waiting on process starts
     * for answers that were sitting in the same directory. Reading all of it at once
     * brings that back to one call per environment.
     *
     * @return what was asked for, what something depends on, and each package's
     *         commands
     */
    public EnvFacts facts(String envPrefix) {
        EnvFacts out = new EnvFacts();
        String script =
                "python3 - <<'PY'\n"
                + "import json, glob, collections, subprocess\n"
                + "prefix = " + quoteForPython(envPrefix) + "\n"
                + "needed = set()\n"
                + "commands = {}\n"
                + "declared = []\n"
                + "versions = {}\n"
                + "for f in glob.glob(prefix + '/conda-meta/*.json'):\n"
                + "    try: j = json.load(open(f))\n"
                + "    except Exception: continue\n"
                + "    name = j.get('name') or ''\n"
                + "    if name:\n"
                + "        declared.append(name)\n"
                + "        versions[name] = j.get('version') or ''\n"
                + "        bins = [x[4:] for x in (j.get('files') or [])\n"
                + "                if x.startswith('bin/') and '/' not in x[4:]]\n"
                + "        if bins: commands[name] = sorted(set(bins))\n"
                + "    for x in (j.get('depends') or []):\n"
                + "        needed.add(x.split()[0].split('=')[0].split('>')[0]"
                + ".split('<')[0].split('!')[0])\n"
                + "print('N\\t' + '\\t'.join(sorted(needed)))\n"
                + "print('D\\t' + '\\t'.join(sorted(set(declared))))\n"
                + "for name in sorted(versions):\n"
                + "    print('V\\t' + name + '\\t' + versions[name])\n"
                + "for name in sorted(commands):\n"
                + "    print('C\\t' + name + '\\t' + '\\t'.join(commands[name]))\n"
                + "PY\n";
        for (String line : wsl.runBashCapture(script).stdout()) {
            String[] parts = line.split("\t");
            if (parts.length == 0) {
                continue;
            }
            if ("N".equals(parts[0])) {
                for (int i = 1; i < parts.length; i++) {
                    if (!parts[i].trim().isEmpty()) {
                        out.neededBySomething.add(parts[i].trim());
                    }
                }
            } else if ("D".equals(parts[0])) {
                for (int i = 1; i < parts.length; i++) {
                    if (!parts[i].trim().isEmpty()) {
                        out.declared.add(parts[i].trim());
                    }
                }
            } else if ("V".equals(parts[0]) && parts.length >= 2) {
                // 包版本。用来判断「工具换过版本没有」——换过就当新装的重新探测，
                // 没换就保留用户在里面做过的所有设置。
                out.packageVersions.put(parts[1], parts.length >= 3 ? parts[2]
                        : "");
            } else if ("C".equals(parts[0]) && parts.length >= 2) {
                java.util.List<String> list = new java.util.ArrayList<String>();
                for (int i = 2; i < parts.length; i++) {
                    if (!parts[i].trim().isEmpty()) {
                        list.add(parts[i].trim());
                    }
                }
                out.commandsByPackage.put(parts[1], list);
            }
        }

        // What the user asked conda for. Asked separately because it is a conda
        // question rather than a file to read, and asked only when it is wanted.
        out.requested.addAll(explicitPackages(envPrefix));
        return out;
    }

    /** Quotes a value for embedding in a Python source string. */
    private static String quoteForPython(String s) {
        String t = s == null ? "" : s;
        return "'" + t.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    /**
     * What one environment records about itself.
     *
     * <p>Gathered in a single pass over its {@code conda-meta} directory, since every
     * fact here comes from the same files.
     */
    public static final class EnvFacts {
        /** What the user asked conda to install. */
        public final List<String> requested = new java.util.ArrayList<String>();
        /** Every package conda records, whether requested or pulled in. */
        public final List<String> declared = new java.util.ArrayList<String>();
        /** Package names that something in the environment depends on. */
        public final java.util.Set<String> neededBySomething =
                new java.util.HashSet<String>();
        /** Package name to the commands it installed, for packages that install any. */
        public final Map<String, List<String>> commandsByPackage =
                new LinkedHashMap<String, List<String>>();
        /**
         * Package name to its version, as conda records it.
         *
         * <p>Read from the same {@code conda-meta/*.json} files the other facts come
         * from, so it costs no extra round trip. It exists so a rescan can tell
         * "this tool was upgraded" from "nothing about this tool changed": the
         * former must be re-probed, the latter must keep every setting the user
         * made, and there is no way to tell them apart without a version.
         */
        public final Map<String, String> packageVersions =
                new LinkedHashMap<String, String>();
    }

    /**
     * The packages that other packages in an environment depend on.
     *
     * <p>This is what tells a tool from what the tool needed. An environment records
     * both as installed: ask for a Python program and conda notes the program
     * <em>and</em> the interpreter it runs on, and both then look like things the
     * user asked for. What separates them is that the interpreter is depended on by
     * something else in the same environment, while the tool at the top is depended
     * on by nothing.
     *
     * <p>Read from conda's own {@code depends} records, so no package name has to be
     * known here — the same rule holds for a tool, a library or a platform.
     *
     * @return the names something depends on, however conda writes them
     */
    public java.util.Set<String> dependedOnPackages(String envPrefix) {
        // conda writes a dependency as "name version build" or with a constraint, so
        // only the name is kept. Done in one call over all the manifests, which can
        // number in the hundreds.
        //
        // 环境前缀不能直接拼进 Python 源码：路径里只要有一个单引号
        // （/home/o'brien/...）整段脚本就是语法错误。以前 stderr 被 2>/dev/null
        // 丢掉，于是错误看不见、结果永远是空集，所有依赖包都被当成用户要的
        // 工具列出来。这里照 facts() 的做法用 quoteForPython 转义。
        String script = "import json,glob,collections\n"
                + "d=collections.Counter()\n"
                + "for f in glob.glob(" + quoteForPython(envPrefix)
                + " + '/conda-meta/*.json'):\n"
                + "    try: j=json.load(open(f))\n"
                + "    except Exception: continue\n"
                + "    for x in (j.get('depends') or []):\n"
                + "        d[x.split()[0].split('=')[0].split('>')[0]"
                + ".split('<')[0].split('!')[0]] += 1\n"
                + "print('\\n'.join(d.keys()))\n";
        // 不再把 stderr 丢进 /dev/null：读失败要能被看见，而不是伪装成「没有依赖」。
        String cmd = "python3 -c " + WslBridge.quote(script);
        Transport.Result r = wsl.runBashCapture(cmdOf(cmd));
        java.util.Set<String> out = new java.util.HashSet<String>();
        for (String line : r.stdout()) {
            String t = line.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        // 空结果 + 非空 stderr 说明脚本根本没跑起来（python3 缺失、前缀拼坏了、
        // 依赖记录读不了……），不是「这个环境没有依赖」。静默返回空集会让每个
        // 依赖包都被当成工具列出来，而用户没有任何线索，所以必须说出来。
        // 走 ConfigWarnings 而不是界面调用：这里仍是 core 层，同一问题只报一次。
        String err = r.stderrText() == null ? "" : r.stderrText().trim();
        if (out.isEmpty() && !err.isEmpty()) {
            ConfigWarnings.reportOnce("读不出环境的依赖关系，依赖包可能被当成工具列出来："
                    + envPrefix + "（" + errorSummary(err) + "）");
        }
        return out;
    }

    /**
     * 把多行错误压成一句：留第一行和最后一行。
     *
     * <p>Python 的 traceback 第一行往往只是 {@code File "<string>", line 1}，
     * 真正说明问题的是最后一行（{@code SyntaxError: ...}），所以两行都要留。
     */
    private static String errorSummary(String s) {
        if (s == null) {
            return "";
        }
        java.util.List<String> kept = new java.util.ArrayList<String>();
        for (String line : s.trim().split("\\r?\\n")) {
            String t = line.trim();
            if (!t.isEmpty()) {
                kept.add(cap(t));
            }
        }
        if (kept.isEmpty()) {
            return "";
        }
        if (kept.size() == 1) {
            return kept.get(0);
        }
        return kept.get(0) + " … " + kept.get(kept.size() - 1);
    }

    /** 提示里的一行文字最多留这么长，免得一段几百行的报错刷满输出面板。 */
    private static String cap(String t) {
        return t.length() > 120 ? t.substring(0, 120) + "…" : t;
    }

    /**
     * Lists the executables a specific package installed.
     *
     * <p>Read from the package's own file manifest, which conda records as
     * {@code bin/<name>} entries. This is what lets discovery offer exactly what
     * a requested package provides — all of it, since a package may legitimately
     * ship many commands — without inheriting anything from its dependencies.
     */
    public List<String> executablesOfPackage(String envPrefix, String packageName) {
        String cmd = "for j in " + WslBridge.quote(envPrefix + "/conda-meta")
                + "/" + WslBridge.quote(packageName) + "-*.json; do "
                + "  [ -f \"$j\" ] || continue; "
                + "  grep -o '\"bin/[^\"/]*\"' \"$j\" 2>/dev/null "
                + "    | sed 's#\"bin/##; s#\"##' ; "
                + "done | sort -u";
        List<String> out = new java.util.ArrayList<String>();
        for (String line : wsl.runBashCapture(cmdOf(cmd)).stdout()) {
            String t = line.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * Reads the package names an environment declares, from
     * {@code conda-meta/*.json}. Retained for the bundled-environment fallback.
     */
    public List<String> declaredPackages(String envPrefix) {
        String cmd = "ls " + WslBridge.quote(envPrefix + "/conda-meta") + "/*.json 2>/dev/null "
                + "| sed 's#.*/##; s#\\.json$##' | sed 's#-[0-9].*##' | sort -u";
        return wsl.runBashCapture(cmd).stdout();
    }

    /**
     * Wraps a shell command so it runs through bash with conda available.
     *
     * <p>Several probes invoke shell builtins or pipelines, which need a shell
     * rather than a bare executable.
     */
    private String cmdOf(String shellCommand) {
        StringBuilder sb = new StringBuilder();
        if (condaRoot != null && !condaRoot.isEmpty()) {
            sb.append("export PATH=").append(WslBridge.quote(condaRoot + "/bin"))
              .append(":$PATH\n");
        }
        sb.append(shellCommand).append('\n');
        return sb.toString();
    }

    public String getCondaRoot() {
        return condaRoot;
    }
}

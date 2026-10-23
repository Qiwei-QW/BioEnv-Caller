package bioenv.core;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import vmadapt.core.ProcessCapture;

/**
 * Transport to the WSL side. Every external command the plugin runs goes through
 * here, so the rest of the code never has to think about Windows quoting, WSL
 * invocation flags, or path translation.
 *
 * <p>Verified behaviours this class relies on (measured on conda 26.3.2):
 * <ul>
 *   <li>{@code conda run -p <prefix> --no-capture-output} streams output live;
 *       without {@code --no-capture-output} conda buffers everything until exit.</li>
 *   <li>Exit codes propagate faithfully (a missing command yields 127).</li>
 *   <li>On failure conda additionally prints a line starting with
 *       {@code ERROR conda.cli.main_run} to stderr; that line is not tool output
 *       and is suppressed by {@link #isCondaNoise(String)}.</li>
 * </ul>
 *
 * <p>本类实现 {@link Transport}，是为了让 WSL 与 SSH 虚拟机两条执行通道在
 * 消费者眼里可以互换：{@code EnvScanner}、{@code EnvRunner}、{@code Runner}
 * 等只关心「命令跑完的结果长什么样」，不该被绑死在 WSL 这一种通道上。
 * 静态工具方法（{@code quote} / {@code toWslPath} / {@code listDistros} 等）
 * 是 WSL 特有的知识，<b>不属于</b>这个接缝，因此保持原样、不进接口。
 */
public final class WslBridge implements Transport {

    /** Distro to target; null means "use the WSL default distro". */
    private String distro;
    /** Timeout for short probe commands, in seconds. */
    private static final int PROBE_TIMEOUT_S = 25;

    private static final Charset UTF8 = Charset.forName("UTF-8");

    public WslBridge() {
    }

    public WslBridge(String distro) {
        this.distro = distro;
    }

    /**
     * 返回本通道的人类可读标签，即 WSL 发行版名。
     *
     * <p>对应 {@link Transport#getDistro()}。在接口层面这个方法的语义是
     * 「这句话是在哪台机器上跑的」，WSL 侧的答案就是发行版名；界面据此
     * 回填那个发行版下拉框，错误提示里也用它告诉用户刚才探的是哪一台。
     */
    public String getDistro() {
        return distro;
    }

    public void setDistro(String distro) {
        this.distro = distro;
    }

    /**
     * 一次命令执行的结果快照。
     *
     * <p>实现 {@link Transport.Result}，从而能跨越 Transport 接缝被消费者读取。
     * 原有的 public 字段（{@code exitCode} / {@code stdout} / {@code stderr} /
     * {@code timedOut}）<b>全部保留</b>：树里仍有大量代码直接按字段访问，
     * 且字段名与接口方法名在 Java 里可以共存，所以两层表示可以并存，
     * 不必为了接接口而改动任何既有调用点。
     */
    public static final class Result implements Transport.Result {
        public int exitCode = -1;
        public final List<String> stdout = new ArrayList<String>();
        public final List<String> stderr = new ArrayList<String>();
        public boolean timedOut;

        public String stdoutText() {
            return join(stdout);
        }

        public String stderrText() {
            return join(stderr);
        }

        public boolean ok() {
            return exitCode == 0 && !timedOut;
        }

        /**
         * 退出码访问器，对应 {@link Transport.Result#exitCode()}。
         *
         * <p>为什么要有同名的字段与方法：接口无法声明字段，而既有代码全部按
         * {@code r.exitCode} 访问。与其改遍所有调用点去适应接口（那正是这次
         * 重构最想避免的大范围改动），不如让字段保留、再加一个只读访问器。
         * 结果是老代码一行不用动，新接口也能接上。
         */
        @Override
        public int exitCode() {
            return exitCode;
        }

        /**
         * 是否超时，对应 {@link Transport.Result#timedOut()}。
         *
         * <p>方法名刻意与字段同名（而不是 {@code isTimedOut()}）：消费者原来写的是
         * {@code r.timedOut}，字段转方法时 {@code r.timedOut()} 是纯机械替换，
         * 少一次「is 前缀对应哪个字段」的翻译，也就少一处写错的余地。
         */
        @Override
        public boolean timedOut() {
            return timedOut;
        }

        /**
         * stdout 逐行内容，对应 {@link Transport.Result#stdout()}。
         *
         * <p>返回空列表而不是 null：消费者普遍直接写
         * {@code for (String line : r.stdout())}，返回 null 会把「命令没有输出」
         * 这种正常情况变成 NullPointerException。
         */
        @Override
        public List<String> stdout() {
            return stdout;
        }

        /**
         * stderr 逐行内容，对应 {@link Transport.Result#stderr()}。
         *
         * @see #stdout()
         */
        @Override
        public List<String> stderr() {
            return stderr;
        }

        private static String join(List<String> lines) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) {
                    sb.append('\n');
                }
                sb.append(lines.get(i));
            }
            return sb.toString();
        }
    }

    /**
     * 逐行输出回调。
     *
     * <p>与 {@link Transport.LineSink} 保持<b>两个独立但形状相同</b>的接口，
     * 而不是让它 {@code extends Transport.LineSink}：后者会让本类
     * {@code runBashStreaming} 的参数类型变窄，反而不满足接口里
     * {@code runBashStreaming(String, Transport.LineSink)} 的契约
     * （形参是逆变位置，子接口不能替代父接口接收调用）。
     *
     * <p>两个接口的方法签名在磁盘上完全一致，所以任何实现任一者的匿名类
     * 都能按需适配；现有的 {@code runBashStreaming} 已放宽形参为
     * {@link Transport.LineSink}，两种写法都能直接传入，无需包装。
     */
    public interface LineSink {
        void line(String text, boolean isError);
    }

    // ------------------------------------------------------------------
    // WSL discovery
    // ------------------------------------------------------------------

    /**
     * Lists installed WSL distributions. Parses the UTF-16LE output of
     * {@code wsl -l -q}, which on Windows console hosts is emitted with
     * interleaved NUL bytes.
     */
    public static List<String> listDistros() {
        List<String> out = new ArrayList<String>();
        for (String d : listDistrosUnfiltered()) {
            // A container engine's own system is not somewhere a user installs
            // analysis tools, and offering it as a choice only produces a
            // confusing failure later.
            if (!isInfrastructureDistro(d)) {
                out.add(d);
            }
        }
        return out;
    }

    /**
     * Every distribution WSL reports, including the ones
     * {@link #listDistros()} leaves out.
     *
     * <p>Kept separate so a caller that finds nothing usable can say why: the
     * difference between "no WSL distribution" and "only a container engine's own
     * systems" decides which advice is right.
     */
    public static List<String> listDistrosUnfiltered() {
        List<String> out = new ArrayList<String>();
        try {
            ProcessBuilder pb = new ProcessBuilder("wsl.exe", "-l", "-q");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            // 15 秒上限现在真的能到：输出在 pump 线程上读，本线程只 waitFor。
            // 以前是「先读到 EOF，再 waitFor」，wsl.exe 一卡住超时就永远轮不到。
            final StringBuilder raw = new StringBuilder();
            ProcessCapture.run(p, 15, new ProcessCapture.LineSink() {
                @Override
                public void line(String text, boolean isError) {
                    raw.append(text).append('\n');
                }
            }, null);

            // wsl.exe emits UTF-16LE; strip NULs and any BOM, then split.
            String cleaned = raw.toString().replace("\u0000", "").replace("\uFEFF", "");
            for (String line : cleaned.split("\\r?\\n")) {
                String t = line.trim();
                if (!t.isEmpty() && !t.startsWith("Windows")) {
                    out.add(t);
                }
            }
        } catch (Exception ignored) {
            // No WSL installed, or wsl.exe unavailable: report an empty list.
        }
        return out;
    }

    /**
     * True when a distribution is infrastructure rather than a Linux environment.
     *
     * <p>Container tools register their own systems with WSL. Docker Desktop adds
     * {@code docker-desktop} to run the engine and {@code docker-desktop-data} to
     * hold images; both are Alpine-based, ship {@code ash} rather than
     * {@code bash}, and never hold conda. Rancher Desktop and Podman Desktop
     * register equivalents.
     *
     * <p>Excluding them matters for a second reason: Windows lists the default
     * distribution first, and these are frequently the default. Sending the probe
     * to one reported "WSL is not reachable" on a machine whose WSL was working
     * perfectly — the system simply had no {@code bash} to answer with.
     *
     * <p>The names are the closed sets published by the tools that register them,
     * which keeps this a statement about those tools rather than a guess about
     * what looks like one (DESIGN.md P1, P20).
     */
    public static boolean isInfrastructureDistro(String name) {
        if (name == null) {
            return false;
        }
        String n = name.trim().toLowerCase(java.util.Locale.ROOT);
        if (n.isEmpty()) {
            return false;
        }
        return n.equals("docker-desktop")
                || n.startsWith("docker-desktop-")
                || n.startsWith("rancher-desktop")
                || n.startsWith("podman-machine");
    }

    /**
     * The distribution to use when the user has not chosen one.
     *
     * <p>The first real distribution, not simply the first. Windows orders the
     * list with its default at the top, so this keeps the user's own default
     * whenever that default is usable and otherwise falls to the first that is —
     * rather than to whatever infrastructure system happens to be registered
     * first.
     *
     * @return the chosen distribution, or null when there is no real one
     */
    public static String preferredDistro(List<String> distros) {
        if (distros == null) {
            return null;
        }
        for (String d : distros) {
            if (!isInfrastructureDistro(d)) {
                return d;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Command execution
    // ------------------------------------------------------------------

    /**
     * Runs a bash script inside WSL and captures its output synchronously.
     * Intended for short probe commands, not for long analysis runs.
     *
     * <p>返回类型写成 {@code WslBridge.Result}（而不是接口里的
     * {@link Transport.Result}）是合法的<b>协变返回</b>：因为
     * {@code WslBridge.Result} 已经 {@code implements Transport.Result}，
     * 它是接口返回类型的子类型。这样做的好处是既满足了
     * {@link Transport#runBashCapture(String)} 的契约，又让既有调用点
     * （例如 {@code WslBridge.Result r = bridge.runBashCapture(...)}）一行都不用改。
     */
    public Result runBashCapture(String script) {
        Result r = new Result();
        final OutputCap out = new OutputCap();
        final OutputCap err = new OutputCap();
        try {
            Process p = startBash(script);
            // 两个流各由 ProcessCapture 的读取线程排空，本线程只做 waitFor(25s)。
            // 关键是顺序：以前先 readLines 到 EOF 才 waitFor，而卡住的子进程不会关闭
            // stdout，超时那一行于是永远执行不到。现在时间一到就强制结束，timedOut
            // 为 true、退出码保持 -1（见 Transport.Result 的契约）。
            ProcessCapture.Outcome o = ProcessCapture.run(p, PROBE_TIMEOUT_S,
                    new ProcessCapture.LineSink() {
                        @Override
                        public void line(String text, boolean isError) {
                            out.add(AnsiText.strip(text));
                        }
                    },
                    new ProcessCapture.LineSink() {
                        @Override
                        public void line(String text, boolean isError) {
                            err.add(AnsiText.strip(text));
                        }
                    });
            r.stdout.addAll(out.lines());
            r.stderr.addAll(err.lines());
            if (o.timedOut) {
                r.timedOut = true;
                r.stderr.add("命令在 " + PROBE_TIMEOUT_S
                        + " 秒内没有结束，已强制终止本次调用。");
            } else {
                r.exitCode = o.exitCode;
            }
        } catch (Exception e) {
            r.stderr.add("WSL invocation failed: " + e.getMessage());
        }
        return r;
    }

    /**
     * Runs a bash script inside WSL, forwarding output line by line to
     * {@code sink} so the UI can show progress live. Blocks until the process
     * ends; call from a background thread.
     *
     * <p>返回类型是协变返回（{@code WslBridge.Result} 是
     * {@link Transport.Result} 的子类型），因此既有调用点无需改动。
     * 形参写成 {@link Transport.LineSink} 是<b>必须</b>的：接口在形参位置
     * 要求能接收任何 {@code Transport.LineSink}，收窄成子接口就不满足契约。
     * 放宽之后，{@code Runner} 里传的 {@code Transport.LineSink} 匿名类
     * 与任何 {@code WslBridge.LineSink} 实例都能直接传入。
     */
    public Result runBashStreaming(String script, final Transport.LineSink sink) {
        Result r = new Result();
        final OutputCap out = new OutputCap();
        final OutputCap err = new OutputCap();
        try {
            Process p = startBash(script);
            // 运行类命令不设时间上限（分析任务动辄几十分钟），但两个流仍然各用一个
            // 读取线程：以前 stdout 在本线程读到 EOF、stderr 才用 pump，任一侧卡住都
            // 收不了尾。回调照旧收到每一行，只有「留下的那份」受 OutputCap 约束。
            ProcessCapture.Outcome o = ProcessCapture.run(p, 0,
                    new ProcessCapture.LineSink() {
                        @Override
                        public void line(String text, boolean isError) {
                            String clean = AnsiText.strip(text);
                            out.add(clean);
                            if (sink != null) {
                                sink.line(clean, false);
                            }
                        }
                    },
                    new ProcessCapture.LineSink() {
                        @Override
                        public void line(String text, boolean isError) {
                            String clean = AnsiText.strip(text);
                            err.add(clean);
                            if (sink != null) {
                                sink.line(clean, true);
                            }
                        }
                    });
            r.stdout.addAll(out.lines());
            r.stderr.addAll(err.lines());
            if (o.timedOut) {
                r.timedOut = true;
                r.stderr.add("命令在限定时间内没有结束，已强制终止本次调用。");
            } else {
                r.exitCode = o.exitCode;
            }
        } catch (Exception e) {
            r.stderr.add("WSL invocation failed: " + e.getMessage());
            if (sink != null) {
                sink.line("WSL invocation failed: " + e.getMessage(), true);
            }
        }
        return r;
    }

    /**
     * Starts {@code wsl.exe [--distribution X] -- bash -s} with {@code script}
     * written to stdin. Passing the script via stdin avoids all Windows
     * command-line length limits and quoting hazards (Chinese paths, spaces,
     * quotes, {@code $} signs) that plagued the probe scripts during design.
     */
    private Process startBash(String script) throws IOException {
        List<String> cmd = new ArrayList<String>();
        cmd.add("wsl.exe");
        if (distro != null && !distro.trim().isEmpty()) {
            cmd.add("-d");
            cmd.add(distro.trim());
        }
        cmd.add("--");
        cmd.add("bash");
        cmd.add("-s");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        Process p = pb.start();

        OutputStream os = p.getOutputStream();
        os.write(script.getBytes(UTF8));
        os.flush();
        os.close();
        return p;
    }

    /**
     * Runs a short script and does not wait for it.
     *
     * <p>For work that has to happen on the far side while the caller carries on.
     * Stopping a run is the case this exists for: the kill has to be issued from
     * inside WSL, and the caller is meanwhile returning from its own wait.
     *
     * <p>Output is discarded. A kill script has nothing useful to say, and a caller
     * in the middle of cancelling has nowhere to put it.
     *
     * @param script the script to run
     * @param distroName the distribution, or null for the default
     * @return the process, so a caller that wants to may wait for it
     */
    public Process runDetached(String script, String distroName) throws IOException {
        if (script == null || script.isEmpty()) {
            return null;
        }
        List<String> cmd = new ArrayList<String>();
        cmd.add("wsl.exe");
        String d = distroName != null && !distroName.trim().isEmpty()
                ? distroName : distro;
        if (d != null && !d.trim().isEmpty()) {
            cmd.add("-d");
            cmd.add(d.trim());
        }
        cmd.add("--");
        cmd.add("bash");
        cmd.add("-s");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        // Discarded rather than read: nothing is waiting on it, and an unread pipe
        // would fill and block the script.
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        OutputStream os = p.getOutputStream();
        os.write(script.getBytes(UTF8));
        os.flush();
        os.close();
        return p;
    }

    /**
     * Creates a directory inside WSL, and reports where it is.
     *
     * <p>For a working area on the Linux side. Writing there is far faster than
     * writing through {@code /mnt/c}: that path is a network-like share between
     * Windows and the virtual machine, and a tool that creates tens of thousands of
     * small files — which is what a search tool does — pays for every one of them.
     *
     * @param preferred the path to create, or null to choose one
     * @param label     a name to include, so several runs do not share a directory
     * @return the created path, or null when it could not be made
     */
    public String createWorkDir(String preferred, String label) {
        StringBuilder sb = new StringBuilder();
        sb.append("set -e\n");
        if (preferred != null && !preferred.trim().isEmpty()) {
            sb.append("d=").append(quote(preferred.trim())).append('\n');
        } else {
            String safe = label == null ? "work" : label.replaceAll("[^A-Za-z0-9_.-]", "_");
            sb.append("d=\"$HOME/bioenv_work/").append(safe).append('_')
              .append("$(date +%Y%m%d_%H%M%S)\"\n");
        }
        sb.append("mkdir -p \"$d\"\n");
        sb.append("printf '%s' \"$d\"\n");

        Result r = runBashCapture(sb.toString());
        if (!r.ok()) {
            return null;
        }
        String path = r.stdoutText().trim();
        return path.isEmpty() ? null : path;
    }

    /**
     * Copies paths from the Windows side into a directory on the Linux side.
     *
     * <p>Reading and writing through {@code /mnt/c} goes over a share between Windows
     * and the virtual machine. A tool that touches a file once barely notices; one
     * that creates tens of thousands of small files spends most of its time waiting.
     * Moving the inputs across first and running entirely on the Linux side is the
     * difference between minutes and hours, measured at 43 ms against 16.9 seconds
     * for two thousand small files.
     *
     * <p>Directories are copied whole. Nothing is deleted, and a file that is already
     * there and the same size is left alone, so pressing the button twice does not
     * copy everything again.
     *
     * @param sources   paths to copy, in the order they should appear
     * @param targetDir directory on the Linux side to copy into
     * @return for each source, the path it now has; an entry is null when it could
     *         not be copied
     */
    public List<String> copyInto(List<String> sources, String targetDir) {
        List<String> out = new ArrayList<String>();
        if (sources == null || sources.isEmpty() || targetDir == null
                || targetDir.trim().isEmpty()) {
            return out;
        }
        // One script rather than one call per file: starting an environment has a
        // fixed cost, and a run with a dozen inputs would otherwise pay it a dozen
        // times.
        StringBuilder sb = new StringBuilder();
        sb.append("set -e\n");
        sb.append("t=").append(quote(targetDir.trim())).append('\n');
        sb.append("mkdir -p \"$t\"\n");
        // Nothing is copied twice: same name and same size counts as already there.
        sb.append("cp_one() {\n");
        sb.append("  s=\"$1\"\n");
        sb.append("  b=$(basename \"$s\")\n");
        sb.append("  d=\"$t/$b\"\n");
        sb.append("  if [ -e \"$d\" ] && [ \"$(stat -c %s \"$s\" 2>/dev/null)\" = ")
          .append("\"$(stat -c %s \"$d\" 2>/dev/null)\" ]; then\n");
        sb.append("    printf 'SKIP\\t%s\\n' \"$d\"\n");
        sb.append("  elif cp -r \"$s\" \"$d\" 2>/dev/null; then\n");
        sb.append("    printf 'OK\\t%s\\n' \"$d\"\n");
        sb.append("  else\n");
        sb.append("    printf 'FAIL\\t%s\\n' \"$s\"\n");
        sb.append("  fi\n");
        sb.append("}\n");
        for (String s : sources) {
            sb.append("cp_one ").append(quote(toWslPath(s))).append('\n');
        }

        Result r = runBashCapture(sb.toString());
        // The outputs come back in the same order as the sources, which is how the
        // caller matches them to the fields they came from.
        List<String> results = new ArrayList<String>();
        for (String line : r.stdout) {
            String t = line.trim();
            if (t.startsWith("OK\t") || t.startsWith("SKIP\t")) {
                results.add(t.substring(t.indexOf('\t') + 1).trim());
            } else if (t.startsWith("FAIL\t")) {
                results.add(null);
            }
        }
        while (results.size() < sources.size()) {
            results.add(null);
        }
        return results.subList(0, sources.size());
    }

    /**
     * True when a path is on a Windows disk, which is the slow side.
     *
     * <p>Used to decide what is worth copying: a file already on the Linux side is
     * where it should be.
     */
    public static boolean isWindowsPath(String path) {
        if (path == null) {
            return false;
        }
        String p = path.trim();
        if (p.isEmpty() || p.startsWith("/")) {
            return false;
        }
        if (p.startsWith("\\\\wsl$\\") || p.startsWith("\\\\wsl.localhost\\")) {
            return false;
        }
        return p.length() >= 2 && p.charAt(1) == ':';
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * True when a stderr line is conda's own diagnostic wrapper rather than
     * output from the tool. Such lines are suppressed in the UI so users see
     * the real error message.
     */
    public static boolean isCondaNoise(String line) {
        if (line == null) {
            return false;
        }
        String t = line.trim();
        return t.startsWith("ERROR conda.cli.main_run")
                || t.startsWith("Error loading anaconda-anon-usage");
    }

    // ------------------------------------------------------------------
    // Path translation and quoting
    // ------------------------------------------------------------------

    /**
     * Converts a Windows path to its WSL equivalent:
     * {@code D:\data\in.fa} becomes {@code /mnt/d/data/in.fa}.
     * A path already in POSIX form is returned unchanged.
     */
    public static String toWslPath(String windowsPath) {
        if (windowsPath == null) {
            return null;
        }
        String p = windowsPath.trim();
        if (p.isEmpty()) {
            return p;
        }
        if (p.startsWith("/")) {
            return p; // already POSIX
        }
        if (p.startsWith("\\\\wsl$\\") || p.startsWith("\\\\wsl.localhost\\")) {
            return wslUncToPosix(p);
        }
        p = p.replace('\\', '/');
        if (p.length() >= 2 && p.charAt(1) == ':') {
            char drive = Character.toLowerCase(p.charAt(0));
            String rest = p.substring(2);
            if (!rest.startsWith("/")) {
                rest = "/" + rest;
            }
            // Trim a trailing slash so a drive root maps to "/mnt/c" rather
            // than "/mnt/c/". Both work in a shell, but the canonical form
            // matters when the path is concatenated or compared.
            while (rest.length() > 1 && rest.endsWith("/")) {
                rest = rest.substring(0, rest.length() - 1);
            }
            return "/mnt/" + drive + (rest.equals("/") ? "" : rest);
        }
        return p;
    }

    /** Converts {@code \\wsl$\Ubuntu\home\qw\x} to {@code /home/demo/x}. */
    private static String wslUncToPosix(String unc) {
        String rest = unc;
        if (rest.startsWith("\\\\wsl$\\")) {
            rest = rest.substring("\\\\wsl$\\".length());
        } else if (rest.startsWith("\\\\wsl.localhost\\")) {
            rest = rest.substring("\\\\wsl.localhost\\".length());
        }
        int slash = rest.indexOf('\\');
        if (slash < 0) {
            return "/";
        }
        return "/" + rest.substring(slash + 1).replace('\\', '/');
    }

    /**
     * Converts a WSL path back to a Windows-visible path for display and for
     * the file chooser. Paths under {@code /mnt/<drive>} map to drive letters;
     * everything else maps to the {@code \\wsl$\<distro>} UNC share.
     */
    public static String toWindowsPath(String posixPath, String distro) {
        if (posixPath == null) {
            return null;
        }
        String p = posixPath.trim();
        if (p.isEmpty()) {
            return p;
        }
        if (p.length() >= 2 && p.charAt(1) == ':') {
            return p; // already a Windows path
        }
        if (p.startsWith("/mnt/") && p.length() > 6) {
            char drive = Character.toUpperCase(p.charAt(5));
            // substring(6) keeps the leading slash ("/a.fa"), so the separator
            // must only be added when it is absent.
            String rest = p.substring(6);
            if (rest.startsWith("/")) {
                rest = rest.substring(1);
            }
            return drive + ":\\" + rest.replace('/', '\\');
        }
        String d = (distro == null || distro.trim().isEmpty()) ? "Ubuntu" : distro.trim();
        String tail = p.startsWith("/") ? p.substring(1) : p;
        return "\\\\wsl$\\" + d + "\\" + tail.replace('/', '\\');
    }

    /**
     * Quotes a single shell argument for bash, always.
     *
     * <p>Used where a value's safety cannot be judged — most notably for
     * environment prefixes and commands assembled from discovery output.
     */
    public static String quote(String arg) {
        if (arg == null) {
            return "''";
        }
        return "'" + arg.replace("'", "'\\''") + "'";
    }

    /**
     * Quotes an argument only when it needs quoting.
     *
     * <p>Command lines are read, copied and turned into scripts by hand, so they
     * should look like something a person would write. Blanket-quoting every
     * value produces noise such as {@code -M 'msa'} and {@code -t '12'}, which
     * is harder to read and harder to adapt (DESIGN.md P13).
     *
     * <p>A value is emitted bare when it consists only of characters bash treats
     * literally. Anything containing whitespace, quotes, globbing or expansion
     * characters is quoted, so paths with spaces and values with shell
     * metacharacters remain safe.
     */
    public static String quoteIfNeeded(String arg) {
        if (arg == null || arg.isEmpty()) {
            return "''";
        }
        if (needsQuoting(arg)) {
            return quote(arg);
        }
        return arg;
    }

    /**
     * True when a value cannot be emitted bare.
     *
     * <p>The safe set is letters, digits and the punctuation that appears
     * routinely in paths, numbers, formats and option values: {@code . _ - + / :
     * = , @ %}. Everything else — spaces, quotes, {@code $ * ? [ ] ~ ! & | ; < >
     * ( ) { } \ } — means the value is quoted.
     */
    public static boolean needsQuoting(String arg) {
        if (arg == null || arg.isEmpty()) {
            return true;
        }
        for (int i = 0; i < arg.length(); i++) {
            char c = arg.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-' || c == '+' || c == '/'
                    || c == ':' || c == '=' || c == ',' || c == '@' || c == '%';
            if (!safe) {
                return true;
            }
        }
        return false;
    }

    /** Resolves the bundled-JRE-safe path for a Windows File, or null. */
    public static String absoluteOrNull(File f) {
        return f == null ? null : f.getAbsolutePath();
    }
}

package vmadapt.core;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * 一台虚拟机的 SSH 传输通道，基于 Windows 自带的 OpenSSH 客户端。
 *
 * <p>为什么不用 JSch 之类的纯 Java SSH 库：
 * <ul>
 *   <li>TBtools 的插件目录不方便再塞第三方 jar，且 JSch 已多年不维护；
 *       引入它意味着要自己处理加密算法协商、known_hosts、agent 转发。</li>
 *   <li>Windows 10 1809 以后自带 OpenSSH 客户端，能自动复用用户已经配好的
 *       ssh-agent、known_hosts 和 config，用户的学习成本为零。</li>
 *   <li>scp/sftp 同样自带，文件传输任务可以和我们共用同一套参数。</li>
 * </ul>
 * 代价是我们必须自己处理进程、流和编码——这正是本类存在的意义。
 *
 * <p>关于「网络层错误」与「远端命令错误」的区分：ssh.exe 用一个退出码
 * （通常是 255）表示连接阶段失败，把失败原因写进 stderr；远端脚本自己的
 * 退出码则原样透传。用户看到「连不上」和「命令跑挂了」时需要做的事完全不同，
 * 所以 {@link #diagnose(Result)} 会把这些信号翻译成中文建议。
 */
public final class SshTransport {

    /** 编码统一 UTF-8：远端现代 Linux 默认就是它，ssh.exe 也是原样透传。 */
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** 探测类短命令的超时（秒）。 */
    private static final int PROBE_TIMEOUT_S = 25;

    /** 默认执行类命令的超时（秒）。5 分钟足够跑完常规分析步骤。 */
    private static final int DEFAULT_RUN_TIMEOUT_S = 300;

    /** 转储输出时单行最大长度，避免一个超长行把界面撑爆。 */
    private static final int MAX_LINE_CHARS = 20000;

    private final SshTarget target;

    /** 执行类命令的超时秒数，可由调用方调整。 */
    private int runTimeoutSeconds = DEFAULT_RUN_TIMEOUT_S;

    /**
     * @param target 连接目标；允许 host 暂时为空（此时任何执行都会立刻返回
     *               带中文错误的失败结果，而不是抛异常），这让界面可以在
     *               用户还没填完表单时就安全地构造对象。
     */
    public SshTransport(SshTarget target) {
        this.target = target == null ? new SshTarget() : target;
    }

    /** 返回被包装的连接目标。 */
    public SshTarget target() {
        return target;
    }

    /** 设置执行类命令的超时秒数。 */
    public void setRunTimeoutSeconds(int seconds) {
        if (seconds > 0) {
            this.runTimeoutSeconds = seconds;
        }
    }

    /** 当前执行类命令的超时秒数。 */
    public int runTimeoutSeconds() {
        return runTimeoutSeconds;
    }

    // ------------------------------------------------------------------
    // 结果类型
    // ------------------------------------------------------------------

    /**
     * 一次远端命令的结果。
     *
     * <p>字段 public 是为了让其它模块少写样板；{@link #ok()} 是唯一的成功判据。
     * stdout / stderr 同时以「行列表」和「整段文本」两种形式提供：界面要按行
     * 上色，解析器要整段匹配，两种需求都常见，各自转换一次不值得。
     */
    public static final class Result {
        /** 进程退出码；-1 表示进程根本没起来，或超时被杀。 */
        public int exitCode = -1;

        public final List<String> stdout = new ArrayList<String>();
        public final List<String> stderr = new ArrayList<String>();

        /** 是否因为超时被强制终止。 */
        public boolean timedOut;

        /** 真正执行的命令行（已渲染成文字），仅用于日志与排错。 */
        public String commandLine = "";

        /** 退出码为 0 且未超时。 */
        public boolean ok() {
            return exitCode == 0 && !timedOut;
        }

        /** stdout 全文（无末尾换行）。 */
        public String stdoutText() {
            return join(stdout);
        }

        /** stderr 全文（无末尾换行）。 */
        public String stderrText() {
            return join(stderr);
        }

        /** stdout 去空白后的单行结果，探测场景最常用。 */
        public String stdoutTrimmed() {
            return stdoutText().trim();
        }

        /** 是否属于「连接阶段就失败」——ssh.exe 用 255 表示这一类。 */
        public boolean isTransportFailure() {
            if (timedOut) {
                return true;
            }
            if (exitCode == 255) {
                return true;
            }
            // 进程压根没起来（找不到 ssh.exe）
            return exitCode < 0;
        }

        /** 是否属于「连上了，但远端命令自己失败」。 */
        public boolean isRemoteFailure() {
            return !timedOut && exitCode > 0 && exitCode != 255;
        }

        @Override
        public String toString() {
            return "exit=" + exitCode + (timedOut ? " (timed out)" : "")
                    + " out=" + stdout.size() + " lines err=" + stderr.size() + " lines";
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

    /** 逐行输出回调；{@code isError} 为 true 表示这行来自 stderr。 */
    public interface LineSink {
        void line(String text, boolean isError);
    }

    // ------------------------------------------------------------------
    // 对外执行接口
    // ------------------------------------------------------------------

    /**
     * 快速探测这台机器是否可达、是否是 Linux、有没有 bash。
     *
     * <p>用一条短脚本一次问完所有问题，而不是分几次连接：每次 SSH 握手在
     * 虚拟机上要花几百毫秒，一次搞定能让「测试连接」按钮的响应快得多。
     *
     * @return 探测结果；连不上时 exitCode=255，stderr 里有原因
     */
    public Result probe() {
        return runCapture(probeScript(), PROBE_TIMEOUT_S);
    }

    /**
     * 构造探测脚本。
     *
     * <p>单独抽出来是为了能测：这条脚本的「每个字段独占一行」是硬要求，
     * 而它一旦被破坏，表现是某个字段静默消失——不会报错，只会让上层得出
     * 完全错误的结论。
     */
    public static String probeScript() {
        StringBuilder sb = new StringBuilder();
        // OSNAME 放最前：它是「这台机器是什么系统」最直接的答案。缺了它，
        // 报告里的 osName 只能退回内核名，界面于是把「Linux」当操作系统显示，
        // 而用户想知道的是 Ubuntu 22.04 还是 CentOS。
        probeField(sb, "OSNAME",
                "if [ -r /etc/os-release ]; then . /etc/os-release; "
                        + "printf '%s' \"$PRETTY_NAME\"; else echo unknown; fi");
        probeField(sb, "KERNEL", "uname -s 2>/dev/null || echo unknown");
        probeField(sb, "RELEASE", "uname -r 2>/dev/null || echo unknown");
        probeField(sb, "MACHINE", "uname -m 2>/dev/null || echo unknown");
        probeField(sb, "HOME", "printf '%s' \"$HOME\"");
        probeField(sb, "BASH", "command -v bash 2>/dev/null || echo none");
        probeField(sb, "PYTHON",
                "command -v python3 2>/dev/null || command -v python 2>/dev/null || echo none");
        probeField(sb, "CONDA", "c=$(command -v conda 2>/dev/null); "
                + "if [ -z \"$c\" ]; then for p in \"$HOME/miniconda3/bin/conda\" "
                + "\"$HOME/anaconda3/bin/conda\" \"$HOME/miniforge3/bin/conda\" "
                + "\"/opt/conda/bin/conda\" \"/opt/miniconda3/bin/conda\" "
                + "\"/opt/anaconda3/bin/conda\"; do "
                + "if [ -x \"$p\" ]; then c=\"$p\"; break; fi; done; fi; "
                + "printf '%s' \"${c:-none}\"");
        probeField(sb, "USER", "id -un 2>/dev/null || echo unknown");
        probeField(sb, "PWD", "pwd");
        return sb.toString();
    }

    /**
     * 追加一条探测字段，末尾**强制**补一个换行。
     *
     * <p>为什么必须强制：字段的命令不一定自带换行——{@code printf '%s' "$HOME"}
     * 就不带。那样下一个字段的 {@code KEY=} 会黏在同一行末尾，解析器按行读就
     * 再也看不到那个 KEY。
     *
     * <p>这个 bug 真实发生过：HOME 把 BASH 吞掉，于是探测结果里没有 BASH，
     * 界面报「远端没有 bash」，而远端其实好好的。当时排查花了很多轮，
     * 因为每一层单独看都正确——错的是它们拼在一起的方式。
     */
    private static void probeField(StringBuilder sb, String key, String command) {
        sb.append("printf '").append(key).append("='; ")
          .append(command)
          .append("; printf '\\n'\n");
    }

    /**
     * 执行一段 bash 脚本并收集全部输出。
     *
     * <p>脚本通过 stdin 送给远端 {@code bash -s}，不拼进命令行——见
     * {@link SshCommand} 的说明。stdout 与 stderr 分别收集，因为调用方需要
     * 区分「工具的正常输出」和「工具抱怨了什么」。
     *
     * @param script bash 脚本内容
     * @return 结果；target 未填完整时直接返回带中文提示的失败结果
     */
    public Result runBashCapture(String script) {
        return runCapture(script, runTimeoutSeconds);
    }

    /**
     * 执行一段 bash 脚本，并把输出逐行实时回调给 {@code sink}。
     *
     * <p>为什么必须逐行而不缓冲：分析任务动辄跑几十分钟，用户需要看到进度；
     * 如果等进程结束再一次性吐出，界面在某些工具（如 conda/mamba）上会长时间
     * 一片空白，用户会以为程序卡死。stdout 和 stderr 各用一个守护线程读，
     * 两个管道都不设置大缓冲，因此每一行一到达就回调。
     *
     * <p>本方法阻塞到进程结束，调用方应在后台线程调用它。
     *
     * <p>本重载等价于「调用方没有可用的结束方式」：转调三参数版本并传 null，
     * 因此超时时的措辞会如实说明远端可能仍在运行。需要「超时顺便让远端停下」的
     * 调用方请用 {@link #runBashStreaming(String, LineSink, String)}。
     *
     * @param script bash 脚本内容
     * @param sink   行回调，可为 null（此时只收集不回调）
     * @return 结果
     */
    public Result runBashStreaming(String script, final LineSink sink) {
        return runBashStreaming(script, sink, null);
    }

    /**
     * 执行一段 bash 脚本，逐行回调输出，并在<b>超时</b>时把远端进程树也停掉。
     *
     * <p>为什么要多这一个参数：超时之后 {@code destroyForcibly} 杀的是本机的
     * {@code ssh.exe}，而真正干活的是另一台机器上的进程树，两者不在一个进程树里。
     * 要让远端停下，必须<b>另开一条连接</b>把一条结束命令送过去——也就是用户点
     * 「停止」时用的那套机制。
     *
     * <h2>为什么传的是脚本文本，不是标记</h2>
     *
     * <p>结束命令必须按「本次运行」的标记来匹配进程，而生成它的
     * {@code CommandBuilder.killScript(marker)} 属于插件模块（bioenv）。
     * 模块只允许单向依赖：根目录 build.ps1 先编译 vmadapt，编译它时 classpath 是空的，
     * 本模块 import 插件代码会直接让构建失败。把生成好的脚本文本传下来，
     * 既保住了依赖方向，也把「标记是什么」留在它该在的那一层。
     *
     * <h2>超时时的三种措辞（都不宣称远端已死）</h2>
     *
     * <ul>
     *   <li>{@code killScript} 为 null/空：本机客户端已终止，<b>没有</b>送出停止命令，
     *       远端进程树可能仍在运行；</li>
     *   <li>停止命令已送出（{@code runDetached} 返回了进程）：只说「已把停止命令
     *       发到某主机」，并说明<b>是否停下来没有得到确认</b>——这条命令不等结果、
     *       也不读输出；</li>
     *   <li>停止命令送不出去（返回 null 或抛异常）：说明远端进程树可能仍在运行，
     *       并给出原因。</li>
     * </ul>
     *
     * @param script     bash 脚本内容
     * @param sink       行回调，可为 null
     * @param killScript 结束本次运行的远端命令；null 或空串表示调用方没有可用的方式
     * @return 结果
     */
    public Result runBashStreaming(String script, final LineSink sink, String killScript) {
        Result r = new Result();
        String invalid = validateForRun();
        if (invalid != null) {
            r.stderr.add(invalid);
            if (sink != null) {
                sink.line(invalid, true);
            }
            return r;
        }
        Process p = null;
        final ProcessCapture.CappedOutput out = new ProcessCapture.CappedOutput();
        final ProcessCapture.CappedOutput err = new ProcessCapture.CappedOutput();
        try {
            String[] cmd = SshCommand.build(target);
            r.commandLine = SshCommand.toDisplayString(cmd);
            p = startProcess(cmd, script);

            // 执行与收尾都交给 ProcessCapture：两个流各一个读取线程（顺序读会在
            // 管道写满时死锁），本线程只负责 waitFor(runTimeoutSeconds)。
            //
            // 以前是「先把 stdout 读到 EOF，再 waitFor 超时」——远端命令只要不关闭
            // stdout，waitFor 那一行就永远执行不到，300 秒的 runTimeoutSeconds 于是
            // 形同虚设，用户看到的是界面永远停在「运行中」。
            ProcessCapture.Outcome o = ProcessCapture.run(p, runTimeoutSeconds,
                    new ProcessCapture.LineSink() {
                        @Override
                        public void line(String text, boolean isError) {
                            String clean = clamp(Shell.stripAnsi(text));
                            out.add(clean);
                            if (sink != null) {
                                // 实时回调：每一行都交出去，界面自己裁剪；
                                // CappedOutput 只约束"留下来的那一份"。
                                sink.line(clean, false);
                            }
                        }
                    },
                    new ProcessCapture.LineSink() {
                        @Override
                        public void line(String text, boolean isError) {
                            String clean = clamp(Shell.stripAnsi(text));
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
                String msg = stopRemoteAndDescribe(runTimeoutSeconds, killScript);
                r.stderr.add(msg);
                if (sink != null) {
                    sink.line(msg, true);
                }
            } else {
                r.exitCode = o.exitCode;
            }
        } catch (Exception e) {
            r.stderr.add(describeLaunchFailure(e));
            if (sink != null) {
                sink.line(r.stderr.get(r.stderr.size() - 1), true);
            }
        }
        return r;
    }

    /**
     * 超时之后的收尾：本机客户端已经被杀，再按需要把结束命令送到远端，
     * 并把<b>实际发生了什么</b>翻译成一句给用户看的话。
     *
     * <h2>为什么措辞要分三种</h2>
     *
     * <p>{@code destroyForcibly} 只杀了本机的 {@code ssh.exe}；远端那条
     * {@code conda run} 进程树会不会跟着结束，取决于远端的 sshd 与终端语义，
     * 所以任何情况下都不能写「远端已终止」。三种情形能确认的事情不同：
     * 没有结束方式、已把结束命令送出、送不出去——用户接下来该做的事也不同
     * （分别对应：手动去远端处理、稍等确认、检查连接）。
     *
     * <p>「已送出」也只说「已把停止命令发到某主机」：{@link #runDetached} 只负责
     * 把命令写进一条新连接的 stdin，即不等它执行完、也不读它的输出，所以
     * <b>是否真的停下来没有得到确认</b>。
     *
     * @param seconds    这次运行的超时秒数
     * @param killScript 调用方给的结束命令；null 或空表示没有
     * @return 给用户看的一句话（英文，与界面其它运行期消息一致）
     */
    private String stopRemoteAndDescribe(int seconds, String killScript) {
        String head = "The remote command did not finish within " + seconds
                + " seconds. The local ssh client was terminated";
        if (killScript == null || killScript.trim().isEmpty()) {
            return head + "; no stop command was available, so the remote process tree may "
                    + "still be running on " + safeHost() + ". If the task is genuinely long, "
                    + "raise the timeout.";
        }
        try {
            Process p = runDetached(killScript);
            if (p != null) {
                return head + ", and a stop command was sent to " + safeHost()
                        + ". Whether the remote processes have stopped is not confirmed "
                        + "(the command is not waited for).";
            }
            return head + ", but a stop command could not be sent to " + safeHost()
                    + " (the connection parameters are incomplete, or ssh.exe is missing), "
                    + "so the remote process tree may still be running.";
        } catch (Exception e) {
            return head + ", but sending a stop command to " + safeHost() + " failed ("
                    + e + "), so the remote process tree may still be running.";
        }
    }

    /**
     * 另起一条独立连接执行脚本，不等它结束、不读它的输出。
     *
     * <p>用途是「取消」：用户点停止时，我们需要在远端把正在跑的分析 kill 掉，
     * 而这个动作必须发生在一个<b>新的</b> SSH 连接上——原来那条连接正忙着跑
     * 任务，命令发不进去。输出直接 DISCARD：kill 脚本没什么有用的话要说，
     * 而调用方正在从自己的 wait 里返回，没地方接收。不读管道还有一个硬理由：
     * 没人读的管道写满后会让远端脚本自己阻塞。
     *
     * @param script 要在远端独立执行的脚本
     * @return 已启动的进程，调用方可以选择等待或直接丢弃；启动失败返回 null
     */
    public Process runDetached(String script) {
        if (script == null || script.isEmpty()) {
            return null;
        }
        if (validateForRun() != null) {
            return null;
        }
        try {
            String[] cmd = SshCommand.build(target);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            OutputStream os = p.getOutputStream();
            os.write(script.getBytes(UTF8));
            os.flush();
            os.close();
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 用一条独立连接立刻掐断目标命令。
     *
     * <p>先 TERM 后 KILL，给工具一个清理临时文件的机会；等待放在远端完成，
     * 因此本方法很快返回，不会拖住取消按钮。
     *
     * @param pattern 用于匹配进程的字符串（例如输出目录名），会做 shell 引用
     * @return 是否成功启动了取消连接
     */
    public boolean killRemote(String pattern) {
        if (pattern == null || pattern.trim().isEmpty()) {
            return false;
        }
        String quoted = Shell.quote(pattern.trim());
        StringBuilder sb = new StringBuilder();
        sb.append("pkill -TERM -f ").append(quoted).append(" 2>/dev/null\n");
        sb.append("sleep 2\n");
        sb.append("pkill -KILL -f ").append(quoted).append(" 2>/dev/null\n");
        sb.append("echo DISCARD\n");
        return runDetached(sb.toString()) != null;
    }

    /**
     * 把一次失败的结果翻译成中文，指明是网络层还是远端命令层的问题。
     *
     * <p>识别的信号取自实测的 ssh.exe 输出：
     * Connection refused / Connection timed out / Permission denied (publickey) /
     * Host key verification failed / Could not resolve hostname /
     * No such file or directory（密钥）。
     *
     * @param r 要诊断的结果，可为 null
     * @return 中文说明；无话可说时返回空串
     */
    public String diagnose(Result r) {
        if (r == null) {
            return "";
        }
        String err = (r.stderrText() + "\n" + r.stdoutText()).toLowerCase();
        if (r.timedOut) {
            return "Connection or command timed out after "
                    + (target.timeoutSeconds > 0 ? target.timeoutSeconds : SshTarget.DEFAULT_TIMEOUT_SECONDS)
                    + " seconds. Check that the VM is powered on and reachable; if the task is genuinely long, raise the timeout.";
        }
        if (err.contains("connection refused")) {
            return "The target host refused the connection: the VM is up, but the SSH service (sshd) is not listening on port "
                    + effectivePort() + ". Run "
                    + "sudo systemctl start ssh inside the VM (on Ubuntu it may be sshd instead), and make sure the firewall allows it.";
        }
        if (err.contains("connection timed out") || err.contains("operation timed out")) {
            return "Connection timed out - the host is unreachable. Check that the VM's network mode (NAT/bridged) lets the host reach it, "
                    + "and that the IP is correct. In bridged mode the VM must be on the same subnet as the host.";
        }
        if (err.contains("permission denied (publickey")) {
            return "Public key authentication was rejected. Possible causes: the public key was not appended to the VM's ~/.ssh/authorized_keys; "
                    + "the wrong private key was selected; or the permissions inside the VM are wrong (~/.ssh should be 700, authorized_keys should be 600). "
                    + "Run ssh-copy-id on the host, or append the public key manually.";
        }
        if (err.contains("host key verification failed")) {
            return "Host key verification failed: this IP previously belonged to a different machine (common after rebuilding a VM). "
                    + "Remove the stale entry from known_hosts on the host, for example by running ssh-keygen -R "
                    + safeHost() + ", then try again.";
        }
        if (err.contains("could not resolve hostname")) {
            return "Could not resolve host name: " + safeHost() + ". Use the IP address instead, or check the spelling of the host name.";
        }
        if (err.contains("no such file or directory") && err.contains("ssh")) {
            return "The specified private key file or the ssh program could not be found. Check that the key path exists and that the OpenSSH client is installed on Windows.";
        }
        if (err.contains("no route to host")) {
            return "Network unreachable: there is no route from the host to the VM. Check that the VM's network is connected and that the IP is correct.";
        }
        if (err.contains("too many authentication failures")) {
            return "Too many authentication failures: ssh-agent offered too many keys. Specify the private key explicitly with -i, or remove the stale keys from the agent.";
        }
        if (err.contains("connection closed") || err.contains("connection reset")) {
            return "The connection was closed by the peer, usually rate limiting by sshd around key exchange, or insufficient resources on the VM. Try again shortly.";
        }
        if (r.isRemoteFailure()) {
            String tail = r.stderrText().trim();
            if (tail.isEmpty()) {
                return "The remote command exited with code " + r.exitCode + " but produced no error output.";
            }
            if (tail.length() > 300) {
                tail = tail.substring(0, 300) + " …";
            }
            return "The remote command failed (exit code " + r.exitCode + "): " + tail;
        }
        return "";
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /**
     * 内部捕获实现，允许指定超时（探测用短的，执行用长的）。
     *
     * <p>两个流都交给 {@link ProcessCapture} 排空，本线程只等超时。以前是
     * 「先把 stdout 读到 EOF，再 waitFor(超时)」：远端命令只要不关闭 stdout，
     * 超时那一行就永远执行不到，探测（{@link #probe()} 的 25 秒）会一直挂住。
     */
    private Result runCapture(String script, int timeoutSeconds) {
        Result r = new Result();
        String invalid = validateForRun();
        if (invalid != null) {
            r.stderr.add(invalid);
            return r;
        }
        if (script == null) {
            script = "";
        }
        Process p = null;
        final ProcessCapture.CappedOutput out = new ProcessCapture.CappedOutput();
        final ProcessCapture.CappedOutput err = new ProcessCapture.CappedOutput();
        try {
            String[] cmd = SshCommand.build(target);
            r.commandLine = SshCommand.toDisplayString(cmd);
            p = startProcess(cmd, script);

            int t = timeoutSeconds > 0 ? timeoutSeconds : runTimeoutSeconds;
            ProcessCapture.Outcome o = ProcessCapture.run(p, t,
                    new ProcessCapture.LineSink() {
                        @Override
                        public void line(String text, boolean isError) {
                            out.add(clamp(Shell.stripAnsi(text)));
                        }
                    },
                    new ProcessCapture.LineSink() {
                        @Override
                        public void line(String text, boolean isError) {
                            err.add(clamp(Shell.stripAnsi(text)));
                        }
                    });
            r.stdout.addAll(out.lines());
            r.stderr.addAll(err.lines());
            if (o.timedOut) {
                r.timedOut = true;
                // 捕获/探测路径没有 per-run 结束命令（它不属于一次"运行"），
                // 所以只能如实说远端可能仍在跑。
                r.stderr.add(stopRemoteAndDescribe(t, null));
            } else {
                r.exitCode = o.exitCode;
            }
        } catch (Exception e) {
            r.stderr.add(describeLaunchFailure(e));
        }
        return r;
    }

    /**
     * 在连接参数不完整时给出中文提示。
     *
     * <p>返回而不是抛异常：界面上的「测试连接」可能在用户填到一半时被点到，
     * 抛异常会让整个按钮回调崩掉，返回一条可展示的错误更合适。
     */
    private String validateForRun() {
        String problem = target.validate();
        if (problem != null) {
            return problem;
        }
        return null;
    }

    private Process startProcess(String[] cmd, String script) throws IOException {
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
     * 进程根本没起来时的中文说明。
     *
     * <p>最常见的原因是这台机器没有 OpenSSH 客户端，或被杀软拦下。
     */
    private String describeLaunchFailure(Exception e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) {
            m = e.getClass().getSimpleName();
        }
        return "Could not start the SSH command: " + m
                + ". Check that the OpenSSH client is installed on Windows (Settings > Apps > Optional features), "
                + "and that " + SshCommand.sshExe() + " exists.";
    }

    private int effectivePort() {
        return target.port > 0 ? target.port : SshTarget.DEFAULT_PORT;
    }

    private String safeHost() {
        String h = target.host == null ? "" : target.host.trim();
        return h.isEmpty() ? "(no host)" : h;
    }

    // 原来这里有一个 closeQuietly(Process)：关闭它的 stdout/stderr 两个流。
    // 现在不做了——超时之后 ssh.exe 是被强杀的，从等待线程里 close 管道可能一直
    // 阻塞到远端那条连接真正结束（Windows 上本机实测过同类情形，见 ProcessCapture
    // 的说明）。两个读取线程由 ProcessCapture 管理，是 daemon，读到 EOF 时自己收尾。

    private static String clamp(String s) {
        if (s == null || s.length() <= MAX_LINE_CHARS) {
            return s;
        }
        return s.substring(0, MAX_LINE_CHARS) + " …[line too long, truncated]";
    }
}

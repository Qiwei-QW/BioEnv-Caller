package vmadapt.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 把「点一下按钮就连上虚拟机」这件事编排起来。
 *
 * <p>为什么需要单独一层：SshTransport 只管发命令，它不知道哪些探测是有意义的，
 * 也不知道失败时该建议用户做什么。用户点「测试连接」时期待的是一句人话——
 * 「连不上，sshd 没开」或者「连上了，但这台机器没有 conda」。这个类就是
 * 把一堆原始探测输出翻译成那种结论的地方。
 *
 * <p>它要能区分三种情况，因为它们对应的修法完全不同：
 * <ol>
 *   <li><b>连不上</b>：网络/防火墙/sshd/密钥问题，去虚拟机控制台解决。</li>
 *   <li><b>连上了但没有 bash</b>：极精简的系统（Alpine 类），要装 bash。</li>
 *   <li><b>连上了但没 conda</b>：能干活，但分析环境还没准备好。</li>
 * </ol>
 */
public final class VmConnector {

    private final SshTarget target;
    private final SshTransport transport;

    /**
     * @param target 连接目标
     */
    public VmConnector(SshTarget target) {
        this.target = target == null ? new SshTarget() : target;
        this.transport = new SshTransport(this.target);
    }

    /** 被编排的连接目标。 */
    public SshTarget target() {
        return target;
    }

    /** 本次编排使用的传输层，便于调用方复用同一条配置。 */
    public SshTransport transport() {
        return transport;
    }

    /**
     * 一键连接：探测 → 判断 → 汇总成报告。
     *
     * <p>流程刻意设计成「一次 SSH 连接问完所有问题」。每次握手在虚拟机上
     * 要几百毫秒，分四五次连接会让按钮明显发卡。所以先跑一条聚合探测脚本，
     * 拿到结果后在本地判断，必要时再补一条针对性的命令。
     *
     * <p>本方法永不抛异常：所有失败都变成报告里的 error + advices，
     * 界面可以直接把这两项显示出来。
     *
     * @return 连接报告，永不为 null
     */
    public ConnectionReport connect() {
        ConnectionReport report = new ConnectionReport();

        // 第 0 步：本地就能判断的参数问题，不必浪费一次网络往返。
        String local = target.validate();
        if (local != null) {
            report.ok = false;
            report.error = local;
            report.advices.add("Complete the connection settings and try again.");
            return report;
        }

        // 第 1 步：探测。连不上时这里会拿到 exit=255 和 ssh.exe 的 stderr。
        SshTransport.Result probe = transport.probe();
        if (!probe.ok()) {
            report.ok = false;
            String why = transport.diagnose(probe);
            if (why == null || why.isEmpty()) {
                why = "Could not connect to " + target.toDisplay() + ".";
            }
            report.error = why;
            report.advices.add("Run ip addr inside the VM to confirm its IP, and check that the VM's network is connected.");
            report.advices.add("Confirm that sshd is installed and running inside the VM: sudo apt install openssh-server && sudo systemctl enable --now ssh");
            report.advices.add("Run ssh " + target.toDisplay()
                    + " manually from a host command prompt to reproduce a more detailed error.");
            collectRaw(report, probe);
            return report;
        }

        // 第 2 步：把 KEY=VALUE 形式的探测输出拆成字段。
        parseProbe(probe.stdoutText(), report);

        // 第 3 步：判断 bash。没 bash 的机器我们的脚本一条都跑不了，
        // 这是硬性阻断，必须明确说出来，而不是让后续步骤报奇怪错。
        //
        // 但「探测一点输出都没有」和「有输出、只是没有 bash」是两件不同的事，
        // 修法也完全不同：前者是脚本没送到远端（连接层问题），后者才是系统缺 bash。
        // 早先两种情况共用一句话，结果把一个传输问题报成了「请安装 bash」，
        // 让人照着装了一遍也没用。
        if (report.bashPath == null || report.bashPath.isEmpty()) {
            report.ok = false;
            if (probeReturnedNothing(report)) {
                report.error = "Connected to " + target.toDisplay()
                        + ", but the remote returned no output at all for the probe.";
                report.advices.add("Every probe field came back empty, so the probe script "
                        + "most likely never reached the remote shell.");
                report.advices.add("Run this from a command prompt to check the same path "
                        + "the plugin uses: echo \"printf 'BASH='; command -v bash\" | ssh "
                        + (target.usesKey() ? "-i <key> " : "") + target.toDisplay() + " bash -s");
            } else {
                report.error = "Connected to " + target.toDisplay()
                        + ", but bash was not found on that system.";
                report.advices.add("This plugin runs remote scripts through bash -s, so bash is required.");
                report.advices.add("On Alpine / BusyBox systems, install bash with: apk add bash");
                report.advices.add("On Debian / Ubuntu systems: sudo apt install bash");
            }
            collectRaw(report, probe);
            return report;
        }

        // 第 4 步：判断 conda。缺 conda 不是连接失败——用户可能只是还没装，
        // 所以 ok 仍然为 true，只在 notes 和 advices 里提示。
        if (report.condaPath == null || report.condaPath.isEmpty()) {
            report.notes.add("conda is not present on the remote host; the analysis environment requires Miniconda to be installed inside the VM first.");
            report.advices.add(RemoteSetup.condaInstallGuide());
        } else {
            report.notes.add("conda found: " + report.condaPath);
        }

        if (report.pythonPath != null && !report.pythonPath.isEmpty()) {
            report.notes.add("Python found: " + report.pythonPath);
        }

        report.ok = true;
        report.error = null;
        return report;
    }

    /**
     * 只做连通性探测，不写报告。
     *
     * <p>给「保存前先验证一下」这类轻量场景用。
     *
     * @return 探测是否成功
     */
    public boolean reachable() {
        return transport.probe().ok();
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 探测是否**一个字段都没解析出来**。
     *
     * <p>用来把「脚本没送到远端」和「远端真的没有 bash」分开：前者所有字段全空，
     * 后者至少 kernel / home / pwd 这些与 bash 无关的字段仍有值。
     */
    private static boolean probeReturnedNothing(ConnectionReport report) {
        return isBlank(report.kernel)
                && isBlank(report.kernelRelease)
                && isBlank(report.machine)
                && isBlank(report.homeDir)
                && isBlank(report.pythonPath)
                && isBlank(report.condaPath)
                && isBlank(report.remoteUser)
                && isBlank(report.pwd);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * 解析 {@code KEY=VALUE} 形式的探测输出。
     *
     * <p>只认行首的 KEY=，避免工具输出里恰好包含同样文字时被误认。
     */
    private static void parseProbe(String text, ConnectionReport report) {
        if (text == null) {
            return;
        }
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (value.equals("none") || value.equals("unknown")) {
                value = "";
            }
            if (key.equals("KERNEL")) {
                report.kernel = value;
            } else if (key.equals("RELEASE")) {
                report.kernelRelease = value;
            } else if (key.equals("MACHINE")) {
                report.machine = value;
            } else if (key.equals("HOME")) {
                report.homeDir = value;
            } else if (key.equals("BASH")) {
                report.bashPath = value;
            } else if (key.equals("PYTHON")) {
                report.pythonPath = value;
            } else if (key.equals("CONDA")) {
                report.condaPath = value;
            } else if (key.equals("USER")) {
                report.remoteUser = value;
            } else if (key.equals("PWD")) {
                report.pwd = value;
            } else if (key.equals("OSNAME")) {
                report.osName = value;
            }
        }
        // osName 用内核名兜底，保证报告里这个字段总有话可说。
        if (report.osName == null || report.osName.isEmpty()) {
            report.osName = report.kernel == null ? "" : report.kernel;
        }
    }

    /**
     * 把原始输出附到报告里，方便用户展开看细节。
     *
     * <p>优先取 stderr——连接失败时 ssh.exe 的报错都在那里。但 stderr 为空时
     * 必须改取 stdout：那正是「命令跑成功了、结果却不是我们预期的」这一类，
     * 探测脚本的真面目就在 stdout 里。只收 stderr 会让这种情况彻底黑箱，
     * 用户和开发者都无从判断到底远端回了什么。
     */
    private static void collectRaw(ConnectionReport report, SshTransport.Result r) {
        if (r == null) {
            return;
        }
        appendNonBlank(report, r.stderr);
        if (report.rawErrors.isEmpty()) {
            appendNonBlank(report, r.stdout);
        }
    }

    /** 把非空行追加到 rawErrors。 */
    private static void appendNonBlank(ConnectionReport report, List<String> lines) {
        if (lines == null) {
            return;
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line != null && !line.trim().isEmpty()) {
                report.rawErrors.add(line);
            }
        }
    }

    /**
     * 一次连接的完整结论。
     *
     * <p>字段 public，因为它是纯数据；advices / notes 是 final List，
     * 让调用方可以往里追加而不必替换整个对象。
     */
    public static final class ConnectionReport {
        /** 是否可以开始工作了（连上且有 bash）。 */
        public boolean ok;

        /** 操作系统名，例如 Linux。 */
        public String osName;

        /** 内核名，例如 Linux（uname -s 的原文）。 */
        public String kernel;

        /** 内核版本，例如 5.15.0-91-generic。 */
        public String kernelRelease;

        /** CPU 架构，例如 x86_64。 */
        public String machine;

        /** 远端家目录，例如 /home/ubuntu。 */
        public String homeDir;

        /** 失败原因（中文）；成功时为 null。 */
        public String error;

        /** 远端登录用户名，以 id -un 的结果为准。 */
        public String remoteUser;

        /** 远端当前目录。 */
        public String pwd;

        /** bash 路径；为空表示没有 bash，这是阻断性问题。 */
        public String bashPath;

        /** python 路径；可为空。 */
        public String pythonPath;

        /** conda 路径；可为空，缺失时给安装指引。 */
        public String condaPath;

        /** 面向用户的中文建议，按重要性排序。 */
        public final List<String> advices = new ArrayList<String>();

        /** 中性提示（探测到的环境信息）。 */
        public final List<String> notes = new ArrayList<String>();

        /** ssh.exe 的原始错误行，供排错。 */
        public final List<String> rawErrors = new ArrayList<String>();

        /** 是否连上了但缺 bash。 */
        public boolean missingBash() {
            return bashPath == null || bashPath.isEmpty();
        }

        /** 是否连上了且有 bash，但缺 conda。 */
        public boolean missingConda() {
            return !missingBash() && (condaPath == null || condaPath.isEmpty());
        }

        /**
         * 生成一段可直接显示给用户的多行中文摘要。
         *
         * <p>界面和日志共用同一个渲染，避免两处文字不一致。
         */
        public String summary() {
            StringBuilder sb = new StringBuilder();
            if (ok) {
                sb.append("Connection succeeded.\n");
            } else {
                sb.append("Connection failed.\n");
                if (error != null && !error.isEmpty()) {
                    sb.append(error).append('\n');
                }
            }
            if (osName != null && !osName.isEmpty()) {
                sb.append("System: ").append(osName);
                if (kernelRelease != null && !kernelRelease.isEmpty()) {
                    sb.append(' ').append(kernelRelease);
                }
                if (machine != null && !machine.isEmpty()) {
                    sb.append(" (").append(machine).append(')');
                }
                sb.append('\n');
            }
            if (homeDir != null && !homeDir.isEmpty()) {
                sb.append("Home directory: ").append(homeDir).append('\n');
            }
            for (int i = 0; i < notes.size(); i++) {
                sb.append("· ").append(notes.get(i)).append('\n');
            }
            for (int i = 0; i < advices.size(); i++) {
                sb.append("Suggestion: ").append(advices.get(i)).append('\n');
            }
            return sb.toString();
        }

        @Override
        public String toString() {
            return "ConnectionReport{ok=" + ok + ", os=" + osName + ", error=" + error + "}";
        }
    }
}

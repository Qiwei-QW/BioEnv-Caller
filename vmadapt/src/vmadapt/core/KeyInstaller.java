package vmadapt.core;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 用密码把本机公钥装到远端：一键流程里唯一会改动虚拟机的操作。
 *
 * <h2>为什么单独一个类</h2>
 *
 * <p>这段逻辑原先写在 {@code ConnectionPanel} 的一个私有方法里。那样有两个问题：
 * <ul>
 *   <li><b>测不到。</b>私有方法加上 Swing 字段，测试只能把命令<b>抄一遍</b>再跑——
 *       抄出来的那份和真正执行的那份可以各自漂移，测了等于没测；</li>
 *   <li><b>放错了层。</b>「拼 ssh 命令、用密码认证、判断结果」是纯机制，跟界面无关。</li>
 * </ul>
 *
 * <p>挪到这里之后，关键路径可以对着<b>真实 sshd</b> 跑（见 {@code KeyInstallLiveTest}），
 * 而且界面只负责把结果翻成人话。
 *
 * <h2>密码怎么交给 ssh</h2>
 *
 * <p>走 {@link SshAskpass}：密码经环境变量传给一个辅助 JVM，由它打到 stdout，
 * ssh 通过 {@code SSH_ASKPASS_REQUIRE=force} 去读。<b>绝不放进命令行</b>——同机任何
 * 进程都能通过 {@code Win32_Process.CommandLine} 读到别人的参数；也不落盘、不进日志。
 *
 * <p>这条通道要求 OpenSSH 8.4+（{@code SSH_ASKPASS_REQUIRE} 的下限）。调用方应当先用
 * {@link SshAskpass#supportsAskpassRequire(int, int)} 判断，不合适就别走密码这条路。
 */
public final class KeyInstaller {

    /** 远端命令成功后打出的标记；用标记而不是退出码判断，避免 ssh 自己的告警混进来。 */
    public static final String MARKER = "TBVM_KEY_INSTALLED";

    /** 失败类型。界面据此说不同的话——"密码错"和"网络不通"的下一步完全不同。 */
    public enum Kind {
        /** 成功。 */
        OK,
        /** 认证被拒：密码不对，或该账号不允许密码登录。 */
        AUTH,
        /** 连不上：拒绝连接、超时、主机不可达。 */
        NETWORK,
        /** ssh 可执行文件有问题（找不到、无法启动）。 */
        LOCAL,
        /** 其它：远端命令报错、输出里没有标记。 */
        OTHER
    }

    /** 一次安装的结果。成功时 {@link #reason} 为 null。 */
    public static final class Outcome {

        public final Kind kind;
        /** 人话原因；失败时非 null。已经带上 ssh 的原始输出，便于排查。 */
        public final String reason;
        /** ssh 的原始输出，供诊断与自检打印。 */
        public final String output;

        Outcome(Kind kind, String reason, String output) {
            this.kind = kind;
            this.reason = reason;
            this.output = output == null ? "" : output;
        }

        public boolean ok() {
            return kind == Kind.OK;
        }
    }

    private KeyInstaller() {
    }

    /**
     * 把公钥追加到远端的 {@code ~/.ssh/authorized_keys}。
     *
     * <p>重复安装是安全的：远端用 {@code grep -qxF} 整行比对，已经存在就不再加一遍，
     * 所以不会把同一个 key 写进去两次，也不会动别的登录方式。
     *
     * @param sshExe        ssh.exe 的路径
     * @param host          主机或 IP
     * @param port          SSH 端口
     * @param user          登录名
     * @param publicKey     公钥正文（一行）
     * @param password      一次性密码；装完即弃
     * @param timeoutSec    连接超时秒数
     * @return 结果；{@link Outcome#ok()} 为 true 表示公钥已装上
     */
    public static Outcome install(String sshExe, String host, int port, String user,
                                  String publicKey, String password, int timeoutSec) {
        if (sshExe == null || !new File(sshExe).isFile()) {
            return new Outcome(Kind.LOCAL, "找不到 ssh 可执行文件", "");
        }
        if (publicKey == null || publicKey.trim().isEmpty()) {
            return new Outcome(Kind.LOCAL, "公钥内容为空", "");
        }

        // 远端脚本：建目录、修权限、整行去重后追加。
        //
        // 用 grep -qxF 而不是 grep -q：-x 要求整行相等，-F 按字面匹配，
        // 否则公钥里的正则元字符会被当成模式，`+` `/` 这类字符在 base64 里很常见。
        String key = publicKey.trim();
        String remote = remoteScript(key);

        List<String> cmd = new ArrayList<String>();
        cmd.add(sshExe);
        // 非交互：不要弹任何本地提示。
        cmd.add("-o");
        cmd.add("BatchMode=no");
        cmd.add("-o");
        cmd.add("StrictHostKeyChecking=accept-new");
        cmd.add("-o");
        cmd.add("ConnectTimeout=" + timeoutSec);
        // 只走密码。否则 ssh 会先拿还没装上的公钥试一遍，白等一个来回，
        // 而且失败信息会变成 publickey，掩盖真正的原因。
        cmd.add("-o");
        cmd.add("PreferredAuthentications=password,keyboard-interactive");
        cmd.add("-o");
        cmd.add("NumberOfPasswordPrompts=1");
        cmd.add("-p");
        cmd.add(String.valueOf(port));
        cmd.add(user + "@" + host);
        cmd.add(remote);

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            // 关键：只往环境里放密码，argv 里没有它。
            Map<String, String> env = SshAskpass.environmentFor(
                    System.getenv(), SshAskpass.javaExe(), SshAskpass.classpath(),
                    password);
            pb.environment().clear();
            pb.environment().putAll(env);
            pb.redirectErrorStream(true);

            Process p = pb.start();
            // 原来是把输出读到 EOF 再 waitFor()：ssh 一旦卡住（半开连接、等输入），
            // read 永远不返回，waitFor 根本轮不到，向导就永远停在「正在安装」。
            // 交给 ProcessCapture：两个流各一个读取线程，等待有上限，超时强制结束。
            final ProcessCapture.CappedOutput out = new ProcessCapture.CappedOutput();
            ProcessCapture.Outcome wait = ProcessCapture.run(
                    p, waitLimitSeconds(timeoutSec),
                    new ProcessCapture.LineSink() {
                        @Override
                        public void line(String text, boolean isError) {
                            out.add(text);
                        }
                    }, null);
            String text = joinLines(out.lines());
            if (wait.timedOut) {
                return new Outcome(Kind.NETWORK,
                        "等待 ssh 超过 " + waitLimitSeconds(timeoutSec)
                                + " 秒仍无响应（连接可能已半开）。"
                                + "无法确认公钥是否已装上，请检查网络后重试。",
                        text);
            }
            if (wait.interrupted) {
                return new Outcome(Kind.LOCAL,
                        "安装公钥的操作被中断，未确认公钥是否已装上。", text);
            }
            int code = wait.exitCode;

            if (text.contains(MARKER)) {
                return new Outcome(Kind.OK, null, text);
            }
            return new Outcome(classify(text, code), explain(text, code), text);
        } catch (Exception e) {
            return new Outcome(Kind.LOCAL, "无法启动 ssh：" + e, String.valueOf(e));
        }
    }

    /** 等 ssh 结束时，在连接超时之外额外给的时间（秒）。 */
    public static final int WAIT_MARGIN_S = 5;

    /**
     * 等待 ssh 进程结束的上限：连接超时加上余量。
     *
     * <p>公开是为了让测试能钉住「等待有上限」这件事——本类原来的写法是把输出读到
     * EOF 再 {@code waitFor()}，超时永远不会生效。真正连上之后还要做一次认证并跑
     * 一段远端脚本，所以上限不能恰好等于连接超时。
     *
     * @param timeoutSec 连接超时（秒）
     * @return 等待上限（秒），至少 1 秒
     */
    public static int waitLimitSeconds(int timeoutSec) {
        return Math.max(1, timeoutSec) + WAIT_MARGIN_S;
    }

    /**
     * 组一条远端安装公钥的脚本。
     *
     * <p>公钥正文里可能有单引号（{@code ssh-keygen -C "Alice's laptop"}）或
     * {@code $} 这类字符：直接塞进单引号会让引号提前闭合，远端 bash 报语法错误。
     * 以前的表现是密码明明正确、安装却失败，界面上只有一句笼统的原因。所以两处
     * 出现公钥的地方都走 {@link Shell#quote}。
     *
     * <p>公开是为了能用一条普通测试钉住「引号一定闭合」。把命令的构造放在 core
     * 里，理由和 {@link #verifyCommand} 一样：测试要验的是真正执行的那份脚本，
     * 而不是抄一遍的另一份。
     *
     * @param key 公钥正文（一行）
     * @return 可直接交给 ssh 在远端执行的脚本
     */
    public static String remoteScript(String key) {
        String q = Shell.quote(key);
        return "mkdir -p ~/.ssh && chmod 700 ~/.ssh && "
                + "touch ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys && "
                + "{ grep -qxF " + q + " ~/.ssh/authorized_keys || "
                + "echo " + q + " >> ~/.ssh/authorized_keys; } && "
                + "echo " + MARKER;
    }

    /** 把逐行收集的输出拼回一段文本。 */
    private static String joinLines(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return sb.toString();
    }

    /**
     * 判断失败属于哪一类。
     *
     * <p>分型是为了让界面说对话：密码错了要让他重输，网络不通要让他查网络模式，
     * 合成一句"连接失败"等于把排查工作丢回给用户。
     */
    public static Kind classify(String output, int exitCode) {
        String o = output == null ? "" : output;
        if (o.contains("Permission denied")) {
            return Kind.AUTH;
        }
        if (o.contains("Connection refused")
                || o.contains("Connection timed out")
                || o.contains("No route to host")
                || o.contains("Network is unreachable")
                || o.contains("Connection closed")) {
            return Kind.NETWORK;
        }
        if (o.contains("Host key verification failed")) {
            return Kind.AUTH;
        }
        return Kind.OTHER;
    }

    /** 给失败配一句人话，并附上原始输出。 */
    public static String explain(String output, int exitCode) {
        Kind k = classify(output, exitCode);
        String head;
        switch (k) {
            case AUTH:
                head = "认证被拒绝。密码可能不对，或这个账号不允许用密码登录。";
                break;
            case NETWORK:
                head = "连不上虚拟机。请检查地址、端口和虚拟机的网络模式。";
                break;
            default:
                head = "登录成功但安装公钥的命令报错（ssh 退出码 " + exitCode + "）。";
                break;
        }
        String o = output == null ? "" : output.trim();
        return o.isEmpty() ? head : head + "\n" + o;
    }

    /**
     * 组成"验证免密登录"的 ssh 命令。
     *
     * <h2>为什么这件事也要放在 core</h2>
     *
     * <p>因为这里有一个踩过的坑，而它只会在真机上暴露：我们的密钥叫
     * {@code id_rsa_tbvm}，<b>不在 ssh 的默认密钥名里</b>（{@code id_rsa}、
     * {@code id_ed25519}…）。所以命令里必须显式写 {@code -i <密钥>}，
     * 否则 ssh 根本不会拿它去认证。
     *
     * <p>漏掉 {@code -i} 的表现极难定位：公钥确实装到了虚拟机上（装的那一步自己带了
     * {@code -i}），但验证与随后的连接都失败——用户看到的是"装好了却连不上"。把命令
     * 的构造挪到这里，就能用一条普通测试钉住"有密钥时必须带 {@code -i}"，而不必等到
     * 真机上才发现。
     *
     * @param sshExe    ssh.exe 路径
     * @param host      主机
     * @param port      端口
     * @param user      登录名
     * @param keyPath   私钥路径；为空表示走 ssh 默认密钥名
     * @param timeoutSec 连接超时
     * @return 参数列表，末尾是要在远端执行的命令
     */
    public static List<String> verifyCommand(String sshExe, String host, int port,
                                             String user, String keyPath,
                                             int timeoutSec) {
        List<String> cmd = new ArrayList<String>();
        cmd.add(sshExe);
        cmd.add("-o");
        cmd.add("BatchMode=yes");
        cmd.add("-o");
        cmd.add("StrictHostKeyChecking=accept-new");
        cmd.add("-o");
        cmd.add("ConnectTimeout=" + timeoutSec);
        if (keyPath != null && !keyPath.trim().isEmpty()) {
            cmd.add("-i");
            cmd.add(keyPath.trim());
        }
        cmd.add("-p");
        cmd.add(String.valueOf(port));
        cmd.add(user + "@" + host);
        cmd.add("echo " + LOGIN_MARKER + "; uname -sr");
        return cmd;
    }

    /** 验证命令成功时远端打出的标记。 */
    public static final String LOGIN_MARKER = "TBVM_OK";

    /**
     * 取私钥对应的公钥正文（一行，带注释）。
     *
     * <h2>为什么不能只读 .pub</h2>
     *
     * <p>正常情况 {@code ssh-keygen} 会同时写出私钥和 {@code .pub}，读那个文件就够了。
     * 但有三种情形会让 {@code .pub} 不可用：
     *
     * <ul>
     *   <li><b>用户只有私钥。</b>换了机器、只拷了私钥、或者手动删过 {@code .pub}；
     *       在"我已经有密钥了"的人身上并不少见；</li>
     *   <li><b>ssh-keygen 写不出 .pub。</b>实测在受限权限下（本项目的构建沙箱就是这样）
     *       {@code ssh-keygen} 报
     *       {@code Unable to save public key to ...: Bad file descriptor}：
     *       私钥正常写出来，{@code .pub} 却是 0 字节。普通桌面环境不会这样（用户机器上
     *       那把 {@code id_rsa_tbvm.pub} 就是好的，731 字节），但既然存在这种可能，
     *       就不该把"公钥为空"当成正常结果往下走；</li>
     *   <li><b>.pub 存在但内容是空的</b>（上面那种失败留下的残骸）。</li>
     * </ul>
     *
     * <p>都改用 {@code ssh-keygen -y -f <私钥>} 现推——它把公钥打到 stdout，不依赖
     * 任何文件写入。这是最后一道兜底：公钥拿不到就不该继续，因为把空公钥追加进
     * {@code authorized_keys} 只会让用户以后每次都被拒，且完全看不出原因。
     *
     * @param sshKeygenExe ssh-keygen.exe 的路径；为 null 时只读 .pub
     * @param privKey      私钥文件
     * @return 公钥正文；取不到返回 null
     */
    public static String publicKeyOf(String sshKeygenExe, File privKey) {
        if (privKey == null || !privKey.isFile()) {
            return null;
        }
        // ① 先看 .pub：正常路径，不用起进程。
        File pub = new File(privKey.getAbsolutePath() + ".pub");
        if (pub.isFile()) {
            String text = readAll(pub);
            if (text != null && !text.trim().isEmpty()) {
                return text.trim();
            }
        }
        // ② 从私钥现推。
        if (sshKeygenExe == null || !new File(sshKeygenExe).isFile()) {
            return null;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(sshKeygenExe, "-y", "-f",
                    privKey.getAbsolutePath());
            pb.redirectErrorStream(false);
            Process p = pb.start();
            // 私钥若带口令，ssh-keygen 会来问。没有 stdin 时它会失败而不是挂住，
            // 所以这里显式关掉 stdin，避免"永远转不完的运行中"。
            p.getOutputStream().close();
            String out = drain(p.getInputStream());
            p.waitFor();
            if (p.exitValue() == 0) {
                String key = out.trim();
                if (!key.isEmpty()) {
                    return key;
                }
            }
        } catch (Exception ignored) {
            // 统一返回 null，由调用方报错
        }
        return null;
    }

    /** 读一个文本文件；失败返回 null。 */
    private static String readAll(File f) {
        try {
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                int off = 0;
                while (off < b.length) {
                    int n = in.read(b, off, b.length - off);
                    if (n < 0) {
                        break;
                    }
                    off += n;
                }
            } finally {
                in.close();
            }
            return new String(b, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    /** 读干净子进程输出，避免子进程写满管道后卡住。 */
    public static String drain(InputStream in) {
        StringBuilder sb = new StringBuilder();
        try {
            byte[] b = new byte[1024];
            int n;
            while ((n = in.read(b)) > 0) {
                sb.append(new String(b, 0, n, "UTF-8"));
            }
        } catch (Exception ignored) {
            // 读不到按空输出处理
        }
        return sb.toString();
    }
}

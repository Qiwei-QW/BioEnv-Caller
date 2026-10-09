package vmadapt.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用 {@code SSH_ASKPASS} 给 ssh.exe 提供一次密码。
 *
 * <h2>为什么不直接把密码传给 ssh</h2>
 *
 * <p>因为 ssh.exe <b>没有</b>接受密码参数的选项，而这是刻意的安全设计：命令行在
 * Windows 上任何进程都能通过 {@code Win32_Process.CommandLine} 读到，密码一旦写进
 * 参数就等于公开。临时文件同样不行——其它程序可读，崩溃后还可能残留。
 *
 * <p>唯一安全的路子是 {@code SSH_ASKPASS}：ssh 需要密码时不去问控制台，而是
 * <b>运行我们指定的小程序</b>，读它 stdout 的第一行当密码。密码只经过父子进程之间
 * 那一个管道，不落盘、不进命令行、不进日志。
 *
 * <h2>为什么需要 {@code SSH_ASKPASS_REQUIRE=force}</h2>
 *
 * <p>历史上 {@code SSH_ASKPASS} 只在"没有终端"时才生效（靠 DISPLAY 判断）。Windows
 * 上那个判断不稳，于是 OpenSSH 8.4 加了 {@code SSH_ASKPASS_REQUIRE}：
 * 设成 {@code force} 就<b>无条件</b>用 askpass，不再管有没有终端。
 *
 * <p>这也意味着<b>版本下限是 8.4</b>：更早的版本不认识这个变量，密码通道无法工作。
 * 所以 {@link #supportsAskpassRequire} 必须被调用方先问一次，不支持时要降级到
 * "显示公钥让用户自己去装"，而不是弹一个注定失败的密码框。
 *
 * <h2>辅助程序是什么</h2>
 *
 * <p>askpass 必须是一个<b>可执行程序</b>。这里不额外打包 exe，而是复用当前 JVM：
 * 用同一个 {@code java.exe} 带一个特殊参数启动本类，它检测到该参数就把密码打印到
 * stdout 并立刻退出。见 {@link #askpassMain}。
 *
 * <p>为什么这么绕：插件是往 TBtools 里装的 jar，多带一个 exe 会让打包和签名复杂化，
 * 而 JVM 路径在运行时本来就知道（{@code java.home}），不需要额外配置。
 */
public final class SshAskpass {

    /** 传给子 JVM 的标记参数：带上它就只负责打印密码。 */
    static final String MARKER = "--tbvm-askpass";

    static {
        // 子 JVM 启动时会加载本类（因为它就是 main 类），静态块里就地判断
        // "这是不是一次 askpass 调用"。放在静态块而不是 main 方法，是因为
        // 用本类作 main 时 JVM 会先初始化类再调 main——两处都一样，但静态块
        // 能保证即使有人误改了启动方式也不会漏掉。
        askpassMain(new String[0]);
    }

    /**
     * 真正的入口：由子 JVM 调用（{@code java -cp ... SshAskpass --tbvm-askpass}）。
     *
     * <p>没有这个 main 就起不来——{@code SSH_ASKPASS} 需要一个可执行程序。
     */
    public static void main(String[] args) {
        askpassMain(args);
    }

    /**
     * 密码经环境变量传给辅助进程。
     *
     * <p>环境变量比命令行好：它不出现在 {@code CommandLine} 里。但仍要承认它<b>不是
     * 绝对安全</b>——同机器上的高权限进程理论上可以读另一个进程的环境块。这一点与
     * "用户在终端里手输密码"相比并没有变差，所以可以接受；但不能宣称绝对安全。
     */
    static final String ENV_PASSWORD = "TBVM_ASKPASS_VALUE";

    private SshAskpass() {
    }

    /**
     * 版本是否支持 {@code SSH_ASKPASS_REQUIRE}（8.4 起）。
     *
     * <p>拿不到版本时保守返回 false：宁可降级去走"显示公钥"那条一定可行的路，
     * 也不要弹一个可能无效的密码框。
     */
    public static boolean supportsAskpassRequire(int major, int minor) {
        if (major > 8) {
            return true;
        }
        return major == 8 && minor >= 4;
    }

    /** 从 {@code ssh -V} 的输出里取主版本号，取不到返回 0。 */
    public static int majorOf(String versionText) {
        int[] v = parse(versionText);
        return v[0];
    }

    /** 从 {@code ssh -V} 的输出里取次版本号，取不到返回 0。 */
    public static int minorOf(String versionText) {
        int[] v = parse(versionText);
        return v[1];
    }

    /**
     * 解析 {@code OpenSSH_for_Windows_9.5p2, LibreSSL 3.8.2} 这类字符串。
     *
     * <p>取的是 {@code OpenSSH} 后面第一组数字。不用正则库的完整能力，是因为这里
     * 只需要两个整数，而正则写错时会静默返回错误版本——那会导致"该降级时弹了密码框"。
     */
    private static int[] parse(String versionText) {
        if (versionText == null) {
            return new int[] {0, 0};
        }
        int at = versionText.indexOf("OpenSSH");
        if (at < 0) {
            return new int[] {0, 0};
        }
        int i = at + "OpenSSH".length();
        // 跳到第一段数字
        while (i < versionText.length() && !Character.isDigit(versionText.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < versionText.length() && Character.isDigit(versionText.charAt(i))) {
            i++;
        }
        if (i == start) {
            return new int[] {0, 0};
        }
        int major = Integer.parseInt(versionText.substring(start, i));
        int minor = 0;
        if (i < versionText.length() && versionText.charAt(i) == '.') {
            i++;
            int ms = i;
            while (i < versionText.length() && Character.isDigit(versionText.charAt(i))) {
                i++;
            }
            if (i > ms) {
                minor = Integer.parseInt(versionText.substring(ms, i));
            }
        }
        return new int[] {major, minor};
    }

    /**
     * 组装一次"带密码"的 ssh 调用所需的进程环境。
     *
     * @param base      现有环境（通常 {@code System.getenv()}），不会被修改
     * @param javaExe   {@code java.exe} 的路径，用作 askpass 程序
     * @param classpath 子 JVM 需要能找到本类
     * @param password  这一次要用的密码
     * @return 可直接交给 ProcessBuilder 的环境副本
     */
    public static Map<String, String> environmentFor(Map<String, String> base,
                                                     String javaExe,
                                                     String classpath,
                                                     String password) {
        Map<String, String> env = new HashMap<String, String>();
        if (base != null) {
            env.putAll(base);
        }
        List<String> argv = new ArrayList<String>();
        argv.add(javaExe);
        argv.add("-cp");
        argv.add(classpath);
        argv.add(SshAskpass.class.getName());
        argv.add(MARKER);
        env.put("SSH_ASKPASS", joinForAskpass(argv));
        // force：无条件用 askpass，不去看有没有终端。
        env.put("SSH_ASKPASS_REQUIRE", "force");
        env.put(ENV_PASSWORD, password == null ? "" : password);
        // 有些 ssh 版本在没有 DISPLAY 时会跳过 askpass，给个占位值。
        env.put("DISPLAY", ":0");
        return env;
    }

    /**
     * 把 argv 拼成 ssh 能执行的一行。
     *
     * <p>Windows 上 {@code SSH_ASKPASS} 会被当命令行解析，所以带空格的路径必须加引号
     * （{@code C:\Program Files\...} 很常见，漏了引号就会解析成 {@code C:\Program}）。
     */
    private static String joinForAskpass(List<String> argv) {
        StringBuilder sb = new StringBuilder();
        for (String a : argv) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(quote(a));
        }
        return sb.toString();
    }

    private static String quote(String s) {
        if (s == null) {
            return "\"\"";
        }
        if (s.indexOf(' ') < 0 && s.indexOf('\t') < 0) {
            return s;
        }
        return "\"" + s.replace("\"", "") + "\"";
    }

    /**
     * 辅助进程的入口：把密码写到 stdout 就退出。
     *
     * <p>只有被 {@link SshAskpass} 以 {@link #MARKER} 启动时才会走到这里，
     * 正常加载本类不会触发——所以它不会影响插件进程。
     *
     * <h2>stdout 必须只有密码</h2>
     *
     * <p>ssh 读的是 stdout 的<b>第一行</b>。所以这个进程绝不能往 stdout 打任何别的
     * 东西——一句日志、一个警告，都会让密码变成那句日志，然后认证失败，而用户看到
     * 的只是"密码错误"，无从排查。
     *
     * <p>一个真实存在的干扰源是 {@code JAVA_TOOL_OPTIONS}：一旦用户设了它，JVM 启动时
     * 会打 "Picked up JAVA_TOOL_OPTIONS: ..."。实测那条横幅走的是 <b>stderr</b>，
     * 不会污染 stdout；但这里仍然只写密码、不写任何其它内容，因为这个前提一旦变了
     * （比如某个 JVM 版本改了行为），故障会非常难查。
     *
     * @return true 表示这次启动是"当 askpass 用"，调用方应立刻退出
     */
    public static boolean askpassMain(String[] args) {
        boolean isAskpass = false;
        for (String a : args) {
            if (MARKER.equals(a)) {
                isAskpass = true;
                break;
            }
        }
        if (!isAskpass) {
            return false;
        }
        // 自检留痕：设置了标记路径就把"被调用过"写下来。
        // 生产路径不会设这个变量，所以不影响正常使用。
        String mark = System.getenv("TBVM_ASKPASS_MARK");
        if (mark != null && !mark.trim().isEmpty()) {
            try {
                OutputStream mf = new FileOutputStream(mark.trim());
                mf.write(("called with " + (System.getenv(ENV_PASSWORD) == null
                        ? "(no value)" : "(a value)")).getBytes("UTF-8"));
                mf.close();
            } catch (Exception ignored) {
                // 留痕失败不影响这次 askpass 的本职工作
            }
        }
        String value = System.getenv(ENV_PASSWORD);
        try {
            // 直接写字节，不经过 println（它会按平台加换行并涉及编码）。
            // ssh 只读第一行，末尾的换行是必须的。
            OutputStream out = new FileOutputStream(FileDescriptorOut());
            out.write((value == null ? "" : value).getBytes("UTF-8"));
            out.write('\n');
            out.flush();
            out.close();
        } catch (Exception ignored) {
            // 写不出去等于密码为空，ssh 会认证失败并正常报错。
        }
        System.exit(0);
        return true;
    }

    /** stdout 的文件描述符，单独抽出来是为了让 catch 分支也能用到。 */
    private static java.io.FileDescriptor FileDescriptorOut() {
        return FileDescriptorHolder.OUT;
    }

    /** 延迟持有 stdout 描述符，避免类初始化顺序问题。 */
    private static final class FileDescriptorHolder {
        static final java.io.FileDescriptor OUT = java.io.FileDescriptor.out;
    }

    /**
     * 自检用：起一次 ssh，让 askpass 被调用，并把结果写进一个标记文件。
     *
     * <p>不是生产路径。放在这里而不是测试里，是因为它需要用同一个 JVM 启动方式
     * 来构造 askpass 命令行——那正是要验证的东西。
     *
     * <p><b>必须连一个真的在监听、且启用了密码认证的 sshd。</b>曾经这里连的是一个
     * 没人听的端口，结果在 TCP 阶段就 Connection refused，ssh 走不到认证，
     * askpass 自然不会被调用——探针报 FAIL，看着像机制失灵，其实是探针没测到点子上。
     *
     * @param sshExe ssh.exe 路径
     * @param marker 收到调用时由辅助进程写下的标记文件
     * @param host   一台启用了密码认证的 sshd 主机
     * @param port   它的端口
     * @param user   登录名（密码会是错的，这里只验证 askpass 被调用）
     */
    public static void selftest(String sshExe, File marker, String host,
                                String port, String user) {
        try {
            List<String> cmd = new ArrayList<String>();
            cmd.add(sshExe);
            cmd.add("-o");
            cmd.add("BatchMode=no");
            cmd.add("-o");
            cmd.add("ConnectTimeout=5");
            cmd.add("-o");
            cmd.add("StrictHostKeyChecking=no");
            cmd.add("-o");
            cmd.add("UserKnownHostsFile=" + File.createTempFile("tbvm-kh", ".txt")
                    .getAbsolutePath());
            // 只要密码认证：免得 ssh 先试公钥、再试别的，把这一步绕过去。
            cmd.add("-o");
            cmd.add("PreferredAuthentications=password");
            cmd.add("-o");
            cmd.add("NumberOfPasswordPrompts=1");
            cmd.add("-p");
            cmd.add(port);
            cmd.add(user + "@" + host);
            cmd.add("true");

            Map<String, String> env = environmentFor(System.getenv(),
                    javaExe(), classpath(), "TBVM_PROBE_DELIBERATELY_WRONG");
            env.put("TBVM_ASKPASS_MARK", marker.getAbsolutePath());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().clear();
            pb.environment().putAll(env);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            drain(p.getInputStream());
            p.waitFor();
        } catch (Exception e) {
            System.out.println("  (selftest 异常: " + e + ")");
        }
    }

    private static void drain(java.io.InputStream in) {
        try {
            byte[] buf = new byte[1024];
            while (in.read(buf) >= 0) {
                // 丢弃：这里只关心 askpass 有没有被调用
            }
        } catch (Exception ignored) {
            // 读不到就算了
        }
    }

    /**
     * 跑 {@code ssh -V} 取版本字符串，例如
     * {@code OpenSSH_for_Windows_9.5p2, LibreSSL 3.8.2}。
     *
     * <p>取不到返回空串。调用方据此判断"要不要降级"——拿不到版本时保守当作不支持，
     * 宁可走那条一定可行的手动路径，也不弹一个可能无效的密码框。
     */
    public static String versionOf(String sshExe) {
        if (sshExe == null) {
            return "";
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(sshExe, "-V");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            byte[] b = new byte[256];
            int n = p.getInputStream().read(b);
            p.waitFor();
            return n <= 0 ? "" : new String(b, 0, n, "UTF-8").trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** 当前 JVM 的 java.exe，用作 askpass 程序。自检也要用它，所以公开。 */
    public static String javaExe() {
        String home = System.getProperty("java.home");
        if (home == null || home.trim().isEmpty()) {
            return "java";
        }
        File f = new File(home, "bin" + File.separator + "java.exe");
        return f.isFile() ? f.getAbsolutePath() : "java";
    }

    /**
     * 供子 JVM 定位本类用的 classpath。
     *
     * <h2>为什么不能直接用 {@code java.class.path}</h2>
     *
     * <p>因为插件是跑在 <b>TBtools 的进程里</b>的，而 TBtools 的 classpath 是它自己的
     * jar 列表，不含 BioEnvCaller.jar——实测它启动时的 classpath 是：
     *
     * <pre>
     *   D:/TBtoolsDir/TBtools_JRE1.6.jar;...;D:\TBtools\.\win64\*
     * </pre>
     *
     * <p>插件由 TBtools 自己的类加载器装入，因此 {@code java.class.path} 里根本没有
     * 插件类所在的 jar。拿它去起子进程会得到：
     *
     * <pre>
     *   错误: 找不到或无法加载主类 vmadapt.core.SshAskpass
     *   原因: java.lang.ClassNotFoundException
     * </pre>
     *
     * <p>这一条是实测出来的，不是推断：模拟 TBtools 的 classpath 起子进程，确实
     * ClassNotFound。而它失败的表现是"密码框输完，报密码错误"——用户会以为是自己
     * 打错了，几乎不可能联想到类路径。所以必须从<b>本类自己的来源</b>推导：
     * {@link java.security.CodeSource} 给的是本类实际被加载的位置，无论谁加载的都对。
     *
     * <p>三种情形：
     * <ul>
     *   <li>本类在 jar 里 → 返回该 jar 的路径；</li>
     *   <li>本类在目录里（开发/自检）→ 返回该目录；</li>
     *   <li>取不到来源 → 退回 {@code java.class.path}，聊胜于无。</li>
     * </ul>
     *
     * @return 可直接交给子 JVM 的 {@code -cp} 值
     */
    public static String classpath() {
        try {
            java.security.CodeSource cs = SshAskpass.class.getProtectionDomain()
                    .getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                File f = new File(cs.getLocation().toURI());
                if (f.exists()) {
                    return f.getAbsolutePath();
                }
            }
        } catch (Exception ignored) {
            // 安全管理器拒绝、URI 不合法等都退回下面的兜底
        }
        String cp = System.getProperty("java.class.path");
        return cp == null ? "" : cp;
    }
}

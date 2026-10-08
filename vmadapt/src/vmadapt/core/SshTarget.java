package vmadapt.core;

import java.io.File;

/**
 * 一台可以通过 ssh.exe 连接的目标虚拟机的连接参数。
 *
 * <p>为什么做成「值对象」而不是散落的几个字段：连接参数在项目里要经过
 * 界面表单 → 配置文件的 JSON → 命令行参数 → ssh.exe 四道转换。任何一道
 * 各自解析 host/port/user/key，都会让「密码里带空格」「端口没填」「密钥路径
 * 带中文」这类问题反复出现。集中在一个类里，前端只需要拿这个对象传递。
 *
 * <p>本类刻意不含 password 字段。我们走的是公钥认证（ssh.exe 的
 * BatchMode=yes 会禁止一切交互式提问），所以密码根本没有存在的地方，
 * 也就不可能被误写进配置文件。
 *
 * <p>字段全部 public：这是一个纯数据载体，调用方直接读写比一堆 getter/setter
 * 更省事，也让其它队友的代码少写样板。label 是给界面显示用的别名，允许为空。
 */
public final class SshTarget {

    /** 默认 SSH 端口。绝大多数 Linux 虚拟机都是 22。 */
    public static final int DEFAULT_PORT = 22;

    /** 默认连接超时（秒）。太短会把慢速虚拟机误判为不可达。 */
    public static final int DEFAULT_TIMEOUT_SECONDS = 15;

    /** 主机名或 IP，例如 192.168.1.100。 */
    public String host;

    /** SSH 端口；非正数一律按 {@link #DEFAULT_PORT} 处理。 */
    public int port;

    /** 登录用户名，例如 ubuntu、root。 */
    public String user;

    /** 私钥文件路径（Windows 本地路径）。为空表示交给 ssh.exe 的默认密钥。 */
    public String keyPath;

    /** 显示用别名，例如「测序分析机」。允许为空。 */
    public String label;

    /** 连接超时秒数；非正数一律按 {@link #DEFAULT_TIMEOUT_SECONDS} 处理。 */
    public int timeoutSeconds;

    /**
     * 完整构造。这是与其它模块约定的主构造签名，不要改。
     *
     * @param host    主机名或 IP
     * @param port    SSH 端口，非正数回退到 22
     * @param user    登录用户名
     * @param keyPath 私钥路径，可为 null
     */
    public SshTarget(String host, int port, String user, String keyPath) {
        this.host = host == null ? "" : host.trim();
        this.port = port > 0 ? port : DEFAULT_PORT;
        this.user = user == null ? "" : user.trim();
        this.keyPath = normalizeKeyPath(keyPath);
        this.timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
    }

    /** 默认端口 22 的便捷构造。 */
    public SshTarget() {
        this("", DEFAULT_PORT, "", null);
    }

    // ------------------------------------------------------------------
    // 派生值
    // ------------------------------------------------------------------

    /**
     * 连接目标是否填写完整，并给出人类可读的中文原因。
     *
     * <p>返回 null 表示合法。这样调用方写 {@code if (t.validate() != null)} 一处
     * 就能既判断又拿到提示，不必在界面代码里重复一遍校验规则。
     *
     * @return null 表示合法；否则返回英文错误说明
     */
    public String validate() {
        if (host == null || host.trim().isEmpty()) {
            return "Enter the VM's host name or IP address.";
        }
        String h = host.trim();
        // 常见误填：把 ssh:// 前缀或 user@host 整串粘进来。分开提示比笼统
        // 说「格式错误」更容易让人自己改对。
        if (h.indexOf(' ') >= 0 || h.indexOf('\t') >= 0) {
            return "The host name must not contain spaces: " + h;
        }
        if (h.startsWith("ssh://")) {
            return "Do not include the ssh:// prefix in the host name - enter just the IP or domain, for example 192.168.1.100.";
        }
        if (h.indexOf('@') >= 0) {
            return "Do not include the user name in the host name - split " + h + " into the user name and host fields.";
        }
        if (user == null || user.trim().isEmpty()) {
            return "Enter the login user name, for example ubuntu or root.";
        }
        if (port <= 0 || port > 65535) {
            return "The port must be between 1 and 65535; the current value is " + port + ".";
        }
        if (timeoutSeconds <= 0 || timeoutSeconds > 600) {
            return "The connection timeout must be between 1 and 600 seconds; the current value is " + timeoutSeconds + ".";
        }
        String key = keyPath;
        if (key != null && !key.trim().isEmpty()) {
            File f = new File(key.trim());
            if (!f.isFile()) {
                return "Private key file not found: " + key.trim() + ". Check that the path is correct (use backslashes or forward slashes for Windows paths).";
            }
        }
        return null;
    }

    /**
     * 界面与日志里显示这个连接的一行文字，例如 {@code ubuntu@192.168.1.100:22}。
     *
     * <p>有别名时带上别名，让人一眼认出是哪台机器。
     */
    public String toDisplay() {
        StringBuilder sb = new StringBuilder();
        String l = label == null ? "" : label.trim();
        if (!l.isEmpty()) {
            sb.append(l).append(" (");
        }
        String u = user == null ? "" : user.trim();
        if (!u.isEmpty()) {
            sb.append(u).append('@');
        }
        String h = host == null ? "" : host.trim();
        sb.append(h.isEmpty() ? "(no host)" : h);
        sb.append(':').append(port > 0 ? port : DEFAULT_PORT);
        if (!l.isEmpty()) {
            sb.append(')');
        }
        return sb.toString();
    }

    /**
     * 归一化私钥路径，把 ssh.exe 不认的形式尽量修正回来。
     *
     * <p>为什么需要：用户从资源管理器复制路径时常带首尾引号，WSL 用户习惯
     * 用 {@code /mnt/c/...}，还有人填 {@code ~/.ssh/id_rsa}。ssh.exe 不认
     * 后两种写法，但都能机械地翻译成 Windows 路径，翻译比报错友好得多。
     *
     * @param raw 原始路径，可为 null
     * @return 归一化后的路径，null 或空串返回 null
     */
    public static String normalizeKeyPath(String raw) {
        if (raw == null) {
            return null;
        }
        String p = raw.trim();
        if (p.isEmpty()) {
            return null;
        }
        // 去掉用户粘贴时带上的成对引号。
        if (p.length() >= 2) {
            char c0 = p.charAt(0);
            char c1 = p.charAt(p.length() - 1);
            if ((c0 == '"' && c1 == '"') || (c0 == '\'' && c1 == '\'')) {
                p = p.substring(1, p.length() - 1).trim();
                if (p.isEmpty()) {
                    return null;
                }
            }
        }
        // WSL 风格挂载点 → Windows 盘符路径。
        if (p.length() > 6 && p.startsWith("/mnt/") && p.charAt(5) != '/') {
            char drive = Character.toUpperCase(p.charAt(5));
            String rest = p.substring(6);
            if (rest.startsWith("/")) {
                rest = rest.substring(1);
            }
            return drive + ":\\" + rest.replace('/', '\\');
        }
        // ~ 只能展开到当前用户的 .ssh 目录；这是 ssh.exe 唯一常见的家目录用法。
        if (p.equals("~") || p.startsWith("~/") || p.startsWith("~\\")) {
            String home = System.getProperty("user.home");
            if (home != null && !home.trim().isEmpty()) {
                String tail = p.length() > 2 ? p.substring(2) : "";
                tail = tail.replace('/', '\\');
                if (tail.startsWith("\\")) {
                    tail = tail.substring(1);
                }
                return home + "\\.ssh\\" + tail;
            }
        }
        return p;
    }

    /**
     * 该目标是否使用公钥认证。
     *
     * <p>没配密钥时仍然允许连接——用户可能已经在 ssh-agent 里加载了密钥，
     * 或者把密钥放在了默认位置。这只是提示信息，不是错误。
     */
    public boolean usesKey() {
        return keyPath != null && !keyPath.trim().isEmpty();
    }

    /** 该目标是否配置了有效密钥文件（文件真实存在）。 */
    public boolean hasUsableKey() {
        if (!usesKey()) {
            return false;
        }
        return new File(keyPath.trim()).isFile();
    }

    @Override
    public String toString() {
        return toDisplay();
    }
}

package vmadapt.core;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 组装 ssh.exe / scp.exe 的命令行参数。
 *
 * <p>为什么集中在这里：Windows 上的命令行引用规则和 bash 完全不同，把
 * 远端脚本拼进命令行会立刻掉进「引号地狱」——脚本里的 {@code "}、{@code $}、
 * 中文路径全都可能被 cmd 或 CreateProcess 改掉。所以本类的策略很硬：
 * <b>脚本一律通过 stdin 传给远端 {@code bash -s}，命令行里只出现主机、端口、
 * 密钥这些短参数</b>。命令行本身用 ProcessBuilder 的列表形式传参，绕开
 * Windows 的字符串拼接。
 *
 * <p>三个 {@code -o} 选项各有用意：
 * <ul>
 *   <li>{@code BatchMode=yes}：禁止一切交互式提问。否则遇到未知 host key 或
 *       需要密码时，ssh.exe 会挂在等待输入的界面上，而我们的进程没有控制台，
 *       表现为「卡死」。宁可立刻失败并返回明确错误。</li>
 *   <li>{@code StrictHostKeyChecking=accept-new}：首次连接自动写入
 *       known_hosts，之后仍严格校验。虚拟机重建后 IP 复用是常态，纯
 *       {@code yes} 会让用户每次都手工删 known_hosts，太烦；而 {@code no}
 *       又等于关掉中间人防护。{@code accept-new} 是二者之间正确的折中。</li>
 *   <li>{@code ConnectTimeout=N}：把「网络不通」的等待压到可接受范围，
 *       否则 Windows 默认要等几十秒 TCP 超时。</li>
 * </ul>
 */
public final class SshCommand {

    /** 连接阶段的选项，ssh 与 scp 通用。 */
    private SshCommand() {
    }

    /**
     * 组装 ssh 命令行（用于交互式执行远端脚本）。
     *
     * <p>这就是与其它模块约定的主签名，不要改。命令行末尾固定是
     * {@code bash -s}：脚本由调用方写到进程 stdin。
     *
     * @param t 连接目标，不能为 null
     * @return 可直接交给 ProcessBuilder 的参数数组
     */
    public static String[] build(SshTarget t) {
        return build(t, false);
    }

    /**
     * 组装 ssh 或 scp 的命令行。
     *
     * @param t      连接目标，不能为 null
     * @param forScp true 生成 scp.exe 的固定部分（-P 大写端口号），
     *               false 生成 ssh.exe 的（-p 小写端口号）
     * @return 可直接交给 ProcessBuilder 的参数数组
     */
    public static String[] build(SshTarget t, boolean forScp) {
        if (t == null) {
            throw new IllegalArgumentException("SshTarget must not be null");
        }
        List<String> cmd = new ArrayList<String>();
        cmd.add(exeName(forScp));
        // scp 的端口选项是大写 -P，ssh 是小写 -p；写反了 scp 会把端口当成源文件。
        cmd.add(forScp ? "-P" : "-p");
        cmd.add(String.valueOf(effectivePort(t)));
        cmd.add("-o");
        cmd.add("BatchMode=yes");
        cmd.add("-o");
        cmd.add("StrictHostKeyChecking=accept-new");
        cmd.add("-o");
        cmd.add("ConnectTimeout=" + effectiveTimeout(t));
        if (t.usesKey()) {
            cmd.add("-i");
            cmd.add(t.keyPath.trim());
        }
        if (!forScp) {
            cmd.add("-T");
            cmd.add("-o");
            cmd.add("LogLevel=ERROR");
        }
        cmd.add(userAtHost(t));
        if (!forScp) {
            cmd.add("bash");
            cmd.add("-s");
        }
        return cmd.toArray(new String[0]);
    }

    /**
     * 把一段任意 argv 拼成 ssh 可用的命令数组：先是我们固定的连接选项，
     * 然后把调用方给的内容整体接在后面（例如 {@code bash -lc 'some cmd'}）。
     *
     * <p>保留这个重载是因为命令行构造经常需要临时追加一两个参数。
     * 它同样不把脚本塞进命令行，只接受已经切分好的参数。
     *
     * @param t    连接目标
     * @param args 要追加在主机之后的目标命令参数，可为 null
     * @return 完整参数数组
     */
    public static String[] buildArgs(SshTarget t, List<String> args) {
        if (t == null) {
            throw new IllegalArgumentException("SshTarget must not be null");
        }
        List<String> cmd = new ArrayList<String>();
        cmd.add(exeName(false));
        cmd.add("-p");
        cmd.add(String.valueOf(effectivePort(t)));
        cmd.add("-o");
        cmd.add("BatchMode=yes");
        cmd.add("-o");
        cmd.add("StrictHostKeyChecking=accept-new");
        cmd.add("-o");
        cmd.add("ConnectTimeout=" + effectiveTimeout(t));
        cmd.add("-o");
        cmd.add("LogLevel=ERROR");
        if (t.usesKey()) {
            cmd.add("-i");
            cmd.add(t.keyPath.trim());
        }
        cmd.add("-T");
        cmd.add(userAtHost(t));
        if (args != null) {
            for (int i = 0; i < args.size(); i++) {
                String a = args.get(i);
                if (a != null) {
                    cmd.add(a);
                }
            }
        }
        return cmd.toArray(new String[0]);
    }

    /**
     * 单独取 ssh 可执行文件路径，便于测试和错误提示。
     *
     * @return ssh.exe 的路径（若系统未提供则返回裸名字，由 PATH 解析）
     */
    public static String sshExe() {
        return resolveExe("ssh.exe");
    }

    /**
     * 单独取 scp 可执行文件路径。
     *
     * @return scp.exe 的路径（若系统未提供则返回裸名字，由 PATH 解析）
     */
    public static String scpExe() {
        return resolveExe("scp.exe");
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private static String exeName(boolean forScp) {
        return forScp ? scpExe() : sshExe();
    }

    /**
     * 找到 Windows 自带 OpenSSH 的绝对路径。
     *
     * <p>先查 System32\OpenSSH，再退回 PATH 里的裸名字。用绝对路径是因为
     * TBtools 是以 GUI 进程启动的，它继承的 PATH 未必包含 System32\OpenSSH；
     * 而写死绝对路径又会在精简系统上失效，所以两级回退。
     */
    private static String resolveExe(String name) {
        String sysRoot = System.getenv("SystemRoot");
        if (sysRoot == null || sysRoot.trim().isEmpty()) {
            sysRoot = System.getenv("windir");
        }
        if (sysRoot != null && !sysRoot.trim().isEmpty()) {
            File f = new File(sysRoot.trim() + "\\System32\\OpenSSH\\" + name);
            if (f.isFile()) {
                return f.getAbsolutePath();
            }
        }
        return name;
    }

    private static int effectivePort(SshTarget t) {
        return t.port > 0 ? t.port : SshTarget.DEFAULT_PORT;
    }

    private static int effectiveTimeout(SshTarget t) {
        return t.timeoutSeconds > 0 ? t.timeoutSeconds : SshTarget.DEFAULT_TIMEOUT_SECONDS;
    }

    /** 拼出 {@code user@host}，用户名缺失时只给 host。 */
    private static String userAtHost(SshTarget t) {
        String h = t.host == null ? "" : t.host.trim();
        String u = t.user == null ? "" : t.user.trim();
        return u.isEmpty() ? h : u + "@" + h;
    }

    /**
     * 把命令数组渲染成一行便于阅读和写日志的文字。
     *
     * <p>只用于显示：真正执行时永远走数组形式，不做字符串再解析。
     */
    public static String toDisplayString(String[] cmd) {
        if (cmd == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cmd.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            String a = cmd[i];
            if (a == null) {
                continue;
            }
            if (a.isEmpty() || a.indexOf(' ') >= 0 || a.indexOf('\t') >= 0) {
                sb.append('"').append(a).append('"');
            } else {
                sb.append(a);
            }
        }
        return sb.toString();
    }
}

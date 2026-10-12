package bioenv.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 把一条命令交给<b>真正的终端</b>去跑，而不是在插件面板里跑。
 *
 * <h2>为什么不开一个终端仿真，而是打开一个真终端</h2>
 *
 * <p>交互式工具（PAML 的 {@code evolver} 那种打印菜单等人输数字的）在插件里跑不了：
 * 插件整条运行路径都是非交互的（SSH 下还以 {@code BatchMode=yes} 禁掉一切提问），
 * 而工具停在提示上等输入，没人应答就放弃。
 *
 * <p>要驱动它得分配 PTY、把键盘送进去、还要把 ANSI 转义解释成画面——那是一整套终端
 * 仿真。可用户机器上<b>本来就有</b>终端。所以这里不模拟，只负责<b>拼出启动它的命令</b>，
 * 剩下的交给终端本身：颜色、行编辑、Ctrl-C、全屏程序，全都白送。
 *
 * <h2>三层，从好到保底</h2>
 *
 * <ol>
 *   <li>{@code wt.exe} —— Windows Terminal，体验最好；</li>
 *   <li>{@code cmd /c start} —— 老控制台，一定有；</li>
 *   <li>把命令<b>复制到剪贴板</b>，用户自己粘到任何终端里。零依赖，永不失败。</li>
 * </ol>
 *
 * <p>第三层不是凑数：它任何平台都能用，而前两层在别的平台上根本不存在。
 *
 * <h2>为什么这些是静态纯函数</h2>
 *
 * <p>拼命令行最容易错的地方是<b>引号</b>：命令要穿过 wt.exe、再穿过远端 shell 两层。
 * 拼错了按钮就是个死键，而且不报错。把拼装拆成纯函数，无头环境就能把每一层钉住。
 */
public final class TerminalLaunch {

    private TerminalLaunch() {
    }

    /** 启动方式。 */
    public enum Layer {
        /** Windows Terminal（或平台上的同类）。 */
        TERMINAL,
        /** 老控制台窗口，退路。 */
        CONSOLE,
        /** 不启动，只把命令交出去让用户自己粘。 */
        COPY
    }

    /**
     * POSIX 单引号引用。
     *
     * <p>单引号内一切都失去特殊含义，所以这是唯一能把任意字符串安全交给 shell 的办法。
     * 唯一要处理的是字符串里本身的 {@code '}：收尾、转义一个、再开引号。
     */
    public static String posixQuote(String s) {
        if (s == null) {
            return "''";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('\'');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') {
                sb.append("'\\''");
            } else {
                sb.append(c);
            }
        }
        sb.append('\'');
        return sb.toString();
    }

    /**
     * 让远端把这次会话<b>留一份记录</b>。
     *
     * <p>{@code script -qec "<命令>" <记录文件>} 分配一个 PTY 并把整场会话写进文件。
     * 于是两头都有：交互在真终端里进行，跑完插件可以把记录回填到输出面板。
     *
     * <p>{@code -q} 是不打印 {@code script} 自己的开场白，{@code -e} 是把子命令的退出码
     * 原样带回来——少了 {@code -e}，跑失败也看着像成功。
     *
     * <p>{@code script} 在 util-linux 里，Ubuntu 自带；<b>万一没有</b>，用
     * {@link #withoutTranscript(String)} 退回不记录，别让整件事失败。
     */
    public static String withTranscript(String script, String transcriptPath) {
        return "script -qec " + posixQuote(script) + " " + posixQuote(transcriptPath);
    }

    /** 不留记录时的命令：原样就是那段脚本。 */
    public static String withoutTranscript(String script) {
        return script == null ? "" : script;
    }

    /**
     * 通道那一层：在远端（WSL 或 SSH）执行一段脚本。
     *
     * @param ssh            true 走 SSH，false 走 WSL
     * @param targetOrDistro SSH 时是 {@code user@host}；WSL 时是发行版名（可为空）
     * @param script         要在远端跑的那段脚本
     */
    public static List<String> channelArgv(boolean ssh, String targetOrDistro,
                                           String script) {
        List<String> argv = new ArrayList<String>();
        if (ssh) {
            argv.add("ssh.exe");
            // -t 分配 PTY：交互式程序要它；不加的话远端工具会以为没有终端，
            // 行为会变（关掉行编辑、改缓冲），正是要避免的那种。
            //
            // 刻意<b>不</b>传 -o BatchMode=yes。那个选项是为了防止无人值守时被密码
            // 提示挂住；而这里用户就坐在终端前，能直接输密码 —— 挂住不再是风险，
            // 反而是"能用密码登录"这个能力。
            argv.add("-t");
            if (targetOrDistro != null && !targetOrDistro.trim().isEmpty()) {
                argv.add(targetOrDistro.trim());
            }
            argv.add(script);
            return argv;
        }
        argv.add("wsl.exe");
        if (targetOrDistro != null && !targetOrDistro.trim().isEmpty()) {
            argv.add("-d");
            argv.add(targetOrDistro.trim());
        }
        argv.add("--");
        argv.add("bash");
        // -l 让 profile 被读（conda 的初始化常在里面），-c 执行这段脚本。
        argv.add("-lc");
        argv.add(script);
        return argv;
    }

    /**
     * 把通道命令包进某个启动器。
     *
     * @param layer 哪一层；{@link Layer#COPY} 只返回命令本身，不启动任何东西
     */
    public static List<String> launchArgv(Layer layer, List<String> channelArgv) {
        List<String> inner = channelArgv == null
                ? new ArrayList<String>() : channelArgv;
        List<String> argv = new ArrayList<String>();
        if (layer == Layer.COPY) {
            argv.addAll(inner);
            return argv;
        }
        if (layer == Layer.TERMINAL) {
            // wt.exe 把剩下的参数当作"要跑的命令行"。
            argv.add("wt.exe");
            argv.addAll(inner);
            return argv;
        }
        // 老控制台：start 的第一个参数是窗口标题，必须给一个空串占位，
        // 否则 start 会把后面的可执行文件当成标题，然后什么都不开。
        argv.add("cmd.exe");
        argv.add("/c");
        argv.add("start");
        argv.add("");
        argv.addAll(inner);
        return argv;
    }

    /**
     * 人可读的形式，用于复制到剪贴板。
     *
     * <p>每一段按需加引号：命令行里出现空格、引号或 shell 元字符时，不加引号粘出去
     * 就会变形。Windows 的命令行规则（反斜杠 + 双引号）和 POSIX 不同，这里是
     * Windows 侧要粘贴的，所以按 Windows 规则来。
     */
    public static String printable(List<String> argv) {
        if (argv == null || argv.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < argv.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(quoteForWindows(argv.get(i)));
        }
        return sb.toString();
    }

    /** 按 Windows 的命令行规则引用一段参数。 */
    static String quoteForWindows(String s) {
        if (s == null || s.isEmpty()) {
            return "\"\"";
        }
        boolean needs = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '"') {
                needs = true;
                break;
            }
        }
        if (!needs) {
            return s;
        }
        // 反斜杠只在双引号前才需要加倍。多行脚本里换行不特殊，原样保留。
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        int backslashes = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                backslashes++;
                sb.append(c);
                continue;
            }
            if (c == '"') {
                for (int k = 0; k < backslashes; k++) {
                    sb.append('\\');
                }
                sb.append('\\');
                backslashes = 0;
                sb.append(c);
                continue;
            }
            backslashes = 0;
            sb.append(c);
        }
        for (int k = 0; k < backslashes; k++) {
            sb.append('\\');
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * 记录文件的路径：放在工作目录下，用固定名字。
     *
     * <p>固定名而不是时间戳：用户回头要找它，一个能记住的名字比一串数字有用；而且
     * 每次覆盖正好表示"这是最近一次"。
     */
    public static String transcriptPath(String workdir) {
        if (workdir == null || workdir.trim().isEmpty()) {
            return "tbvterm.log";
        }
        String d = workdir.trim();
        return d.endsWith("/") ? d + "tbvterm.log" : d + "/tbvterm.log";
    }
}

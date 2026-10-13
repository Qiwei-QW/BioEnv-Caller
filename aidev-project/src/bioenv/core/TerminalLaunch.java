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

    /**
     * 整场会话的命令：跑脚本 + 留记录 + <b>最后写一个标记文件</b>。
     *
     * <p>标记文件是给插件用的。终端窗口一起来，启动它的进程就返回了，插件<b>无法等待</b>
     * 那场会话结束。所以让会话自己在跑完之后碰一个文件，插件轮询它就等于"等它结束"。
     * 没有这个标记，就只能靠"文件多久没长大"来猜，那是不可靠的。
     */
    public static String sessionScript(String script, String transcriptPath) {
        String log = posixQuote(transcriptPath);
        return withTranscript(script, transcriptPath)
                + " ; touch " + posixQuote(sentinelPath(transcriptPath)) + " 2>/dev/null";
    }

    /** 会话结束标记的路径：记录文件后面加 {@code .done}。 */
    public static String sentinelPath(String transcriptPath) {
        return transcriptPath == null ? ".done" : transcriptPath + ".done";
    }

    /** 读回记录的脚本；文件不在就给空。 */
    public static String readTranscriptScript(String transcriptPath) {
        return "cat " + posixQuote(transcriptPath) + " 2>/dev/null";
    }

    /** 标记文件是否已出现。 */
    public static String sentinelCheckScript(String transcriptPath) {
        return "test -f " + posixQuote(sentinelPath(transcriptPath))
                + " && echo yes || echo no";
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
    /**
     * WSL：在某个发行版里跑脚本文件。
     *
     * <p>{@code --} 之后是给 Linux 的命令，argv 里每个元素都不含空格或引号 ——
     * 这一条是硬要求，见 {@link #sshArgv} 上面那段说明。
     */
    public static List<String> wslArgv(String distro, String scriptPath) {
        List<String> argv = new ArrayList<String>();
        argv.add("wsl.exe");
        if (distro != null && !distro.trim().isEmpty()) {
            argv.add("-d");
            argv.add(distro.trim());
        }
        argv.add("--");
        argv.add("bash");
        argv.add(scriptPath == null ? "" : scriptPath.trim());
        return argv;
    }

    /**
     * SSH：在远端跑脚本文件。
     *
     * <h2>必须和插件自己的连接用同一套选项</h2>
     *
     * <p>用户实测报回来的：终端里连着 {@code 127.0.0.1}，屏幕上停在
     * {@code Are you sure you want to continue connecting (yes/no/[fingerprint])?} ——
     * 而插件自己连同一台机器时从来不问。同一台机器、两种行为，用户只会觉得这是个 bug。
     *
     * <p>差别就在这里：插件自己的连接一律带 {@code StrictHostKeyChecking=accept-new}
     * （见 {@code SshCommand}，注释写明了为什么这也是安全与可用的折中：首次自动写入
     * known_hosts，之后仍严格校验）。这里原来什么都没带，于是 ssh 用了默认的 {@code ask}。
     *
     * <p>端口和密钥同理：{@code userHost()} 只给 {@code user@host}，非 22 端口会连错，
     * 不带 {@code -i} 则不会用配置里那把密钥 —— 终端必须复现插件的连接，否则它跑的是
     * 另一件事。
     *
     * <p><b>刻意不带 {@code BatchMode=yes}</b>：那个选项是为了防止无人值守时被密码提示
     * 挂住，而这里用户就坐在终端前，能直接输密码 —— 挂住不再是风险，反而是能力。
     *
     * @param userHost   {@code user@host}
     * @param port       端口；&lt;= 0 时按 ssh 默认（22）
     * @param keyPath    私钥路径；为空则交给 ssh 自己找
     * @param scriptPath 远端脚本文件
     */
    public static List<String> sshArgv(String userHost, int port, String keyPath,
                                       String scriptPath) {
        List<String> argv = new ArrayList<String>();
        argv.add("ssh.exe");
        // -t 分配 PTY：交互式程序要它；不加的话远端工具会以为没有终端，行为会变
        // （关掉行编辑、改缓冲），正是要避免的那种。
        argv.add("-t");
        argv.add("-o");
        argv.add("StrictHostKeyChecking=accept-new");
        if (port > 0 && port != 22) {
            argv.add("-p");
            argv.add(String.valueOf(port));
        }
        // 密钥路径里若有空格就不带它。
        //
        // argv 里不许出现含空格的元素，否则 wt.exe 重新解析命令行时会把它拆散（实测过，
        // 现象是窗口开出来了却在跑脚本的尾巴）。宁可退回让 ssh 自己找密钥，也不要为了
        // 带上它而破坏这条不变式 —— 路径里有空格的情形极少，而且退回去仍然连得上。
        String key = keyPath == null ? "" : keyPath.trim();
        if (!key.isEmpty() && key.indexOf(' ') < 0 && key.indexOf('"') < 0) {
            argv.add("-i");
            argv.add(key);
        }
        if (userHost != null && !userHost.trim().isEmpty()) {
            argv.add(userHost.trim());
        }
        argv.add("bash");
        argv.add(scriptPath == null ? "" : scriptPath.trim());
        return argv;
    }

    /**
     * 面板内交互：WSL 侧要在插件起的进程里造一个 PTY。
     *
     * <h2>为什么这里反而需要 {@code script}</h2>
     *
     * <p>交给终端窗口时不需要它 —— 那个窗口本身就是终端。但在面板里跑时，进程是插件
     * 起的，stdin/stdout 都是<b>管道</b>。而 stdout 不是终端时，很多程序会切成块缓冲：
     * 程序已经打印了提示、正在等你输入，那几行却<b>憋在缓冲区里</b>，界面上什么都看不到，
     * 输入框也就无从下手。
     *
     * <p>{@code script -qec "<命令>" /dev/null} 一次给两样：它给远端命令分配一个 PTY
     * （于是行缓冲、提示立刻出来），同时把它自己 stdin 的内容转发进那个 PTY
     * （于是我们往管道里写就等于往远端程序的键盘里写）。
     *
     * <p>{@code -q} 不打印开场白，{@code -e} 把子命令的退出码带回来。
     *
     * <p><b>注意这里 "bash /path" 是一个含空格的元素，而这是允许的</b> ——
     * 那个"argv 里不许有空格"的约束只存在于走 {@code wt.exe} 的时候（它会重新解析
     * 命令行）。这里由 {@code ProcessBuilder} 直接起进程，元素原样传下去。两边写反了
     * 都是 bug，所以测试把两种情形分别钉住了。
     */
    public static List<String> wslInteractiveArgv(String distro, String scriptPath) {
        List<String> argv = new ArrayList<String>();
        argv.add("wsl.exe");
        if (distro != null && !distro.trim().isEmpty()) {
            argv.add("-d");
            argv.add(distro.trim());
        }
        argv.add("--");
        argv.add("script");
        argv.add("-qec");
        argv.add("bash " + (scriptPath == null ? "" : scriptPath.trim()));
        argv.add("/dev/null");
        return argv;
    }

    /**
     * 面板内交互：SSH 侧直接用 {@link #sshArgv}，不需要额外包装。
     *
     * <p>{@code ssh -t} 已经在<b>远端</b>分配了 PTY，而 ssh 的本地 stdin 是管道照样能写，
     * 它会把内容转进那个 PTY。所以 {@code sshArgv} 出的命令行在"交给终端窗口"和
     * "在面板里跑"两种用法下是同一份 —— 区别只在谁去启动它、谁去喂输入。
     */
    public static List<String> sshInteractiveArgv(String userHost, int port,
                                                  String keyPath, String scriptPath) {
        return sshArgv(userHost, port, keyPath, scriptPath);
    }

    /**
     * 一次运行要用的临时文件基名：放在 {@code /tmp} 下，<b>不落进用户的工作目录</b>。
     *
     * <p>原来这三个文件（脚本、记录、结束标记）都写在工作目录里，于是用户跑完一看，
     * 目录里除了工具真正的产出，还多了 {@code tbvterm.sh}、{@code tbvterm.log}、
     * {@code tbvterm.log.done}。用户报的正是这个：他在终端里跑同一个工具只得到两个结果
     * 文件，插件跑却多出三个 —— <b>插件的记账不该落在用户的数据目录里</b>。
     *
     * <p>选 {@code /tmp} 而不是 {@code ~/.tbvadapt} 之类的家目录，是因为这里的路径要经
     * shell 引用（见 {@link #posixQuote}，它一律加单引号），而单引号里 {@code ~} 和
     * {@code $HOME} <b>不会展开</b> —— 这正是本项目早先建出过一个名叫 {@code ~} 的目录
     * 的原因。{@code /tmp} 是绝对路径，不需要任何展开，引号加了也照样对。
     *
     * <p>带一段唯一后缀：两次运行互不覆盖；名字里的十六进制来自纳秒，不需要额外查询。
     * 用完不必删 —— 系统会清 {@code /tmp}。
     */
    public static String tempBase() {
        return "/tmp/tbvterm-" + Long.toHexString(System.nanoTime());
    }

    /** 会话脚本文件的路径。 */
    public static String scriptFilePath(String base) {
        String b = (base == null || base.trim().isEmpty()) ? "/tmp/tbvterm" : base.trim();
        return b + ".sh";
    }

    /**
     * 把脚本内容写进远端文件的命令。
     *
     * <p>用 <b>base64</b> 而不是 heredoc：脚本里会出现单引号、美元符、反引号、换行，
     * 任何一层转义写错都会让文件内容悄悄变形，而变形之后的表现是"终端里跑的东西不对"，
     * 极难查。base64 的字母表里没有 shell 元字符，写进去就是写进去。
     */
    public static String writeScriptCommand(String scriptPath, String script) {
        byte[] raw = (script == null ? "" : script)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String b64 = java.util.Base64.getEncoder().encodeToString(raw);
        return "printf '%s' " + posixQuote(b64) + " | base64 -d > "
                + posixQuote(scriptPath);
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
    public static String transcriptPath(String base) {
        String b = (base == null || base.trim().isEmpty()) ? "/tmp/tbvterm" : base.trim();
        return b + ".log";
    }
}

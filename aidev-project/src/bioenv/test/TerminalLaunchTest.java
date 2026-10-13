package bioenv.test;

import java.util.Arrays;
import java.util.List;

import bioenv.core.TerminalLaunch;
import bioenv.core.TerminalLaunch.Layer;

/**
 * 把命令交给真终端去跑时，命令行是怎么拼的。
 *
 * <h2>为什么值得单测</h2>
 *
 * <p>这条命令要穿过两层：先是 {@code wt.exe}（或 {@code cmd /c start}），再是远端的
 * shell。引号少一层、多一层，结果都不是"报错"，而是<b>按钮点了没反应</b>、或者远端
 * 收到一段被截断的脚本。这类错最难查，因为插件这边什么都看不出来。
 *
 * <p>所以把拼装做成纯函数，在这里一条条钉住：引用、三层、两条通道、以及记录文件的
 * 名字。<b>不启动任何进程</b>，也不需要显示器。
 */
public final class TerminalLaunchTest {

    private static int checks;
    private static int failures;

    private TerminalLaunchTest() {
    }

    public static void main(String[] args) {
        System.out.println("=== TerminalLaunchTest ===");
        quoting();
        transcript();
        channels();
        scriptFile();
        interactive();
        layers();
        printing();
        System.out.println(failures == 0
                ? "PASS " + checks + " checks"
                : "FAIL " + failures + " of " + checks);
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------

    private static void quoting() {
        eq("普通字符串被单引号包住", "'abc'", TerminalLaunch.posixQuote("abc"));
        eq("空串是一对空引号", "''", TerminalLaunch.posixQuote(""));
        eq("null 当空串", "''", TerminalLaunch.posixQuote(null));
        // 空格必须被包住，否则远端会把它当成两个参数。
        eq("带空格", "'/home/a b/c'", TerminalLaunch.posixQuote("/home/a b/c"));
        // 字符串里本身的单引号：收尾、转义一个、再开引号。这是唯一正确的写法。
        eq("内部单引号被正确转义", "'it'\\''s'", TerminalLaunch.posixQuote("it's"));
        // shell 元字符在单引号里失去含义，这正是要的效果。
        eq("美元符号不被展开", "'$HOME'", TerminalLaunch.posixQuote("$HOME"));
        eq("反引号不被执行", "'`id`'", TerminalLaunch.posixQuote("`id`"));
    }

    private static void transcript() {
        String cmd = TerminalLaunch.withTranscript("evolver", "/home/qw/x.log");
        eq("记录用的是 script -qec",
                "script -qec 'evolver' '/home/qw/x.log'", cmd);
        // -e 少了的话，子命令失败也会看着像成功。
        check("带上 -e，退出码才会被带回来", cmd.contains("-qec"));
        // 脚本里有单引号时，引用必须仍然成立。
        String tricky = TerminalLaunch.withTranscript("echo 'hi'", "/tmp/t.log");
        check("脚本里的引号被转义", tricky.contains("'\\''"), tricky);
        eq("不留记录时原样返回", "evolver",
                TerminalLaunch.withoutTranscript("evolver"));
        eq("不留记录时 null 变空串", "",
                TerminalLaunch.withoutTranscript(null));

        // 记账文件放 /tmp，不落进用户的工作目录。
        //
        // 用户报的：在终端里跑同一个工具只得到两个结果文件，插件跑却多出
        // tbvterm.sh / tbvterm.log / tbvterm.log.done —— 插件的记账混进了数据目录。
        String base = TerminalLaunch.tempBase();
        check("临时目录在 /tmp 下（不需要 shell 展开任何东西）",
                base.startsWith("/tmp/tbvterm-"), base);
        check("临时基名不含空格或 ~（引号加了也照样对）",
                base.indexOf(' ') < 0 && base.indexOf('~') < 0, base);
        check("两次运行不会撞名",
                !base.equals(TerminalLaunch.tempBase()), base);
        eq("脚本文件带 .sh", base + ".sh", TerminalLaunch.scriptFilePath(base));
        eq("记录文件带 .log", base + ".log", TerminalLaunch.transcriptPath(base));
        eq("结束标记在记录后面加 .done", base + ".log.done",
                TerminalLaunch.sentinelPath(TerminalLaunch.transcriptPath(base)));
        // 空基名也要给一个合法的绝对路径，不能退回相对名（那会落进工作目录）。
        check("空基名退回 /tmp 下的绝对路径",
                TerminalLaunch.scriptFilePath("").startsWith("/tmp/"),
                TerminalLaunch.scriptFilePath(""));
    }

    private static void channels() {
        // WSL：走 wsl.exe，读 profile（-l），再执行脚本文本。
        List<String> wsl = TerminalLaunch.wslArgv("Ubuntu", "/home/qw/shili/tbvterm.sh");
        check("WSL 用 wsl.exe", wsl.get(0).equals("wsl.exe"), wsl.toString());
        check("WSL 指定发行版", wsl.contains("Ubuntu"), wsl.toString());
        check("WSL 末尾用 bash 跑脚本文件",
                wsl.get(wsl.size() - 2).equals("bash")
                        && wsl.get(wsl.size() - 1).equals("/home/qw/shili/tbvterm.sh"),
                wsl.toString());
        check("空发行版就不传 -d",
                !TerminalLaunch.wslArgv("", "x.sh").contains("-d"));
        noSpaces("WSL", wsl);

        // SSH：这里几条是用户实测报回来的，一条都不能少。
        List<String> ssh = TerminalLaunch.sshArgv("qw@10.0.0.5", 0, null, "/x/tbvterm.sh");
        check("SSH 用 ssh.exe", ssh.get(0).equals("ssh.exe"), ssh.toString());
        check("SSH 带 -t 分配 PTY", ssh.contains("-t"), ssh.toString());
        // 关键：插件自己的连接一律带 accept-new（见 SshCommand），终端必须一样。
        // 少了它 ssh 用默认的 ask，用户就会看到"确认主机指纹"的提问，而插件那边不会 ——
        // 同一台机器两种行为。实测截图里停在的正是那句 Are you sure you want to continue。
        check("SSH 带 StrictHostKeyChecking=accept-new",
                ssh.contains("StrictHostKeyChecking=accept-new"), ssh.toString());
        check("SSH 的 -o 和它的值是分开的两个元素",
                ssh.indexOf("-o") >= 0
                        && ssh.get(ssh.indexOf("-o") + 1)
                                .equals("StrictHostKeyChecking=accept-new"),
                ssh.toString());
        check("SSH 带目标主机", ssh.contains("qw@10.0.0.5"), ssh.toString());
        check("SSH 末尾用 bash 跑脚本文件",
                ssh.get(ssh.size() - 2).equals("bash")
                        && ssh.get(ssh.size() - 1).equals("/x/tbvterm.sh"),
                ssh.toString());
        check("刻意不带 BatchMode（用户能在终端里输密码）",
                !ssh.toString().contains("BatchMode"), ssh.toString());
        noSpaces("SSH", ssh);

        // 非 22 端口：不带的话终端会连到 22，连的是"另一台"。
        List<String> withPort =
                TerminalLaunch.sshArgv("qw@h", 2222, null, "/x.sh");
        check("非 22 端口带上 -p",
                withPort.indexOf("-p") >= 0
                        && withPort.get(withPort.indexOf("-p") + 1).equals("2222"),
                withPort.toString());
        check("22 端口不必写出来",
                !TerminalLaunch.sshArgv("qw@h", 22, null, "/x.sh").contains("-p"));
        noSpaces("带端口", withPort);

        // 密钥：不带 -i 就不会用配置里那把钥匙。
        List<String> withKey = TerminalLaunch.sshArgv("qw@h", 0,
                "C:\\Users\\q\\.ssh\\id_ed25519", "/x.sh");
        check("带 -i 指定密钥",
                withKey.indexOf("-i") >= 0
                        && withKey.get(withKey.indexOf("-i") + 1)
                                .endsWith("id_ed25519"),
                withKey.toString());
        noSpaces("带密钥", withKey);

        // 密钥路径含空格时宁可不带，也不能破坏"没有元素含空格"这条不变式。
        List<String> spacedKey = TerminalLaunch.sshArgv("qw@h", 0,
                "C:\\Program Files\\k\\id", "/x.sh");
        check("密钥路径含空格就不带 -i（不变式优先）",
                !spacedKey.contains("-i"), spacedKey.toString());
        noSpaces("密钥含空格", spacedKey);
    }

    /**
     * argv 里不许出现含空格或双引号的元素。
     *
     * <p>这是实测逼出来的不变式：{@code wt.exe} 会重新解析命令行，带空格的那一段会被
     * 拆散 —— 现象是窗口开出来了，跑的却是脚本的尾巴。钉住这条性质，比钉某个具体拼法
     * 更能防住以后改坏。
     */
    private static void noSpaces(String what, List<String> argv) {
        for (int i = 0; i < argv.size(); i++) {
            String a = argv.get(i);
            check(what + " 第 " + i + " 个参数不含空格/引号",
                    a.indexOf(' ') < 0 && a.indexOf('"') < 0, a);
        }
    }

    /** 脚本走文件：路径怎么算、内容怎么写进去。 */
    private static void scriptFile() {
        // 这三个原来断言"脚本落在工作目录里"，而那正是用户不要的：
        // 插件的记账混进了数据目录。路径现在一律在 /tmp 下，见上面的检查。

        // base64：脚本里那些单引号、美元符、换行都不能原样进 shell。
        String cmd = TerminalLaunch.writeScriptCommand("/home/qw/x.sh",
                "echo 'hi' $HOME\nnext");
        check("写入用 base64 -d", cmd.contains("base64 -d"), cmd);
        check("重定向到脚本文件", cmd.contains("> '/home/qw/x.sh'"), cmd);
        // 编码后的载荷里不该出现脚本原文的任何片段——出现了就说明没编码。
        check("载荷里不含脚本原文", !cmd.contains("$HOME"), cmd);
        // 反过来：解出来必须和原文一模一样。
        // 载荷在倒数第二个引号对里：从 " | base64" 往前找那对引号。
        int end = cmd.indexOf("' |");
        int start = cmd.lastIndexOf("'", end - 1);
        String b64 = cmd.substring(start + 1, end);
        String back = new String(java.util.Base64.getDecoder().decode(b64),
                java.nio.charset.StandardCharsets.UTF_8);
        eq("解回来和原文一致", "echo 'hi' $HOME\nnext", back);
    }

    /** 面板内交互：WSL 要多一层 script（造 PTY），SSH 复用 sshArgv。 */
    private static void interactive() {
        List<String> w = TerminalLaunch.wslInteractiveArgv("Ubuntu",
                "/home/qw/shili/tbvterm.sh");
        check("交互用 wsl.exe", w.get(0).equals("wsl.exe"), w.toString());
        check("交互指定发行版", w.contains("Ubuntu"), w.toString());
        // 这一条是核心：没有 script 就没有 PTY，远端程序会块缓冲，提示憋在缓冲区里。
        check("交互带 script 造 PTY", w.contains("script"), w.toString());
        check("交互用 -qec", w.contains("-qec"), w.toString());
        check("交互把记录丢到 /dev/null（输出由插件自己收）",
                w.get(w.size() - 1).equals("/dev/null"), w.toString());
        check("交互把 'bash 脚本路径' 作为一个元素传给 script",
                w.contains("bash /home/qw/shili/tbvterm.sh"), w.toString());

        // 这一条<b>反过来</b>钉：这里允许元素含空格。
        //
        // 走 wt.exe 时不允许（它会重新解析命令行，带空格的元素会被拆散 —— 实测过）。
        // 而这里由 ProcessBuilder 直接起进程，元素原样传下去。两边的规矩相反，写反了
        // 都是 bug，所以把"这一侧允许"也写成断言，免得以后有人照着另一侧"修正"过来。
        boolean hasSpace = false;
        for (int i = 0; i < w.size(); i++) {
            if (w.get(i).indexOf(' ') >= 0) {
                hasSpace = true;
            }
        }
        check("交互这一侧允许元素含空格（与终端那一侧相反）", hasSpace, w.toString());

        // SSH 侧复用同一份命令行：-t 已经在远端给了 PTY。
        List<String> s = TerminalLaunch.sshInteractiveArgv("qw@h", 2222,
                "C:\\k\\id", "/x/tbvterm.sh");
        check("交互 SSH 与终端 SSH 是同一份",
                s.equals(TerminalLaunch.sshArgv("qw@h", 2222, "C:\\k\\id",
                        "/x/tbvterm.sh")),
                s.toString());
        check("交互 SSH 带 -t（远端 PTY）", s.contains("-t"), s.toString());
        check("交互 SSH 也带 accept-new", s.contains("StrictHostKeyChecking=accept-new"),
                s.toString());
    }

    private static void layers() {
        List<String> inner = TerminalLaunch.wslArgv("Ubuntu", "/x/tbvterm.sh");

        List<String> wt = TerminalLaunch.launchArgv(Layer.TERMINAL, inner);
        check("第一层用 wt.exe", wt.get(0).equals("wt.exe"), wt.toString());
        check("第一层后面原样接通道命令",
                wt.subList(1, wt.size()).equals(inner), wt.toString());

        List<String> con = TerminalLaunch.launchArgv(Layer.CONSOLE, inner);
        check("第二层用 cmd.exe /c start",
                con.get(0).equals("cmd.exe") && con.get(1).equals("/c")
                        && con.get(2).equals("start"), con.toString());
        // start 的第一参数是窗口标题；不给空串的话它会把它当成标题然后什么都不开。
        check("第二层给了空标题占位", con.get(3).equals(""), con.toString());
        check("第二层后面原样接通道命令",
                con.subList(4, con.size()).equals(inner), con.toString());

        List<String> copy = TerminalLaunch.launchArgv(Layer.COPY, inner);
        check("第三层不启动任何东西，只给出命令",
                copy.equals(inner), copy.toString());

        // 空输入不该抛。
        check("空命令不抛异常",
                TerminalLaunch.launchArgv(Layer.TERMINAL, null).size() == 1);
    }

    private static void printing() {
        // 没有特殊字符就不加引号，读起来干净。
        eq("简单参数不加引号", "wt.exe",
                TerminalLaunch.printable(Arrays.asList("wt.exe")));
        // 带空格要加引号，否则粘出去就变成两个参数。
        check("带空格的参数被引起来",
                TerminalLaunch.printable(Arrays.asList("a b")).equals("\"a b\""),
                TerminalLaunch.printable(Arrays.asList("a b")));
        // 双引号要转义，并且它前面的反斜杠要加倍——Windows 的规则。
        eq("内部双引号被转义", "\"a\\\"b\"",
                TerminalLaunch.printable(Arrays.asList("a\"b")));
        // 反斜杠加倍只发生在<b>引号内部</b>：不带引号的参数末尾一个反斜杠是正常的，
        // 加了引号才需要加倍，否则那个反斜杠会把结尾引号转义掉。
        eq("不带引号时末尾反斜杠原样", "a\\",
                TerminalLaunch.printable(Arrays.asList("a\\")));
        eq("引号内的末尾反斜杠要加倍", "\"a b\\\\\"",
                TerminalLaunch.printable(Arrays.asList("a b\\")));
        eq("空参数给一对空引号", "\"\"",
                TerminalLaunch.printable(Arrays.asList("")));
        eq("空列表给空串", "", TerminalLaunch.printable(null));
    }

    // ------------------------------------------------------------------

    private static void eq(String what, String expected, String actual) {
        check(what + "（期望 " + expected + "，实际 " + actual + "）",
                expected.equals(actual));
    }

    private static void check(String what, boolean ok) {
        check(what, ok, null);
    }

    private static void check(String what, boolean ok, String detail) {
        checks++;
        if (ok) {
            System.out.println("PASS  " + what);
        } else {
            failures++;
            System.out.println("FAIL  " + what + (detail == null ? "" : " — " + detail));
        }
    }
}

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

        eq("没有工作目录时记录文件用相对名", "tbvterm.log",
                TerminalLaunch.transcriptPath(""));
        eq("工作目录下的记录文件名固定", "/home/qw/shili/tbvterm.log",
                TerminalLaunch.transcriptPath("/home/qw/shili"));
        eq("末尾斜杠不会产生双斜杠", "/home/qw/shili/tbvterm.log",
                TerminalLaunch.transcriptPath("/home/qw/shili/"));
    }

    private static void channels() {
        // WSL：走 wsl.exe，读 profile（-l），再执行脚本。
        List<String> wsl = TerminalLaunch.channelArgv(false, "Ubuntu", "evolver");
        check("WSL 用 wsl.exe", wsl.get(0).equals("wsl.exe"), wsl.toString());
        check("WSL 指定发行版", wsl.contains("Ubuntu"), wsl.toString());
        check("WSL 用 -d 传发行版",
                wsl.indexOf("-d") >= 0 && wsl.get(wsl.indexOf("-d") + 1).equals("Ubuntu"),
                wsl.toString());
        check("WSL 读 profile（-lc）", wsl.contains("-lc"), wsl.toString());
        check("WSL 的脚本是最后一个参数",
                wsl.get(wsl.size() - 1).equals("evolver"), wsl.toString());

        // 发行版为空时不该出现一个空的 -d 值。
        List<String> noDistro = TerminalLaunch.channelArgv(false, "", "ls");
        check("空发行版就不传 -d", !noDistro.contains("-d"), noDistro.toString());

        // SSH：-t 分配 PTY，这是交互能不能用的关键。
        List<String> ssh = TerminalLaunch.channelArgv(true, "qw@10.0.0.5", "evolver");
        check("SSH 用 ssh.exe", ssh.get(0).equals("ssh.exe"), ssh.toString());
        check("SSH 带 -t 分配 PTY", ssh.contains("-t"), ssh.toString());
        check("SSH 带目标主机", ssh.contains("qw@10.0.0.5"), ssh.toString());
        check("SSH 的脚本是最后一个参数",
                ssh.get(ssh.size() - 1).equals("evolver"), ssh.toString());
        // BatchMode=yes 会禁止一切提问——那正是交互做不到的原因，这里刻意不带。
        check("刻意不带 BatchMode（用户能在终端里输密码）",
                !ssh.toString().contains("BatchMode"), ssh.toString());
    }

    private static void layers() {
        List<String> inner = TerminalLaunch.channelArgv(false, "Ubuntu", "evolver");

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

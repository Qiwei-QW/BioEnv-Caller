package bioenv.test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import bioenv.core.LinuxDirList;

/**
 * 浏览 Linux 目录时用到的那几个纯函数：脚本生成、输出解析、路径运算。
 *
 * <h2>为什么单独测这些</h2>
 *
 * <p>这个功能真正的难点不在窗口怎么画，而在<b>脚本写对没有、输出怎么解读</b>。那两件事
 * 一旦错了，表现出来是"进去了却列不出东西""列出来的名字带着斜杠""根目录上不去"这类
 * 现象，而且只有连上真机器才看得见。拆成纯函数之后，无头环境就能把边界钉住。
 *
 * <p>尤其要钉的是<b>路径里的引号与 {@code ~}</b>：项目里为此吃过一次亏——写成
 * {@code mkdir -p '~/x'} 时单引号让 {@code ~} 永不展开，建出一个名字叫 {@code ~} 的
 * 目录。这里同样会把路径交给 shell，所以要有断言守着。
 *
 * <p>不需要显示器，也不连任何机器。
 */
public final class LinuxDirListTest {

    private static int checks;
    private static int failures;

    private LinuxDirListTest() {
    }

    public static void main(String[] args) {
        System.out.println("=== LinuxDirListTest ===");

        scripts();
        parsing();
        paths();
        files();

        System.out.println(failures == 0
                ? "PASS " + checks + " checks"
                : "FAIL " + failures + " of " + checks);
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------

    private static void scripts() {
        // ~ 必须交给双引号去展开，不能落进单引号。这是那个"建出 ~ 目录"的旧账。
        String s = LinuxDirList.script("~/data");
        check("~ 展开用的是双引号（$HOME）", s.contains("d=\"$HOME/data\"")
                || s.contains("d=\"$HOME\"/data"), s);
        check("~ 没有被单引号包住", !s.contains("'~/data'"));

        // 空路径当主目录处理，而不是拼出一个空字符串。
        check("空路径当主目录", LinuxDirList.script("").contains("$HOME")
                || LinuxDirList.script("").contains("d=~"));

        // 路径里有空格也要能进去。
        check("带空格的路径被引起来",
                LinuxDirList.script("/home/a b/c").contains("\"/home/a b/c\"")
                        || LinuxDirList.script("/home/a b/c").contains("'/home/a b/c'"));

        // 必须回显解析后的绝对路径（pwd -P 而不是 pwd）。
        check("脚本用 pwd -P 回显", LinuxDirList.script("/x").contains("pwd -P"));

        // 进不去时要有可识别的标记，界面才能说人话。
        check("进不去时输出标记", LinuxDirList.script("/x")
                .contains(LinuxDirList.NO_SUCH_DIR));

        // 只列目录：靠 ls -1p 的 "/" 后缀。
        check("脚本只留目录条目", LinuxDirList.script("/x")
                .contains("grep '/$'"));

        // mkdir 同样要处理 ~。
        String m = LinuxDirList.mkdirScript("~/vmadapt_work/x");
        check("mkdir 也展开 $HOME", m.contains("$HOME/vmadapt_work/x"), m);
        check("mkdir 用 -p（已存在不算失败）", m.contains("mkdir -p"));
        check("空路径的 mkdir 直接失败", LinuxDirList.mkdirScript("").startsWith("exit 1"));
    }

    private static void parsing() {
        // 正常一份输出：第一行是路径，后面是目录名（带 / 后缀）。
        LinuxDirList.Listing ok = LinuxDirList.parse(
                Arrays.asList("/home/you", "data/", "zebra/", "Alpha/"),
                Collections.<String>emptyList(), 0);
        check("解析出路径", "/home/you".equals(ok.path), ok.path);
        check("目录名去掉了 / 后缀", ok.dirs.contains("data"));
        check("目录名里没有残留斜杠",
                !ok.dirs.contains("data/"), ok.dirs.toString());
        check("名字已排序（忽略大小写）",
                Arrays.asList("Alpha", "data", "zebra").equals(ok.dirs),
                ok.dirs.toString());

        // 只有路径、没有子目录 —— 空目录是正常的，不该报错。
        LinuxDirList.Listing empty = LinuxDirList.parse(
                Collections.singletonList("/home/you"),
                Collections.<String>emptyList(), 0);
        check("空目录算成功", empty.ok(), empty.problem);
        check("空目录的列表是空的", empty.dirs.isEmpty());

        // 进不去：脚本把标记写进 stderr。
        LinuxDirList.Listing missing = LinuxDirList.parse(
                Collections.<String>emptyList(),
                Collections.singletonList(LinuxDirList.NO_SUCH_DIR), 3);
        check("进不去时算失败", !missing.ok());
        check("失败原因是一句人话（不是栈）",
                missing.problem.contains("不存在") || missing.problem.contains("权限"),
                missing.problem);

        // 没有任何回显：可能是目录不可读。
        LinuxDirList.Listing silent = LinuxDirList.parse(
                Collections.<String>emptyList(), Collections.<String>emptyList(), 0);
        check("没有回显算失败", !silent.ok(), silent.problem);

        // 回显的第一行不是绝对路径：不能当成目录用。
        LinuxDirList.Listing weird = LinuxDirList.parse(
                Collections.singletonList("relative/path"),
                Collections.<String>emptyList(), 0);
        check("回显不像绝对路径时算失败", !weird.ok(), weird.problem);

        // ls 的 . 与 .. 不该出现在列表里。
        LinuxDirList.Listing dots = LinuxDirList.parse(
                Arrays.asList("/x", "./", "../", "ok/"),
                Collections.<String>emptyList(), 0);
        check("过滤掉 . 与 ..",
                dots.dirs.equals(Collections.singletonList("ok")), dots.dirs.toString());

        // 详细信息：名字/权限/时间三列，用 tab 分开，所以带空格的名字不会散架。
        LinuxDirList.Listing det = LinuxDirList.parseDetails(
                Arrays.asList("/home/you",
                        "my data\tdrwxr-xr-x\t2026-10-11 14:03",
                        "plain\tdrwx------\t2026-01-02 03:04"),
                Collections.<String>emptyList(), 0);
        check("详情解析出两条", det.entries.size() == 2, String.valueOf(det.entries.size()));
        check("带空格的名字没被拆开",
                det.entries.get(0).name.equals("my data") || det.entries.get(1).name.equals("my data"),
                det.entries.get(0).name);
        check("详情带权限", !det.entries.get(0).perms.isEmpty());
        check("详情带时间", !det.entries.get(0).modified.isEmpty());
        check("详情里的名字也进了 dirs 列表", det.dirs.size() == 2);
    }

    private static void paths() {
        // 远端 Linux 的路径不能交给本机的 File 去算父目录：那会套上 Windows 的规则。
        eq("普通路径的上一级", "/home", LinuxDirList.parentOf("/home/you"));
        eq("两级路径的上一级", "/home/you", LinuxDirList.parentOf("/home/you/data"));
        eq("根目录的上一级还是根", "/", LinuxDirList.parentOf("/"));
        eq("一层路径的上一级是根", "/", LinuxDirList.parentOf("/home"));
        eq("末尾斜杠不影响", "/home", LinuxDirList.parentOf("/home/you/"));
        eq("空路径当根", "/", LinuxDirList.parentOf(""));
        eq("null 当根", "/", LinuxDirList.parentOf(null));

        eq("拼子路径", "/home/you/data", LinuxDirList.childOf("/home/you", "data"));
        eq("父目录带尾斜杠时不出现双斜杠", "/home/you/data",
                LinuxDirList.childOf("/home/you/", "data"));
        eq("根下拼子路径", "/data", LinuxDirList.childOf("/", "data"));
        eq("空父目录当根", "/data", LinuxDirList.childOf("", "data"));
    }

    /** 选文件时的列举：目录和文件都要，且能按名字过滤。 */
    private static void files() {
        String s = LinuxDirList.filesScript("/home/you", null);
        check("列文件也要回显绝对路径", s.contains("pwd -P"));
        check("列文件也要能报「进不去」", s.contains(LinuxDirList.NO_SUCH_DIR));

        // 过滤值里的 / 必须转义，否则 sed 表达式会被它截断成另一回事。
        String f = LinuxDirList.filesScript("/home/you", "a/b");
        check("过滤值里的斜杠被转义", !f.contains("*a/b*"), f);

        // 目录与文件要分开：目录是走进去用的，文件是选来用的。
        LinuxDirList.Listing got = LinuxDirList.parseFiles(
                Arrays.asList("/home/you", "sub/", "data.fa", "ref.gtf"),
                Collections.<String>emptyList(), 0);
        check("目录进了 dirs", got.dirs.equals(Collections.singletonList("sub")),
                got.dirs.toString());
        check("文件进了 files", got.files.size() == 2, got.files.toString());
        check("目录没有被当成文件", !got.files.contains("sub"), got.files.toString());
        check("文件里没有残留的斜杠",
                !got.files.get(0).endsWith("/"), got.files.toString());
        check("文件已排序",
                Arrays.asList("data.fa", "ref.gtf").equals(got.files),
                got.files.toString());

        // 空目录是正常的，不是错误。
        LinuxDirList.Listing empty = LinuxDirList.parseFiles(
                Collections.singletonList("/home/you"),
                Collections.<String>emptyList(), 0);
        check("没有文件时仍然算成功", empty.ok(), empty.problem);
        check("没有文件时 files 为空", empty.files.isEmpty());

        // 进不去时不该被当成"这个目录里没东西"。
        LinuxDirList.Listing bad = LinuxDirList.parseFiles(
                Collections.<String>emptyList(),
                Collections.singletonList(LinuxDirList.NO_SUCH_DIR), 3);
        check("进不去时算失败（不是「没有文件」）", !bad.ok(), bad.problem);
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

    private static List<String> list(String... s) {
        return Arrays.asList(s);
    }
}

package vmadapt.test;

import java.util.ArrayList;
import java.util.List;

import vmadapt.core.RemoteFiles;

/**
 * 远端工作目录路径的测试。
 *
 * <p>回归的是一个「保护措施把功能杀死」的缺陷。原写法是：
 *
 * <pre>
 *   WORK_ROOT = "~/vmadapt_work"
 *   mkdir -p '~/vmadapt_work/x' &amp;&amp; cd '...' &amp;&amp; pwd -P
 * </pre>
 *
 * <p>{@code ~} 只在<b>词首且没被引号包住</b>时才展开，而这里的引号是统一加上的，
 * 于是 shell 建出了一个<b>名字就叫 {@code ~}</b> 的目录；{@code pwd -P} 又如实
 * 把它报回来，一路写进界面显示的路径和工具参数，最后工具报「目录不存在」。
 *
 * <p>更讽刺的是那段代码的注释写着它用 {@code pwd} 就是为了把 {@code ~} 换成
 * 绝对路径——保护措施恰好掐死了它要保护的东西。
 *
 * <p>这个 bug 无声无息：一个叫 {@code ~} 的目录在路径语法上完全合法，代码没有
 * 任何理由怀疑它。
 */
public final class RemoteWorkdirTest {

    private static int checks;
    private static int failures;

    private RemoteWorkdirTest() {
    }

    public static void main(String[] args) {
        System.out.println("=== RemoteWorkdirTest ===");

        scriptShape();
        absolutePathValidation();
        homeTemplate();
        ensureDirScript();
        verifyPresentParsing();

        System.out.println(failures == 0
                ? "PASS " + checks + " checks"
                : "FAIL: " + failures + " of " + checks + " checks failed");
        if (failures > 0) {
            System.exit(1);
        }
    }

    /** 生成的脚本必须真的能展开家目录。 */
    private static void scriptShape() {
        String script = RemoteFiles.workDirScript("of_20261003");

        check("脚本里没有任何 ~ 字符", script.indexOf('~') < 0);
        check("用 $HOME 取家目录", script.contains("$HOME"));
        check("$HOME 在双引号里（会展开）", script.contains("\"$HOME/"));
        check("没有把 $HOME 放进单引号", !script.contains("'$HOME"));
        check("仍用 pwd -P 回显稳定路径", script.contains("pwd -P"));
        check("仍带子目录名", script.contains("vmadapt_work/of_20261003"));

        // 老写法的形状必须已经消失。
        check("不再出现单引号的 ~/ 模板", !script.contains("'~/"));
    }

    /** 回显的路径能不能当绝对路径用。 */
    private static void absolutePathValidation() {
        check("正常绝对路径可用",
                RemoteFiles.isUsableRemoteWorkdir("/home/demo/vmadapt_work/x"));
        // 这一条就是 bug 本身：
        check("中间带 ~ 的路径不可用（老 bug 的产物）",
                !RemoteFiles.isUsableRemoteWorkdir("/home/demo/~/vmadapt_work/x"));
        check("末尾是 ~ 分量的不可用",
                !RemoteFiles.isUsableRemoteWorkdir("/home/demo/~"));
        check("以 ~ 开头的不可用",
                !RemoteFiles.isUsableRemoteWorkdir("~/vmadapt_work/x"));
        check("单独的 ~ 不可用", !RemoteFiles.isUsableRemoteWorkdir("~"));
        check("相对路径不可用",
                !RemoteFiles.isUsableRemoteWorkdir("vmadapt_work/x"));
        check("空串不可用", !RemoteFiles.isUsableRemoteWorkdir(""));
        check("null 不可用", !RemoteFiles.isUsableRemoteWorkdir(null));
        check("只有 / 也是绝对路径（不崩）",
                RemoteFiles.isUsableRemoteWorkdir("/"));
    }

    /** ~/... 模板要变成 shell 真能展开的写法。 */
    private static void homeTemplate() {
        check("~/x 变成 \"$HOME/x\"",
                "\"$HOME/x\"".equals(RemoteFiles.expandHomeTemplate("~/x")));
        check("单独的 ~ 变成 \"$HOME\"",
                "\"$HOME\"".equals(RemoteFiles.expandHomeTemplate("~")));
        check("绝对路径走单引号",
                "'/data/x'".equals(RemoteFiles.expandHomeTemplate("/data/x")));
        // 反斜杠/引号/美元符在双引号里必须转义，否则会被当成展开或提前结束引号。
        String tricky = RemoteFiles.expandHomeTemplate("~/a\"b\\c$d");
        check("双引号里的特殊字符被转义",
                tricky.startsWith("\"$HOME/") && tricky.contains("\\\"")
                        && tricky.contains("\\\\") && tricky.contains("\\$"));
        check("含空格的绝对路径被保护",
                "'/data/my dir'".equals(RemoteFiles.expandHomeTemplate("/data/my dir")));
        check("null 不会崩", "''".equals(RemoteFiles.expandHomeTemplate(null)));
    }

    /** 上传前必须先建目标目录，否则 scp 会把目标路径本身建成副本。 */
    private static void ensureDirScript() {
        String s = RemoteFiles.ensureDirScript("/home/demo/work/inputs");
        check("建目录脚本用 mkdir -p", s.contains("mkdir -p"));
        check("路径被单引号保护",
                s.contains("'/home/demo/work/inputs'"));
        // 带空格的路径如果不加引号会被 shell 拆成两个参数，建出两个目录。
        String spaced = RemoteFiles.ensureDirScript("/home/demo/my work/inputs");
        check("含空格路径仍被完整引用",
                spaced.contains("'/home/demo/my work/inputs'"));
        check("脚本以换行结束", s.endsWith("\n"));
    }

    /** 上传后的存在性核对：对不上的位置要是 null，而不是想当然的路径。 */
    private static void verifyPresentParsing() {
        List<String> paths = new ArrayList<String>();
        paths.add("/home/demo/work/inputs/pep");
        paths.add("/home/demo/work/inputs/other");

        List<String> yesNo = new ArrayList<String>();
        yesNo.add("YES");
        yesNo.add("NO");
        List<String> out = RemoteFiles.presentFrom(paths, yesNo);
        check("存在的保留原路径",
                "/home/demo/work/inputs/pep".equals(out.get(0)));
        check("不存在的返回 null", out.get(1) == null);
        check("结果个数与输入一致", out.size() == paths.size());

        List<String> allYes = new ArrayList<String>();
        allYes.add("YES");
        allYes.add("YES");
        List<String> ok = RemoteFiles.presentFrom(paths, allYes);
        check("全部存在时原样返回",
                paths.get(0).equals(ok.get(0)) && paths.get(1).equals(ok.get(1)));

        check("回显缺失时一律 null",
                RemoteFiles.presentFrom(paths, null).get(0) == null);
        check("回显不足时后项为 null",
                RemoteFiles.presentFrom(paths, new ArrayList<String>()).get(0) == null);

        check("空输入返回空表",
                RemoteFiles.presentFrom(new ArrayList<String>(), null).isEmpty()
                        && RemoteFiles.presentFrom(null, null).isEmpty());
    }

    private static void check(String label, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("  FAIL: " + label);
        }
    }
}

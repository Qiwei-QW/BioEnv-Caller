package bioenv.test;

import java.util.ArrayList;
import java.util.List;

import bioenv.ai.AiContext;

/**
 * {@link AiContext} 的检查。
 *
 * <p>这里盯着的是<b>两件会真正伤人</b>的事，不是覆盖率：
 *
 * <ol>
 *   <li><b>JSON 转义</b> —— 帮助文本里有引号、反斜杠、换行、制表符。漏一个，整条上下文
 *       在页面里 {@code JSON.parse} 失败，用户看到的是"AI 不理我"。</li>
 *   <li><b>截断要说明</b> —— 帮助文本超过预算时必须<b>明说这是节选</b>。否则模型会以为
 *       帮助就这么多，对"没有这个参数"给出错误结论 —— 而这正是用户最容易被误导的地方。</li>
 * </ol>
 */
public final class AiContextTest {

    private static int passed;
    private static int failed;

    private AiContextTest() {
    }

    public static void main(String[] args) {
        System.out.println("=== AiContextTest ===");

        empties();
        fields();
        escaping();
        truncation();
        optionsAndLastRun();
        budget();

        System.out.println("");
        System.out.println("passed: " + passed + "   failed: " + failed);
        if (failed > 0) {
            System.out.println("FAILURES PRESENT");
            System.exit(1);
        }
    }

    private static void empties() {
        check("全空给 {}", "{}".equals(AiContext.build(
                null, null, null, null, null, null, null)));
        check("全是空白也给 {}", "{}".equals(AiContext.build(
                "  ", "", "\n", " ", null, "\t", null)));
        List<String> notes = new ArrayList<String>();
        notes.add("   ");
        notes.add("");
        check("空白 extras 不算数", "{}".equals(AiContext.build(
                null, null, null, null, null, null, notes)));
    }

    private static void fields() {
        String json = AiContext.build("codeml", "paml_env", "usage: codeml",
                "conda run codeml", "WSL", "/home/demo/work", null);
        check("工具名在里面", json.contains("\"tool\":\"codeml\""));
        check("环境名在里面", json.contains("\"env\":\"paml_env\""));
        check("帮助在里面", json.contains("usage: codeml"));
        check("命令在里面", json.contains("conda run codeml"));
        check("连接方式在里面", json.contains("\"mode\":\"WSL\""));
        check("工作目录在里面", json.contains("/home/demo/work"));
        check("是合法 JSON 的形状", json.startsWith("{") && json.endsWith("}"));

        List<String> extras = new ArrayList<String>();
        extras.add("插件用法：控制文件工具从当前目录读 .ctl");
        String withDocs = AiContext.build("codeml", null, null, null, null, null, extras);
        check("说明书节选会带上", withDocs.contains("docs"));

        // 值为 null 的字段不该以 "null" 出现在 JSON 里（那会让页面显示一个假的空值）。
        check("空字段不出现在 JSON 里", !json.contains(":null"));
    }

    private static void escaping() {
        String json = AiContext.build("t", null,
                "line1\nline2\ttab \"quoted\" back\\slash", null, null, null, null);
        check("换行被转义", json.contains("line1\\nline2"));
        check("制表符被转义", json.contains("\\t"));
        check("引号被转义", json.contains("\\\"quoted\\\""));
        check("反斜杠被转义", json.contains("back\\\\slash"));
        check("没有裸换行漏出去", json.indexOf('\n') < 0);

        // 控制字符必须走反斜杠-u 形式，否则 JSON 非法。
        // （注意：这条注释本身不能写那个转义序列 —— Java 在注释里也解析它，
        //   后面跟空格就是非法转义，直接把编译打挂。刚踩过。）
        String ctrl = AiContext.build("t", null, "a\u0001b", null, null, null, null);
        check("控制字符走 \\u 形式", ctrl.contains("\\u0001"));
    }

    private static void truncation() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 4000; i++) {
            big.append("--option").append(i).append("   description here\n");
        }
        String prepared = AiContext.prepareHelp(big.toString());
        check("超长帮助被裁到预算内", prepared.length() < big.length());
        check("裁完仍在预算附近（没有裁得太狠）", prepared.length() > AiContext.HELP_MAX / 2);
        check("★ 明说这是节选", prepared.contains("节选") || prepared.contains("前 "));
        check("★ 说明后面还有内容", prepared.contains("不要因为这里没写就断定"));
        check("给出了原长", prepared.contains(String.valueOf(big.toString().trim().length())));

        String small = "usage: x\n";
        check("短帮助原样保留", small.trim().equals(AiContext.prepareHelp(small)));
        check("null 帮助给空串", "".equals(AiContext.prepareHelp(null)));
    }

    /**
     * 参数表与上次运行。
     *
     * <p>这两项是本轮补上的：页面 {@code ctxText()} 一直在读 {@code context.options}
     * 和 {@code context.lastRun}，而 Java 侧从来没有发过——于是"解释上次为什么失败"
     * 那个入口永远不出现，文档里写的"命令/参数表/上次运行"也一直是空话。
     * 这里把字段形状钉住：字段名、类型、尾部裁剪、空值不出现。
     */
    private static void optionsAndLastRun() {
        List<AiContext.Chosen> options = new ArrayList<AiContext.Chosen>();
        options.add(new AiContext.Chosen("-M", "msa", "merge mode"));
        options.add(new AiContext.Chosen("-o", "/home/demo/out.fa", null));
        String json = AiContext.build("mafft", "bio", null, "conda run -n bio mafft -M msa",
                "WSL", "/home/demo", options,
                new AiContext.RunInfo(2, "Traceback ...\ncommand not found"), null);
        check("参数表在里面", json.contains("\"options\":["));
        check("flag 与值都在", json.contains("\"flag\":\"-M\"")
                && json.contains("\"arg\":\"msa\""));
        check("描述也在", json.contains("\"description\":\"merge mode\""));
        check("没有描述就不写这个键", !json.contains("\"description\":null"));
        check("上次运行在里面", json.contains("\"lastRun\":{\"exit\":2"));
        check("输出尾部在里面", json.contains("command not found"));
        check("命令行真的进去了", json.contains("conda run -n bio mafft -M msa"));

        List<AiContext.Chosen> none = new ArrayList<AiContext.Chosen>();
        String bare = AiContext.build("x", null, null, null, null, null, none, null, null);
        check("空参数表不出现", !bare.contains("options"));
        check("没有上次运行就不出现", !bare.contains("lastRun"));

        StringBuilder tail = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            tail.append("line").append(i).append('\n');
        }
        String big = AiContext.build("x", null, null, null, null, null, null,
                new AiContext.RunInfo(1, tail.toString()), null);
        check("超长尾部被裁到上限内", big.length() < tail.length());
        check("★ 裁完保留的是结尾", big.contains("line1999"));
        check("★ 说明省略了多少", big.contains("省略"));
    }

    /**
     * 预算。
     *
     * <p>参数表和上次运行也吃预算，所以帮助的额度必须跟着缩小；否则"补上字段"这件事
     * 会把请求推过桥的上限，反而让默认模型开始报错。
     */
    private static void budget() {
        StringBuilder help = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            help.append("-o").append(i).append("  description here\n");
        }
        List<AiContext.Chosen> options = new ArrayList<AiContext.Chosen>();
        for (int i = 0; i < 200; i++) {
            options.add(new AiContext.Chosen("-o" + i, "value" + i, "description " + i));
        }
        StringBuilder tail = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            tail.append("out ").append(i).append('\n');
        }
        String json = AiContext.build("t", null, help.toString(), "cmd", "WSL", "/w",
                options, new AiContext.RunInfo(0, tail.toString()), null);
        check("总长度受预算约束", json.length() <= AiContext.BUDGET + 1000);
        check("★ 帮助仍明确说是节选", json.contains("节选") || json.contains("前 "));
        check("参数表有上限", countOccurrences(json, "\"flag\":") <= AiContext.OPTIONS_MAX);
        check("括号配平（页面 JSON.parse 失败就是「AI 不理我」）", balanced(json));
    }

    private static int countOccurrences(String s, String needle) {
        int n = 0;
        int from = 0;
        while ((from = s.indexOf(needle, from)) >= 0) {
            n++;
            from += needle.length();
        }
        return n;
    }

    /** 粗检：花括号/方括号配平，字符串不悬空。不需要完整 JSON 解析。 */
    private static boolean balanced(String s) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
                if (depth < 0) {
                    return false;
                }
            }
        }
        return depth == 0 && !inString;
    }

    private static void check(String label, boolean ok) {        if (ok) {
            passed++;
            System.out.println("  PASS  " + label);
        } else {
            failed++;
            System.out.println("  FAIL  " + label);
        }
    }
}

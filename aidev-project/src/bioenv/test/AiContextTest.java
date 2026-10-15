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
                "conda run codeml", "WSL", "/home/qw/work", null);
        check("工具名在里面", json.contains("\"tool\":\"codeml\""));
        check("环境名在里面", json.contains("\"env\":\"paml_env\""));
        check("帮助在里面", json.contains("usage: codeml"));
        check("命令在里面", json.contains("conda run codeml"));
        check("连接方式在里面", json.contains("\"mode\":\"WSL\""));
        check("工作目录在里面", json.contains("/home/qw/work"));
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

    private static void check(String label, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  PASS  " + label);
        } else {
            failed++;
            System.out.println("  FAIL  " + label);
        }
    }
}

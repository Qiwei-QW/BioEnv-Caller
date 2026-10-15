package bioenv.ai;

import java.util.List;

/**
 * 拼给 AI 的上下文。
 *
 * <h2>为什么要自动带，而不是让用户粘贴</h2>
 *
 * <p>这个插件最值钱的东西，恰恰是<b>别的工具拿不到</b>的那些：每个工具的
 * <b>原始 {@code --help} 文本</b>（{@code DiscoveryCache} 缓存的）、当前拼出的命令、
 * 工作目录、连接方式。用户把它们一条条复制粘贴给网页版 AI，既麻烦又容易漏。
 *
 * <p>这个类只做两件事：<b>把已知状态拼成 JSON</b>，以及<b>按预算裁剪</b>。
 * 全是纯函数，能无头测。
 *
 * <h2>预算</h2>
 *
 * <p>TBtools 的桥对请求有大小上限（实测约 32000 字符 / 20 条消息）。帮助文本动辄几万字符，
 * 所以<b>必须在这里裁</b>，而且<b>要告诉模型它拿到的是节选</b>——否则模型会以为帮助就这么多，
 * 对"没有这个参数"下错结论。
 */
public final class AiContext {

    /** 请求预算（字符）。实测值，见类注释。 */
    public static final int BUDGET = 30000;
    /** 帮助文本单独占的上限 —— 留出余量给对话历史和提示词。 */
    public static final int HELP_MAX = 18000;

    private AiContext() {
    }

    /**
     * 拼上下文。
     *
     * @param tool    工具名（如 {@code codeml}）
     * @param env     conda 环境名，可为空
     * @param help    原始帮助文本，可为空
     * @param command 当前将要执行的命令，可为空
     * @param mode    连接方式（{@code WSL} / {@code SSH}），可为空
     * @param workdir 工作目录，可为空
     * @param extras  额外说明行（可选）
     * @return JSON 字符串；全空时返回 {@code "{}"}
     */
    public static String build(String tool, String env, String help, String command,
                               String mode, String workdir, List<String> extras) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append('{');
        boolean first = true;
        first = field(sb, first, "tool", tool);
        first = field(sb, first, "env", env);
        first = field(sb, first, "mode", mode);
        first = field(sb, first, "workdir", workdir);
        first = field(sb, first, "command", command);
        first = field(sb, first, "help", prepareHelp(help));
        if (extras != null && !extras.isEmpty()) {
            StringBuilder e = new StringBuilder();
            for (int i = 0; i < extras.size(); i++) {
                String line = extras.get(i);
                if (line == null || line.trim().isEmpty()) {
                    continue;
                }
                if (e.length() > 0) {
                    e.append('\n');
                }
                e.append(line.trim());
            }
            first = field(sb, first, "docs", e.toString());
        }
        sb.append('}');
        return sb.toString();
    }

    /**
     * 帮助文本的裁剪。
     *
     * <p>截断时<b>明确写出"这是节选、后面还有多少"</b>：模型必须知道它看到的不是全部，
     * 否则会对"帮助里没有这个参数"给出错误结论。
     */
    public static String prepareHelp(String help) {
        if (help == null) {
            return "";
        }
        String trimmed = help.trim();
        if (trimmed.length() <= HELP_MAX) {
            return trimmed;
        }
        int cut = HELP_MAX;
        // 尽量在行边界断开，别把一行参数说明切两半。
        int nl = trimmed.lastIndexOf('\n', cut);
        if (nl > cut / 2) {
            cut = nl;
        }
        return trimmed.substring(0, cut)
                + "\n\n[帮助文本过长，上面是前 " + cut + " 个字符，全文共 "
                + trimmed.length() + " 个字符 —— 后面还有内容，不要因为这里没写就断定某个参数不存在]";
    }

    // ------------------------------------------------------------------

    private static boolean field(StringBuilder sb, boolean first, String key, String value) {
        if (value == null || value.trim().isEmpty()) {
            return first;
        }
        if (!first) {
            sb.append(',');
        }
        sb.append(jsonString(key)).append(':').append(jsonString(value.trim()));
        return false;
    }

    /** 字符串 → JSON 字面量。 */
    static String jsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}

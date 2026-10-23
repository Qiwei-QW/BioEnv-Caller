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
    /** 参数表最多带多少条 —— 一个工具几十个参数，再多模型也读不完。 */
    public static final int OPTIONS_MAX = 60;
    /** 上次运行输出尾部最多带多少字符。 */
    public static final int RUN_TAIL_MAX = 4000;

    private AiContext() {
    }

    /**
     * 拼上下文（不带参数表、不带上次运行）。
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
        return build(tool, env, help, command, mode, workdir, null, null, extras);
    }

    /**
     * 拼上下文（页面读的全部字段）。
     *
     * <p>页面 {@code ctxText()} 读的字段有 {@code tool/env/mode/workdir/command/options/
     * help/lastRun/docs}。{@code option}s 和 {@code lastRun} 曾经<b>根本没有</b> ——
     * 文档写着"命令、参数表、上次运行"会自动带上，实际只发了工具、环境、帮助和工作目录，
     * 于是"解释上次为什么失败"那个入口永远不会出现。这里把它们补齐。
     *
     * @param options 用户已经填/选的参数，可为空；只放用户表达过的那些（DESIGN.md P12）
     * @param lastRun 上一次运行的退出码与输出尾部，可为空
     */
    public static String build(String tool, String env, String help, String command,
                               String mode, String workdir, List<Chosen> options,
                               RunInfo lastRun, List<String> extras) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append('{');
        boolean first = true;
        first = field(sb, first, "tool", tool);
        first = field(sb, first, "env", env);
        first = field(sb, first, "mode", mode);
        first = field(sb, first, "workdir", workdir);
        first = field(sb, first, "command", command);
        // 帮助在最后裁，因为它的预算是"总预算减去其它字段"。
        first = rawField(sb, first, "options", optionsJson(options));
        first = rawField(sb, first, "lastRun", lastRunJson(lastRun));
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
        int left = BUDGET - sb.length() - 400;   // 给 help 键名与收尾留点余量
        first = field(sb, first, "help", prepareHelp(help, Math.max(2000, Math.min(HELP_MAX, left))));
        sb.append('}');
        return sb.toString();
    }

    /** 一个已经填好的参数：flag 与它的值，外加描述（帮助模型理解这个值是什么）。 */
    public static final class Chosen {
        public final String flag;
        public final String arg;
        public final String description;

        public Chosen(String flag, String arg, String description) {
            this.flag = flag;
            this.arg = arg;
            this.description = description;
        }
    }

    /** 上一次运行的信息：退出码与输出尾部。 */
    public static final class RunInfo {
        public final int exit;
        public final String tail;

        public RunInfo(int exit, String tail) {
            this.exit = exit;
            this.tail = tail;
        }
    }

    /** 参数表 → JSON 数组；空表返回 null（字段就不出现）。 */
    static String optionsJson(List<Chosen> options) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        StringBuilder b = new StringBuilder("[");
        int n = 0;
        for (Chosen c : options) {
            if (c == null || c.flag == null || c.flag.trim().isEmpty()) {
                continue;
            }
            if (n >= OPTIONS_MAX) {
                break;
            }
            if (n > 0) {
                b.append(',');
            }
            b.append('{');
            boolean first = true;
            first = field(b, first, "flag", c.flag);
            first = field(b, first, "arg", c.arg);
            field(b, first, "description", c.description);
            b.append('}');
            n++;
        }
        if (n == 0) {
            return null;
        }
        b.append(']');
        return b.toString();
    }

    /** 上次运行 → JSON 对象；没有标题可用时返回 null。 */
    static String lastRunJson(RunInfo r) {
        if (r == null) {
            return null;
        }
        String tail = r.tail == null ? "" : r.tail;
        if (tail.length() > RUN_TAIL_MAX) {
            tail = "…（前面省略 " + (tail.length() - RUN_TAIL_MAX) + " 字符）\n"
                    + tail.substring(tail.length() - RUN_TAIL_MAX);
        }
        StringBuilder b = new StringBuilder("{");
        b.append("\"exit\":").append(r.exit);
        if (!tail.trim().isEmpty()) {
            b.append(",\"tail\":").append(jsonString(tail.trim()));
        }
        b.append('}');
        return b.toString();
    }

    /** 原样拼一个已经是 JSON 的字段；null 表示这一项不出现。 */
    private static boolean rawField(StringBuilder sb, boolean first, String key, String json) {
        if (json == null || json.isEmpty()) {
            return first;
        }
        if (!first) {
            sb.append(',');
        }
        sb.append(jsonString(key)).append(':').append(json);
        return false;
    }


    /**
     * 帮助文本的裁剪。
     *
     * <p>截断时<b>明确写出"这是节选、后面还有多少"</b>：模型必须知道它看到的不是全部，
     * 否则会对"帮助里没有这个参数"给出错误结论。
     */
    public static String prepareHelp(String help) {
        return prepareHelp(help, HELP_MAX);
    }

    /**
     * 帮助文本的裁剪，按给定预算。
     *
     * <p>参数表和上次运行也占预算，所以帮助的额度不是固定的 {@link #HELP_MAX}。
     * 下限由调用方保证（至少 2000 字符），否则一个超长命令能把帮助挤成空白。
     */
    public static String prepareHelp(String help, int max) {
        if (help == null) {
            return "";
        }
        String trimmed = help.trim();
        if (trimmed.length() <= max) {
            return trimmed;
        }
        int cut = max;
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

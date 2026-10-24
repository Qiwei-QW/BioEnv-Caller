package bioenv.ai;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import bioenv.core.AtomicFiles;
import bioenv.core.ConfigWarnings;
import bioenv.core.FilePerms;

/**
 * AI 模型列表：用户配好的若干个 OpenAI 兼容接口。
 *
 * <h2>为什么是列表</h2>
 *
 * <p>一个 key 一个模型不够用：同一个账号可能想配 chat 和 reasoner 两个，另外还想留一个
 * 本地 Ollama 在不联网的时候用。所以这里是若干条配置，用户在 AI 面板上切当前用哪一条。
 *
 * <h2>谁存什么（这条是硬要求）</h2>
 *
 * <p>这份列表只有设置窗口能改，存在这个文件里。AI 面板只存"当前选中哪一个"，存在页面
 * 自己的 localStorage 里，不回写这里。
 *
 * <p>上一版把"一份配置"同时放在 Java 和页面里，两边都能改，结果是面板里改了不生效 ——
 * 页面没有回写 Java 的通道。两个不相交的集合才不会有这个问题。
 */
public final class AiModelStore {

    private static final String FILE_NAME = "ai-models.properties";

    /**
     * 文件里允许出现的模型条数上限。
     *
     * <p>解析时下标直接决定分配多少个对象。一次截断或一个手误（{@code m50000000.name=x}）
     * 就能让打开设置窗口时分配上千万个对象、直接 OOM；真实使用不可能配这么多条，
     * 所以超限只能意味着"这个文件不是我们写的"：备份、报一次、按没配过继续。
     */
    private static final int MAX_MODELS = 1000;

    /** 一条配置。 */
    public static final class Model {

        /** 显示名，用户在设置里起的。空的话回退到模型名。 */
        public String name = "";

        /** 形如 {@code https://api.deepseek.com/v1}。 */
        public String baseUrl = "";

        /** 明文。见 {@link AiModelStore} 的说明。 */
        public String apiKey = "";

        /** 形如 {@code deepseek-chat}。 */
        public String model = "";

        /** 输出上限，作为 max_tokens 发出去。 0 = 不发。 */
        public int maxOut;

        /** 输入上限（token）。 0 = 不截。只在本地截历史，不发给服务端。 */
        public int maxIn;

        /** 界面上的名字：没起名就用模型名。 */
        public String label() {
            return (name != null && name.trim().length() > 0) ? name.trim()
                    : (model == null ? "" : model.trim());
        }

        /**
         * 配置完整吗。
         *
         * <p>设置列表里那个绿点就是它 —— 这里不 ping 接口：每次开设置都去连一遍会很慢，
         * 而且没网时全灰反而误导。真连通性由「测试」按钮手动触发。
         */
        public boolean complete() {
            return baseUrl != null && baseUrl.trim().length() > 0
                    && model != null && model.trim().length() > 0;
        }

        /** 缺什么，给界面提示用。完整时返回 null。 */
        public String problem() {
            if (baseUrl == null || baseUrl.trim().length() == 0) {
                return "缺接口地址";
            }
            if (model == null || model.trim().length() == 0) {
                return "缺模型名";
            }
            return null;
        }

        /** 复制一份 —— 编辑时在副本上改，点取消就不影响原对象。 */
        public Model copy() {
            Model m = new Model();
            m.name = name;
            m.baseUrl = baseUrl;
            m.apiKey = apiKey;
            m.model = model;
            m.maxOut = maxOut;
            m.maxIn = maxIn;
            return m;
        }
    }

    /**
     * 长度输入框 → int：空 = 0（不限），<b>非法 = -1</b>，负数也非法。
     *
     * <p>-1 让调用方能区分"没填"和"填错了"。以前两种情况都返回 0，于是填错了
     * 也照样保存，下次打开却是空的 —— "为什么我填的没了"就是这么来的。
     *
     * <p>K 按 1024、M 按 1024²：用户写 1024K 和 1M 应该是同一档。按十进制的话
     * 1024K 会比 1M 大 2.4%，这个不一致会让人以为自己算错了。
     */
    public static int parseLen(String s) {
        String v = (s == null) ? "" : s.trim().toLowerCase();
        if (v.length() == 0) {
            return 0;
        }
        long mult = 1L;
        if (v.endsWith("k")) {
            mult = 1024L;
            v = v.substring(0, v.length() - 1).trim();
        } else if (v.endsWith("m")) {
            mult = 1024L * 1024L;
            v = v.substring(0, v.length() - 1).trim();
        }
        if (v.length() == 0) {
            return -1;
        }
        try {
            // multiplyExact：填个天文数字也不该溢出成一个看着合理的负数/小数。
            long n = Math.multiplyExact(Long.parseLong(v), mult);
            if (n < 0) {
                return -1;               // "-1M" 不是"不限"，是填错了
            }
            return (int) Math.min(Integer.MAX_VALUE, n);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 存着的长度 → 输入框里显示的样子。
     *
     * <p>填过 {@code 1M} 的人，下次打开应该还看到 {@code 1M}。显示 1048576 的话，
     * 用户得数一遍零才能确认这是不是自己填的那个数 —— 而且很容易以为填错了。
     */
    public static String formatLen(int v) {
        if (v <= 0) {
            return "";
        }
        if (v % (1024 * 1024) == 0) {
            return (v / (1024 * 1024)) + "M";
        }
        if (v % 1024 == 0) {
            return (v / 1024) + "K";
        }
        return String.valueOf(v);
    }

    /** 配好的模型。顺序就是界面上的顺序。 */
    public final List<Model> models = new ArrayList<Model>();

    /** 读盘。文件不存在就是空列表 —— 空列表等于"只用内置"。 */
    public static AiModelStore load() {
        AiModelStore out = new AiModelStore();
        File f = file();
        if (f == null || !f.isFile()) {
            return out;
        }
        String text = AtomicFiles.read(f, StandardCharsets.UTF_8);
        if (text == null) {
            corrupted(f);
            return new AiModelStore();
        }
        try {
            String[] lines = text.split("\r?\n");
            int count = -1;
            List<Model> buf = new ArrayList<Model>();
            for (int li = 0; li < lines.length; li++) {
                String line = lines[li];
                int eq = line.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String k = line.substring(0, eq).trim();
                String v = unescape(line.substring(eq + 1));
                if ("count".equals(k)) {
                    int declared = strictInt(v);
                    if (declared < 0 || declared > MAX_MODELS) {
                        corrupted(f);
                        return new AiModelStore();
                    }
                    count = declared;
                    continue;
                }
                if (!k.startsWith("m")) {
                    continue;
                }
                int dot = k.indexOf('.');
                if (dot < 0) {
                    continue;
                }
                int idx;
                try {
                    idx = Integer.parseInt(k.substring(1, dot));
                } catch (NumberFormatException e) {
                    // 不是我们写的字段（例如将来加的 mN.xxx 之外的键），忽略即可。
                    continue;
                }
                if (idx < 0 || idx > MAX_MODELS) {
                    // 计数来自文件，不能拿它当分配上界。
                    corrupted(f);
                    return new AiModelStore();
                }
                while (idx >= buf.size()) {
                    buf.add(new Model());
                }
                String field = k.substring(dot + 1);
                Model m = buf.get(idx);
                if ("name".equals(field)) {
                    m.name = v;
                } else if ("baseUrl".equals(field)) {
                    m.baseUrl = v;
                } else if ("apiKey".equals(field)) {
                    m.apiKey = v;
                } else if ("model".equals(field)) {
                    m.model = v;
                } else if ("maxOut".equals(field)) {
                    m.maxOut = intOf(v);
                } else if ("maxIn".equals(field)) {
                    m.maxIn = intOf(v);
                }
            }
            int n = (count >= 0 && count <= buf.size()) ? count : buf.size();
            for (int i = 0; i < n; i++) {
                out.models.add(buf.get(i));
            }
        } catch (Exception e) {
            // 读不出来就当没配过 —— 但先把原文件留一份 .bak，并报一次。
            corrupted(f);
            return new AiModelStore();
        }
        return out;
    }

    /**
     * 文件在、但读不出来（或其中的数字不可信）：先备份成 {@code .bak}，再报一次。
     *
     * <p>这一份文件里是用户的接口地址、模型名和 key，不能像以前那样"读不出来就当
     * 没配过"，然后在下一次保存时把原文件覆盖掉。
     */
    private static void corrupted(File f) {
        ConfigWarnings.readFailed(f, AtomicFiles.backup(f));
    }

    /** 严格解析一个整数；不是整数时返回 -1（调用方据此判坏，而不是当成 0）。 */
    private static int strictInt(String s) {
        if (s == null) {
            return -1;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 写盘。失败不抛（设置改不动比存不下更让人困惑），但会由 {@link AtomicFiles}
     * 报一次，并且旧文件在新内容完整落盘前不受影响。
     */
    public void save() {
        File f = file();
        if (f == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# BioEnv Caller AI models\n");
        sb.append("count=").append(models.size()).append('\n');
        for (int i = 0; i < models.size(); i++) {
            Model m = models.get(i);
            sb.append("m").append(i).append(".name=").append(escape(m.name)).append('\n');
            sb.append("m").append(i).append(".baseUrl=").append(escape(m.baseUrl)).append('\n');
            sb.append("m").append(i).append(".apiKey=").append(escape(m.apiKey)).append('\n');
            sb.append("m").append(i).append(".model=").append(escape(m.model)).append('\n');
            sb.append("m").append(i).append(".maxOut=").append(m.maxOut).append('\n');
            sb.append("m").append(i).append(".maxIn=").append(m.maxIn).append('\n');
        }
        // 建目录、临时文件、原子替换都在 AtomicFiles 里；失败时它已经报过。
        if (AtomicFiles.write(f, sb.toString(), StandardCharsets.UTF_8)) {
            // 这份文件里是明文 API key：写成功后尽量收窄到"只有本人可读写"。
            // 收不了（FAT、网络盘、无 ACL）只会在本进程提示一次，绝不影响保存。
            FilePerms.restrictToOwner(f);
        }
    }

    /**
     * 给页面用的 JSON 数组。
     *
     * <p>key 一起发 —— 请求是页面发的，没有它发不出去。同一进程里没有更安全的通道，
     * 这一点和键存明文是同一个取舍。
     */
    public String toJson() {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < models.size(); i++) {
            Model m = models.get(i);
            if (i > 0) {
                b.append(',');
            }
            b.append("{\"id\":\"").append(i);
            b.append("\",\"label\":\"").append(json(m.label()));
            b.append("\",\"baseUrl\":\"").append(json(trimSlash(m.baseUrl)));
            b.append("\",\"apiKey\":\"").append(json(m.apiKey));
            b.append("\",\"model\":\"").append(json(m.model));
            b.append("\",\"maxOut\":").append(Math.max(0, m.maxOut));
            b.append(",\"maxIn\":").append(Math.max(0, m.maxIn));
            b.append(",\"ok\":").append(m.complete());
            b.append('}');
        }
        b.append(']');
        return b.toString();
    }

    private static int intOf(String s) {
        try {
            return Math.max(0, Integer.parseInt(s.trim()));
        } catch (Exception e) {
            return 0;
        }
    }

    private static String trimSlash(String s) {
        String t = s == null ? "" : s.trim();
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    private static String json(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                b.append('\\').append(c);
            } else if (c == '\n') {
                b.append("\\n");
            } else if (c == '\r') {
                b.append("\\r");
            } else if (c < 0x20) {
                b.append(' ');
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
    }

    private static String unescape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                if (n == 'n') {
                    b.append('\n');
                } else if (n == 'r') {
                    b.append('\r');
                } else if (n == '\\') {
                    b.append('\\');
                } else {
                    b.append(n);
                }
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    private static File file() {
        String home = System.getProperty("user.home");
        if (home == null || home.length() == 0) {
            return null;
        }
        return new File(new File(new File(home, ".TBtools"), "BioEnvCaller"), FILE_NAME);
    }
}

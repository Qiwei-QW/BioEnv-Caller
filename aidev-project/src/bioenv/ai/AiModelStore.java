package bioenv.ai;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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
            return m;
        }
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
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), StandardCharsets.UTF_8));
            String line;
            int count = -1;
            List<Model> buf = new ArrayList<Model>();
            while ((line = r.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String k = line.substring(0, eq).trim();
                String v = unescape(line.substring(eq + 1));
                if ("count".equals(k)) {
                    try {
                        count = Integer.parseInt(v.trim());
                    } catch (NumberFormatException e) {
                        count = -1;
                    }
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
                    continue;
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
                }
            }
            int n = (count >= 0 && count <= buf.size()) ? count : buf.size();
            for (int i = 0; i < n; i++) {
                out.models.add(buf.get(i));
            }
        } catch (Exception e) {
            // 读不出来就当没配过 —— 配置是锦上添花，不能是唯一的失败点。
            return new AiModelStore();
        } finally {
            close(r);
        }
        return out;
    }

    /** 写盘。失败不抛，设置改不动比存不下更让人困惑。 */
    public void save() {
        File f = file();
        if (f == null) {
            return;
        }
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            return;
        }
        Writer w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8);
            w.write("# BioEnv Caller AI models\n");
            w.write("count=" + models.size() + "\n");
            for (int i = 0; i < models.size(); i++) {
                Model m = models.get(i);
                w.write("m" + i + ".name=" + escape(m.name) + "\n");
                w.write("m" + i + ".baseUrl=" + escape(m.baseUrl) + "\n");
                w.write("m" + i + ".apiKey=" + escape(m.apiKey) + "\n");
                w.write("m" + i + ".model=" + escape(m.model) + "\n");
            }
            w.flush();
        } catch (Exception e) {
            // 同上。
        } finally {
            close(w);
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
            b.append("\",\"ok\":").append(m.complete());
            b.append('}');
        }
        b.append(']');
        return b.toString();
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

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
                // 关不上没什么可做的。
            }
        }
    }

    private static File file() {
        String home = System.getProperty("user.home");
        if (home == null || home.length() == 0) {
            return null;
        }
        return new File(new File(new File(home, ".TBtools"), "BioEnvCaller"), FILE_NAME);
    }
}

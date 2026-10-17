package bioenv.ai;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * AI 模型配置：内置的，还是用户自己的。
 *
 * <h2>为什么需要这个东西</h2>
 *
 * <p>内置模型的调用发生在 TBtools 里（{@code window.tbtools.ai.chat}），插件既不知道
 * 用的是哪个模型，也没有接口去换掉它。想让用户用自己的 key，只有一条路：
 * <b>页面自己 {@code fetch} 一个 OpenAI 兼容的接口</b>。这个类存的就是那条路需要的四样东西。
 *
 * <h2>为什么是 OpenAI 兼容格式</h2>
 *
 * <p>因为一套代码就能通吃 OpenAI、DeepSeek、通义、Kimi，以及本地的 Ollama、LM Studio、
 * vLLM —— 它们都实现同一个 {@code /chat/completions} 加 SSE 的形状。多写一个适配层
 * 只会多一处要维护的不一致。
 *
 * <h2>API key 存在哪</h2>
 *
 * <p>明文存在 {@code %USERPROFILE%\.TBtools\BioEnvCaller\ai-model.properties}。
 * 这不做加密 —— 在同一台机器上加密只是把钥匙也放在旁边，反而多一层可以坏的东西。
 * 但<b>界面上必须说清楚它是明文的</b>，让用户自己决定要不要填。
 */
public final class AiModelStore {

    /** 用 TBtools 内置的模型。 */
    public static final String MODE_BUILTIN = "builtin";

    /** 用用户填的 OpenAI 兼容接口。 */
    public static final String MODE_CUSTOM = "custom";

    private static final String FILE_NAME = "ai-model.properties";

    /** builtin 或 custom。 */
    public String mode = MODE_BUILTIN;

    /** 形如 {@code https://api.deepseek.com/v1}，末尾斜杠会被去掉。 */
    public String baseUrl = "";

    /** 明文。见类注释。 */
    public String apiKey = "";

    /** 模型名，形如 {@code deepseek-chat}。 */
    public String model = "";

    /** 现在是不是在用自定义模型。 */
    public boolean isCustom() {
        return MODE_CUSTOM.equals(mode) && baseUrl.trim().length() > 0
                && model.trim().length() > 0;
    }

    /**
     * 缺了什么就读不出来。
     *
     * <p>自定义模式缺 baseUrl 或 model 就是配错了 —— 那种情况下<b>退回内置模型</b>，
     * 而不是带着半份配置去请求，然后让用户面对一个看不懂的 404。
     */
    public String problem() {
        if (!MODE_CUSTOM.equals(mode)) {
            return null;
        }
        if (baseUrl.trim().length() == 0) {
            return "没有填接口地址";
        }
        if (model.trim().length() == 0) {
            return "没有填模型名";
        }
        return null;
    }

    /** 读盘。文件不存在、读不出来，都退回内置模型 —— 不能因为配置坏了就打不开 AI。 */
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
            while ((line = r.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String k = line.substring(0, eq).trim();
                String v = unescape(line.substring(eq + 1));
                if ("mode".equals(k)) {
                    out.mode = v;
                } else if ("baseUrl".equals(k)) {
                    out.baseUrl = v;
                } else if ("apiKey".equals(k)) {
                    out.apiKey = v;
                } else if ("model".equals(k)) {
                    out.model = v;
                }
            }
        } catch (Exception e) {
            // 读不出来就用默认值。配置是锦上添花，不能让它是唯一的失败点。
            return new AiModelStore();
        } finally {
            close(r);
        }
        return out;
    }

    /** 写盘。失败不抛 —— 设置改不动比设置存不下更让人困惑。 */
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
            w.write("# BioEnv Caller AI model config\n");
            w.write("mode=" + escape(mode) + "\n");
            w.write("baseUrl=" + escape(baseUrl) + "\n");
            w.write("apiKey=" + escape(apiKey) + "\n");
            w.write("model=" + escape(model) + "\n");
            w.flush();
        } catch (Exception e) {
            // 同上：存不下不抛。
        } finally {
            close(w);
        }
    }

    /**
     * 给页面用的 JSON。
     *
     * <p>key 一起发过去 —— 请求是页面发的，没有它就发不出去。这个类不负责"安全地
     * 传 key"，因为同一个进程里没有更安全的通道可用。
     */
    public String toJson() {
        StringBuilder b = new StringBuilder();
        b.append("{\"mode\":\"").append(json(mode));
        b.append("\",\"baseUrl\":\"").append(json(trimSlash(baseUrl)));
        b.append("\",\"apiKey\":\"").append(json(apiKey));
        b.append("\",\"model\":\"").append(json(model)).append("\"}");
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

    /** 反斜杠和换行要转义，否则读回来会错行。 */
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

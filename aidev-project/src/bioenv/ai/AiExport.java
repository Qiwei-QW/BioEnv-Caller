package bioenv.ai;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * 把对话导出成 Markdown 文件里的<b>三件不涉及界面的事</b>。
 *
 * <p>拆出来是为了能验：这三步里任何一步错了，结果都不是"报错"，而是
 * <b>一个看起来正常的文件</b> —— 名字少几个字、正文从第一个竖线后开始、
 * 打开是乱码。那种错用户不会去查，只会觉得"导出来的东西不对"。
 *
 * <ol>
 *   <li>{@link #decode(String)} —— 页面留在 localStorage 里的那一条载荷；
 *   <li>{@link #safeBase(String)} —— 会话名 → Windows 上合法的文件名；
 *   <li>{@link #write(File, String)} —— 正文 → UTF-8 文件。
 * </ol>
 *
 * <h2>载荷格式</h2>
 *
 * <p>{@code encodeURIComponent(名字) + "|" + encodeURIComponent(正文)}。
 *
 * <p>为什么是"百分号编码 + 竖线"，而不是 JSON：这种载荷要穿过
 * {@code executeJavaScript} 的返回值回到 Java，而那个返回值在各版本里
 * <b>有时带外层引号、有时不带</b>（见 {@link AiWebView#evalString}）。百分号编码之后
 * 整条载荷里<b>不可能出现引号、反斜杠、换行</b>，所以无论那个返回值怎么被包、
 * 怎么被转义，剥掉外层引号之后就还是原样。
 *
 * <p>竖线之所以能当分隔符，是因为 {@code encodeURIComponent} 会把正文里的
 * {@code |} 编成 {@code %7C} —— 载荷里出现的第一个竖线必然是分隔符。
 */
public final class AiExport {

    /** 载荷里名字与正文之间的分隔符。encodeURIComponent 不会产出它。 */
    static final String SEP = "|";

    /** 名字最长多少个字符：再长对话框里就看不全，也没人这么命名。 */
    private static final int MAX_BASE = 60;

    /** 没有名字时用的文件名主干。 */
    public static final String FALLBACK = "conversation";

    private AiExport() {
    }

    /**
     * 拆开页面写下的那条载荷。
     *
     * @return {@code {名字, 正文}}；载荷为空或正文为空时返回 {@code null}
     */
    public static String[] decode(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.length() == 0) {
            return null;
        }
        // evalString 已经剥过一次引号，但它“有时带有时不带”是引擎的行为，
        // 这里再兜一次：多剥一次不会伤到正常载荷（正文里不可能有裸引号）。
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            s = s.substring(1, s.length() - 1);
        }
        int bar = s.indexOf(SEP);
        String name = bar < 0 ? "" : percentDecode(s.substring(0, bar));
        String text = percentDecode(bar < 0 ? s : s.substring(bar + 1));
        if (text == null || text.trim().length() == 0) {
            return null;
        }
        if (name == null || name.trim().length() == 0) {
            name = FALLBACK;
        }
        return new String[] {name, text};
    }

    /**
     * 会话名 → 能当文件名的样子。
     *
     * <p>Windows 不允许 {@code \ / : * ? " < > |}，还不允许名字以点或空格结尾 ——
     * 而会话名是"工具名 · 时间"，中间那个冒号<b>每次都踩</b>。
     */
    public static String safeBase(String name) {
        String s = name == null ? "" : name;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                continue;                       // 控制字符：文件名里根本放不进去
            }
            if (c == '\\' || c == '/' || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == '|') {
                continue;
            }
            sb.append(c == '\t' ? ' ' : c);
        }
        // 空白压成一个空格：名字来自"时间戳 + 工具名"，多空格只是难看。
        String flat = sb.toString().replaceAll("\\s+", " ").trim();
        // 结尾的点和空格在 Windows 上会被悄悄丢掉，不如自己先去掉。
        int end = flat.length();
        while (end > 0 && (flat.charAt(end - 1) == '.' || flat.charAt(end - 1) == ' ')) {
            end--;
        }
        flat = flat.substring(0, end);
        if (flat.length() == 0) {
            return FALLBACK;
        }
        if (flat.length() > MAX_BASE) {
            int cut = MAX_BASE;
            // 不要把一个代理对切成两半 —— 那会写出一个孤立的半个字符。
            if (Character.isHighSurrogate(flat.charAt(cut - 1))) {
                cut--;
            }
            flat = flat.substring(0, cut).trim();
        }
        return flat;
    }

    /** 补上 {@code .md}：用户在对话框里可能自己删掉了后缀。 */
    public static File withMdSuffix(File f) {
        if (f == null) {
            return null;
        }
        String name = f.getName();
        if (name.toLowerCase().endsWith(".md") || name.toLowerCase().endsWith(".markdown")) {
            return f;
        }
        return new File(f.getParentFile(), name + ".md");
    }

    /**
     * 写 UTF-8 文件，<b>不加 BOM</b>。
     *
     * <p>BOM 在 Markdown 里会变成正文开头的一串乱字符（有的预览器直接把它当第一个
     * 字符画出来），而现在的编辑器和浏览器都认无 BOM 的 UTF-8。
     */
    public static void write(File f, String text) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create directory: " + parent);
        }
        Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8);
        try {
            w.write(text);
            // 文件以换行结尾：git diff、cat、各种编辑器都省事。
            if (!text.endsWith("\n")) {
                w.write("\n");
            }
        } finally {
            w.close();
        }
    }

    /**
     * 只解 {@code %XX}，不把 {@code +} 当空格。
     *
     * <p>用 {@link java.net.URLDecoder} 也能解，但它按表单编码把 {@code +} 变成空格 ——
     * 命令、URL、Windows 路径里都有加号。载荷本该是全编码的（{@code +} 会变成
     * {@code %2B}），可一旦哪天有人写了个只编码一半的载荷，那个空格就成了
     * 一个查不出来的错：正文里"a+b"变成"a b"。
     */
    static String percentDecode(String s) {
        if (s == null || s.indexOf('%') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int hi = hex(s.charAt(i + 1));
                int lo = hex(s.charAt(i + 2));
                if (hi >= 0 && lo >= 0) {
                    bytes.write((hi << 4) | lo);
                    i += 2;
                    continue;
                }
            }
            if (bytes.size() > 0) {
                sb.append(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                bytes.reset();
            }
            sb.append(c);
        }
        if (bytes.size() > 0) {
            sb.append(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }
}

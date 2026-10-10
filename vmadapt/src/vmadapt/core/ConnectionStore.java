package vmadapt.core;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 连接配置的持久化：一组虚拟机的连接参数，存成一个 JSON 文件。
 *
 * <p>为什么手写 JSON 而不引第三方库：项目约定不引入任何第三方依赖（见
 * build.ps1 的独立 classpath）。我们需要的只是「一组扁平对象」的读写，
 * Gson/Jackson 的体积和依赖链都不值得。代价是必须自己把转义写对——
 * 这在 Windows 上尤其要命，因为密钥路径是 {@code C:\Users\me\.ssh\id_rsa}，
 * 里面的每个反斜杠在 JSON 里都必须写成 {@code \\}，否则读回来会变成
 * {@code \U}、{@code \.} 这类非法转义而整个文件报废。
 *
 * <p>存储位置：{@code %USERPROFILE%\.vmadapt\connections.json}。
 * 放在用户家目录而不是插件目录，是为了让 TBtools 升级、插件重装时配置不丢。
 *
 * <p>安全性：本格式<b>没有 password 字段</b>，写入时也永远不会写出密码。
 * 认证只依赖私钥路径，私钥本身不进这个文件。
 *
 * <p>容错：文件不存在返回空列表；文件损坏则备份成 {@code .bak} 并返回空列表，
 * 绝不抛异常。配置文件坏掉不应该让插件打不开。
 */
public final class ConnectionStore {

    private static final Charset UTF8 = StandardCharsets.UTF_8;

    /** 配置目录名。 */
    private static final String DIR_NAME = ".vmadapt";

    /** 配置文件名。 */
    private static final String FILE_NAME = "connections.json";

    /** 当前文件格式版本，便于将来迁移。 */
    private static final int FORMAT_VERSION = 1;

    private final Path file;

    /** 最近一次操作是否因为文件损坏而触发了备份，供界面提示用户。 */
    private boolean recoveredFromCorrupt;

    /**
     * 默认存储位置：{@code %USERPROFILE%\.vmadapt\connections.json}。
     */
    public ConnectionStore() {
        this(defaultFile());
    }

    /**
     * 指定存储文件，便于测试和「导入他人配置」这类需求。
     *
     * @param file 配置文件路径；为 null 时退回默认位置
     */
    public ConnectionStore(Path file) {
        this.file = file == null ? defaultFile() : file;
    }

    /** 默认配置文件路径，界面上会显示给用户看。 */
    public static Path defaultFile() {
        String home = System.getProperty("user.home");
        if (home == null || home.trim().isEmpty()) {
            home = System.getenv("USERPROFILE");
        }
        if (home == null || home.trim().isEmpty()) {
            home = ".";
        }
        return Paths.get(home.trim(), DIR_NAME, FILE_NAME);
    }

    /** 本实例对应的配置文件路径。 */
    public Path file() {
        return file;
    }

    /** 上一次读取是否遇到损坏文件（已备份为 .bak 并当作空配置处理）。 */
    public boolean recoveredFromCorrupt() {
        return recoveredFromCorrupt;
    }

    // ------------------------------------------------------------------
    // 读写
    // ------------------------------------------------------------------

    /**
     * 列出所有已保存的连接。
     *
     * <p>文件不存在 → 空列表。文件损坏 → 备份后空列表。
     * 单条记录格式不对（比如缺 host）会被跳过，而不是让整个列表失败：
     * 一条坏记录不该拖垮其它能用的配置。
     *
     * @return 连接列表，永不为 null
     */
    public List<SshTarget> list() {
        recoveredFromCorrupt = false;
        List<SshTarget> out = new ArrayList<SshTarget>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        String text;
        try {
            text = new String(Files.readAllBytes(file), UTF8);
        } catch (Exception e) {
            return out;
        }
        List<String> objects;
        try {
            objects = Json.splitTopLevelObjects(text);
        } catch (RuntimeException e) {
            backupCorrupt();
            return out;
        }
        if (objects == null) {
            backupCorrupt();
            return out;
        }
        for (int i = 0; i < objects.size(); i++) {
            SshTarget t;
            try {
                t = parseOne(objects.get(i));
            } catch (RuntimeException e) {
                t = null;
            }
            if (t != null) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * 保存一个连接；同名（按 label，其次按 user@host:port）则覆盖。
     *
     * <p>用「覆盖」而不是「追加」的理由：用户反复点保存时最不想看到的就是
     * 列表里堆出十几条一模一样的记录。
     *
     * @param t 要保存的连接；为 null 或校验不通过时不写入并返回 false
     * @return 是否写入成功
     */
    public boolean save(SshTarget t) {
        if (t == null) {
            return false;
        }
        List<SshTarget> all = list();
        String key = identityOf(t);
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (identityOf(all.get(i)).equals(key)) {
                all.set(i, t);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            all.add(t);
        }
        return writeAll(all);
    }

    /**
     * 按名称删除一个连接。
     *
     * <p>匹配顺序：先比 label，再比 {@code user@host:port}，最后比 host。
     * 这样用户既可以按别名删，也可以在别名重复时按地址删。
     *
     * @param name 别名或地址
     * @return 是否删掉了一条；未找到返回 false
     */
    public boolean remove(String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        String want = name.trim();
        List<SshTarget> all = list();
        List<SshTarget> kept = new ArrayList<SshTarget>();
        boolean removed = false;
        for (int i = 0; i < all.size(); i++) {
            SshTarget t = all.get(i);
            if (!removed && matchesName(t, want)) {
                removed = true;
                continue;
            }
            kept.add(t);
        }
        if (!removed) {
            return false;
        }
        return writeAll(kept);
    }

    /**
     * 一条配置在界面上显示的名字：有别名就用别名，没有就用 {@code user@host:port}。
     *
     * <p>这个规则原先只写在界面的 {@code MainPanel.displayNameOf} 里，于是"删除"要想
     * 按显示的名字删，就得在界面里重写一遍匹配逻辑。放在这里，界面和存储用的是同一条
     * 规则，不会两边慢慢分岔。
     */
    public static String displayName(SshTarget t) {
        if (t == null) {
            return "";
        }
        String label = t.label == null ? "" : t.label.trim();
        return label.isEmpty() ? t.toDisplay() : label;
    }

    /**
     * 按界面上显示的名字精确删除一条。
     *
     * <h2>为什么不用 {@link #remove(String)}</h2>
     *
     * <p>{@code remove} 的匹配顺序是「别名 → 地址 → <b>主机名</b>」，那个主机名兜底在这里
     * 是有害的：同一个主机上的两条配置（不同用户、或不同端口）会被认成同一条，于是
     * 用户在下拉框里选中了 A，删掉的却是先出现的 B。
     *
     * <p>界面上既然已经明确选中了一条，就该按<b>那一条</b>删，不猜。所以这里只比显示名，
     * 而且只删第一个完全匹配的。
     *
     * @param display 界面上显示的名字
     * @return 是否删掉了一条
     */
    public boolean removeDisplayed(String display) {
        if (display == null || display.trim().isEmpty()) {
            return false;
        }
        String want = display.trim();
        List<SshTarget> all = list();
        List<SshTarget> kept = new ArrayList<SshTarget>();
        boolean removed = false;
        for (int i = 0; i < all.size(); i++) {
            SshTarget t = all.get(i);
            if (!removed && displayName(t).equals(want)) {
                removed = true;
                continue;
            }
            kept.add(t);
        }
        if (!removed) {
            return false;
        }
        return writeAll(kept);
    }

    /**
     * 重命名的结果。
     *
     * <p>分开判"没找到"和"重名"，是因为界面要说不同的话：前者是那条配置已经不在了
     * （另一个窗口删过），后者是名字撞了、让用户换一个。合成一个 false 就只能说
     * "重命名失败"，用户不知道该改什么。
     */
    public enum RenameResult {
        /** 改好了（新旧名字相同也算，用户没改）。 */
        OK,
        /** 界面上那条已经不在了。 */
        NOT_FOUND,
        /** 新名字和另一条配置撞了。 */
        DUPLICATE,
        /** 文件写不进去。 */
        WRITE_FAILED
    }

    /**
     * 改一条配置的别名（也就是界面上显示的名字）。
     *
     * <h2>为什么不直接用 save()</h2>
     *
     * <p>{@link #save(SshTarget)} 是按<b>身份</b>去重后写入的，而身份就是别名（没别名
     * 时才是地址）。改了别名再调 save，会被当成<b>另一条</b>记录存进去，原来那条还留在
     * 原地——用户看到的是"重命名之后多出来一条"。
     *
     * <p>也不允许改成与别人重名：下拉框里两条一模一样的名字分不出谁是谁，
     * 之后点连接、点删除都成了看运气。
     *
     * @param display  界面上显示的名字（要改的那条）
     * @param newLabel 新别名；空白表示清掉别名，显示名退回 {@code user@host:port}
     * @return 结果
     */
    public RenameResult rename(String display, String newLabel) {
        if (display == null || display.trim().isEmpty()) {
            return RenameResult.NOT_FOUND;
        }
        String want = display.trim();
        String label = newLabel == null ? "" : newLabel.trim();

        List<SshTarget> all = list();
        int target = -1;
        for (int i = 0; i < all.size(); i++) {
            if (displayName(all.get(i)).equals(want)) {
                target = i;
                break;
            }
        }
        if (target < 0) {
            return RenameResult.NOT_FOUND;
        }

        if (!label.isEmpty()) {
            for (int i = 0; i < all.size(); i++) {
                if (i != target && label.equals(displayName(all.get(i)))) {
                    return RenameResult.DUPLICATE;
                }
            }
        }

        SshTarget t = all.get(target);
        String current = t.label == null ? "" : t.label.trim();
        if (current.equals(label)) {
            return RenameResult.OK; // 没改动，不必写盘
        }
        t.label = label;
        return writeAll(all) ? RenameResult.OK : RenameResult.WRITE_FAILED;
    }

    /**
     * 按名称载入一个连接。
     *
     * @param name 别名或地址
     * @return 找到的连接；没有则返回 null
     */
    public SshTarget load(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        String want = name.trim();
        List<SshTarget> all = list();
        for (int i = 0; i < all.size(); i++) {
            if (matchesName(all.get(i), want)) {
                return all.get(i);
            }
        }
        return null;
    }

    /**
     * 用给定列表整体覆盖配置文件（导入/导出场景）。
     *
     * @param targets 新列表，可为 null（等于清空）
     * @return 是否写入成功
     */
    public boolean saveAll(List<SshTarget> targets) {
        List<SshTarget> copy = new ArrayList<SshTarget>();
        if (targets != null) {
            for (int i = 0; i < targets.size(); i++) {
                if (targets.get(i) != null) {
                    copy.add(targets.get(i));
                }
            }
        }
        return writeAll(copy);
    }

    /** 配置文件是否存在。 */
    public boolean exists() {
        return Files.isRegularFile(file);
    }

    /**
     * 把配置渲染成 JSON 文本，但不落盘。
     *
     * <p>给测试和「预览」用：测试可以断言转义正确，界面可以显示将要写入的内容。
     */
    public String toJson(List<SshTarget> targets) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"version\": ").append(FORMAT_VERSION).append(",\n");
        sb.append("  \"connections\": [");
        List<SshTarget> list = targets == null ? new ArrayList<SshTarget>() : targets;
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('\n');
            sb.append("    ").append(targetToJson(list.get(i)));
        }
        if (!list.isEmpty()) {
            sb.append('\n').append("  ");
        }
        sb.append("]\n");
        sb.append("}\n");
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 内部：写入
    // ------------------------------------------------------------------

    private boolean writeAll(List<SshTarget> targets) {
        try {
            Path dir = file.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            String json = toJson(targets);
            // 先写临时文件再原子替换：写到一半断电或磁盘满，旧配置仍然完好。
            // 临时文件名必须由本实例的实际文件派生，而不是写死默认名，
            // 否则自定义路径的实例会互相踩踏。
            Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
            Files.write(tmp, json.getBytes(UTF8));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void backupCorrupt() {
        recoveredFromCorrupt = true;
        try {
            // 备份名同样由实际文件名派生，保证自定义路径（测试、导入）也正确，
            // 也避免多份配置互相覆盖备份。
            Path bak = file.resolveSibling(file.getFileName().toString() + ".bak");
            Files.copy(file, bak, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
            // 备份失败也不能让读取失败
        }
    }

    // ------------------------------------------------------------------
    // 内部：序列化
    // ------------------------------------------------------------------

    private static String targetToJson(SshTarget t) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"label\":").append(Json.str(t.label)).append(',');
        sb.append("\"host\":").append(Json.str(t.host)).append(',');
        sb.append("\"port\":").append(t.port > 0 ? t.port : SshTarget.DEFAULT_PORT).append(',');
        sb.append("\"user\":").append(Json.str(t.user)).append(',');
        // keyPath 里的反斜杠在这里被转义成 \\；读回来由 Json.parseString 还原。
        sb.append("\"keyPath\":").append(Json.str(t.keyPath)).append(',');
        sb.append("\"timeoutSeconds\":")
                .append(t.timeoutSeconds > 0 ? t.timeoutSeconds : SshTarget.DEFAULT_TIMEOUT_SECONDS);
        // 注意：这里没有 password 字段，是有意为之，永不添加。
        sb.append('}');
        return sb.toString();
    }

    private static SshTarget parseOne(String obj) {
        String host = Json.field(obj, "host");
        if (host == null || host.trim().isEmpty()) {
            return null; // 没有 host 的记录没有意义，跳过
        }
        SshTarget t = new SshTarget(
                host,
                Json.intField(obj, "port", SshTarget.DEFAULT_PORT),
                Json.field(obj, "user"),
                Json.field(obj, "keyPath"));
        t.label = Json.field(obj, "label");
        int timeout = Json.intField(obj, "timeoutSeconds", SshTarget.DEFAULT_TIMEOUT_SECONDS);
        if (timeout > 0) {
            t.timeoutSeconds = timeout;
        }
        return t;
    }

    // ------------------------------------------------------------------
    // 内部：匹配
    // ------------------------------------------------------------------

    /** 判断一条记录是否叫这个名字。 */
    private static boolean matchesName(SshTarget t, String want) {
        if (t == null) {
            return false;
        }
        if (t.label != null && t.label.trim().equals(want)) {
            return true;
        }
        if (addressOf(t).equals(want)) {
            return true;
        }
        String h = t.host == null ? "" : t.host.trim();
        return !h.isEmpty() && h.equals(want);
    }

    /** 覆盖判定用的身份：优先别名，其次地址。 */
    private static String identityOf(SshTarget t) {
        if (t.label != null && !t.label.trim().isEmpty()) {
            return "label:" + t.label.trim();
        }
        return "addr:" + addressOf(t);
    }

    private static String addressOf(SshTarget t) {
        String h = t.host == null ? "" : t.host.trim();
        String u = t.user == null ? "" : t.user.trim();
        int p = t.port > 0 ? t.port : SshTarget.DEFAULT_PORT;
        return (u.isEmpty() ? "" : u + "@") + h + ":" + p;
    }

    // ==================================================================
    // 极简 JSON 读写
    // ==================================================================
    // 只支持本文件用到的形状：顶层对象里一个数组，数组元素是扁平字符串/数字
    // 对象。刻意不做通用解析器——通用解析器容易写错，而我们只需要这么点。

    /** 内部工具类，不对外暴露。 */
    static final class Json {

        private Json() {
        }

        /**
         * 把字符串编码成 JSON 字符串字面量（含两侧引号）。
         *
         * <p>反斜杠必须第一个处理，否则后面插入的转义反斜杠会被二次转义。
         */
        static String str(String s) {
            if (s == null) {
                return "null";
            }
            StringBuilder sb = new StringBuilder(s.length() + 2);
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"':
                        sb.append("\\\"");
                        break;
                    case '\\':
                        sb.append("\\\\");
                        break;
                    case '\n':
                        sb.append("\\n");
                        break;
                    case '\r':
                        sb.append("\\r");
                        break;
                    case '\t':
                        sb.append("\\t");
                        break;
                    case '\b':
                        sb.append("\\b");
                        break;
                    case '\f':
                        sb.append("\\f");
                        break;
                    default:
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", Integer.valueOf(c)));
                        } else {
                            sb.append(c);
                        }
                        break;
                }
            }
            sb.append('"');
            return sb.toString();
        }

        /**
         * 解码 JSON 字符串字面量；入参应包含两侧引号。
         *
         * <p>{@code \\} 还原成单个反斜杠，这是 Windows 路径能读回来的关键。
         */
        static String unescape(String raw) {
            if (raw == null) {
                return null;
            }
            String s = raw.trim();
            if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
                s = s.substring(1, s.length() - 1);
            }
            StringBuilder sb = new StringBuilder(s.length());
            int i = 0;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c != '\\') {
                    sb.append(c);
                    i++;
                    continue;
                }
                i++;
                if (i >= s.length()) {
                    break;
                }
                char e = s.charAt(i);
                i++;
                switch (e) {
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case '/':
                        sb.append('/');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'u':
                        if (i + 4 <= s.length()) {
                            try {
                                int cp = Integer.parseInt(s.substring(i, i + 4), 16);
                                sb.append((char) cp);
                            } catch (NumberFormatException nfe) {
                                sb.append(e);
                            }
                            i += 4;
                        }
                        break;
                    default:
                        // 非法转义：原样保留，尽量不丢信息
                        sb.append(e);
                        break;
                }
            }
            return sb.toString();
        }

        /**
         * 取出顶层对象里某个字段的值，返回去掉引号后的字符串。
         *
         * <p>只在该对象的直接层级里找，不递归——我们的对象是扁平的，
         * 递归查找只会让「keyPath」误匹配到嵌套结构。
         *
         * @return 字段值；不存在或为 null 时返回 null
         */
        static String field(String obj, String name) {
            if (obj == null || name == null) {
                return null;
            }
            int i = obj.indexOf('"' + name + '"');
            while (i >= 0) {
                int colon = obj.indexOf(':', i + name.length() + 2);
                if (colon < 0) {
                    return null;
                }
                int v = skipWs(obj, colon + 1);
                if (v >= obj.length()) {
                    return null;
                }
                char c = obj.charAt(v);
                if (c == '"') {
                    int end = endOfString(obj, v);
                    if (end < 0) {
                        return null;
                    }
                    return unescape(obj.substring(v, end + 1));
                }
                // 非字符串（数字、true、false、null）：读到分隔符为止
                int e = v;
                while (e < obj.length()) {
                    char d = obj.charAt(e);
                    if (d == ',' || d == '}' || d == ']') {
                        break;
                    }
                    e++;
                }
                String token = obj.substring(v, e).trim();
                if (token.equals("null")) {
                    return null;
                }
                return token;
            }
            return null;
        }

        /** 取整数字段，缺失或非法时返回兜底值。 */
        static int intField(String obj, String name, int fallback) {
            String v = field(obj, name);
            if (v == null || v.trim().isEmpty()) {
                return fallback;
            }
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        /**
         * 把文件文本切成顶层 {@code connections} 数组里的一个个对象文本。
         *
         * <p>用花括号计数来切分，而不是正则：对象里的字符串可能包含
         * {@code {} } 这样的字符，只有跟踪「是否在字符串里」才切得对。
         *
         * @return 对象文本列表；结构明显不是我们格式时返回 null（调用方据此备份）
         */
        static List<String> splitTopLevelObjects(String text) {
            if (text == null) {
                return null;
            }
            String t = text.trim();
            if (t.isEmpty()) {
                return new ArrayList<String>();
            }
            int arr = t.indexOf("\"connections\"");
            if (arr < 0) {
                return null;
            }
            int br = t.indexOf('[', arr);
            if (br < 0) {
                return null;
            }
            int end = matchingBracket(t, br);
            if (end < 0) {
                return null;
            }
            String body = t.substring(br + 1, end);
            List<String> objects = new ArrayList<String>();
            int i = 0;
            while (i < body.length()) {
                char c = body.charAt(i);
                if (c == '{') {
                    int close = matchingBrace(body, i);
                    if (close < 0) {
                        return null;
                    }
                    objects.add(body.substring(i, close + 1));
                    i = close + 1;
                } else if (c == '"') {
                    int s = endOfString(body, i);
                    if (s < 0) {
                        return null;
                    }
                    i = s + 1;
                } else {
                    i++;
                }
            }
            return objects;
        }

        private static int matchingBracket(String s, int open) {
            int depth = 0;
            int i = open;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '"') {
                    int e = endOfString(s, i);
                    if (e < 0) {
                        return -1;
                    }
                    i = e + 1;
                    continue;
                }
                if (c == '[') {
                    depth++;
                } else if (c == ']') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
                i++;
            }
            return -1;
        }

        private static int matchingBrace(String s, int open) {
            int depth = 0;
            int i = open;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '"') {
                    int e = endOfString(s, i);
                    if (e < 0) {
                        return -1;
                    }
                    i = e + 1;
                    continue;
                }
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
                i++;
            }
            return -1;
        }

        /** 返回字符串字面量的结束引号下标，找不到返回 -1。 */
        private static int endOfString(String s, int open) {
            int i = open + 1;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '\\') {
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    return i;
                }
                i++;
            }
            return -1;
        }

        private static int skipWs(String s, int from) {
            int i = from;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    i++;
                } else {
                    break;
                }
            }
            return i;
        }
    }
}

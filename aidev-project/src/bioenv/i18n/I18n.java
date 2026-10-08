package bioenv.i18n;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.swing.SwingUtilities;

/**
 * 界面语言的中枢：当前语言、文案查询、切换通知、磁盘持久化。
 *
 * <h2>默认语言是中文</h2>
 *
 * <p>这是用户明确要求的行为，体现在三处，任何一处都不许「顺手」改掉：
 * <ol>
 *   <li>字段初值 {@link #current} 是 {@link Lang#ZH}；</li>
 *   <li>{@link Lang#fromCode(String)} 无法识别时回退到 ZH；</li>
 *   <li>读盘失败、文件不存在、文件损坏时一律保持 ZH。</li>
 * </ol>
 *
 * <h2>API 为什么这么短</h2>
 *
 * <p>{@code I18n.t("menu.detect")} 是未来几百个调用点的标准写法。
 * 每多一个字符（例如 {@code I18n.get().text(...)} 或
 * {@code Messages.getString(...)}）都要乘以调用点数量，最终决定
 * 「大家愿不愿意用它」而不是「用它更规范」。所以：
 * <ul>
 *   <li>{@code t(key)} 取当前语言文案；</li>
 *   <li>{@code t(key, args...)} 带占位符；</li>
 *   <li>{@code set(lang)} 切换；</li>
 *   <li>{@code addListener(r)} 注册刷新回调。</li>
 * </ul>
 *
 * <h2>t() 永不返回 null</h2>
 *
 * <p>键缺失时按 {@link Lang#EN} → 键名本身 的顺序回退。
 * 先试英文而不是直接返回键名，是因为「中文漏译」比「英文漏译」更可能发生
 * （开发者用中文思考、却常常先写英文原文再补中文），此时英文原文至少
 * 还是句人话。两者都没有才返回键名——难看，但可读、可搜索、能一眼看出
 * 是漏译而不是显示成了空白。返回 null 会让 {@code JLabel} 显示 "null"
 * 或直接 NPE，那是最糟的结果。
 *
 * <h2>线程安全</h2>
 *
 * <p>语言状态被界面线程和后台工作线程同时读取（扫描器要在报告里写文案），
 * 所以：
 * <ul>
 *   <li>{@link #current} 是 {@code volatile}——读多写少，且读方永远只关心
 *       「当前值是哪个」，不需要复合原子性；</li>
 *   <li>监听器表用 {@link CopyOnWriteArrayList}——遍历时（通知）可能有人
 *       注册/注销自己，普通 ArrayList 会抛
 *       {@link java.util.ConcurrentModificationException}；</li>
 *   <li>通知以 {@link SwingUtilities#invokeLater} 派发到 EDT——监听器会去动
 *       界面组件，绝不能在后台线程里直接调用。若当前已经在 EDT 上（用户在
 *       EDT 里点了切换按钮，这是最常见的情况），也仍然走 invokeLater 排队，
 *       而不是直接调用：这样同一个监听器永远不会在「有时同步、有时异步」
 *       两种时序下被触发，避免出只有特定路径才复现的重入 bug。</li>
 * </ul>
 */
public final class I18n {

    /**
     * 当前语言。
     *
     * <p>{@code volatile} 而不是加锁：唯一的不变量是「这个引用指向一个合法
     * {@link Lang}」，volatile 的可见性保证已经足够。加锁反而会在每次
     * {@code t()} 调用（可能每秒几百次）上付出无谓代价。
     */
    private static volatile Lang current = Lang.ZH;

    /**
     * 语言变化时被调用的回调。
     *
     * <p>回调的契约：<b>只负责刷新文案，不做别的</b>。它可能被调用多次
     * （例如用户反复切换），且一定会被派发到 EDT 上。
     */
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<Runnable>();

    /**
     * 语言文件路径，可被 {@link #setFile(File)} 覆盖（自检用）。
     *
     * <p>{@code volatile}：自检类会从别的线程改它。
     */
    private static volatile File file = defaultFile();

    /** {@link #load()} 是否已经跑过，避免重复读盘。 */
    private static volatile boolean loaded;

    private I18n() {
        // 纯静态工具类。
    }

    // ------------------------------------------------------------------
    // 语言文件位置
    // ------------------------------------------------------------------

    /**
     * 语言文件默认位置：{@code %USERPROFILE%\.TBtools\BioEnvCaller\language.txt}。
     *
     * <p>刻意与
     * {@code bioenv.core.DiscoveryCache#defaultFile()} <b>同目录</b>：
     * 用户要备份、清理或排查插件状态时，只会去翻一个 {@code .TBtools}
     * 目录。把语言文件放到别处（例如 {@code .vmadapt}）会让「这个插件的
     * 数据到底在哪」变成两个答案，日后谁也说不清哪个文件该跟着走。
     */
    public static File defaultFile() {
        String home = System.getProperty("user.home");
        File dir = new File(home, ".TBtools" + File.separator + "BioEnvCaller");
        return new File(dir, "language.txt");
    }

    /**
     * 覆盖语言文件位置。
     *
     * <p>存在的理由只有一个：自检类不能去改用户真实的语言设置
     * （跑一次测试把用户的界面变成英文，是决不能接受的行为）。
     * 生产代码不应调用它。
     */
    public static void setFile(File f) {
        file = (f == null) ? defaultFile() : f;
        loaded = false;
    }

    /** 当前语言文件位置。 */
    public static File getFile() {
        return file;
    }

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    /**
     * 当前语言。永不返回 null。
     */
    public static Lang get() {
        return current;
    }

    /**
     * 切换语言：更新状态 → 落盘 → 通知监听器。
     *
     * <p>顺序是有意的：先改状态再通知，这样监听器里读
     * {@link #get()} 拿到的一定是新语言；先通知的话，监听器可能
     * 读到旧值并刷新成旧文案，然后永远不再被通知（因为状态已经更新过）。
     *
     * <p>传 null 或与当前相同的语言：什么都不做，也<b>不会</b>通知。
     * 「点了按钮但语言没变」不该触发一次界面重建。
     *
     * @param lang 目标语言；null 视为无操作
     * @return 语言是否真的发生了变化
     */
    public static boolean set(Lang lang) {
        if (lang == null || lang == current) {
            return false;
        }
        current = lang;
        save(lang);
        notifyListeners();
        return true;
    }

    /**
     * 在两种语言之间来回切，供切换按钮直接调用。
     *
     * @return 切换后的语言
     */
    public static Lang toggle() {
        set(current.other());
        return current;
    }

    /**
     * 切换按钮上应该显示的文字。
     *
     * <p>显示的是<b>目标语言</b>的自身名称：中文界面显示 {@code English}，
     * 英文界面显示 {@code 中文}。理由见 {@link Lang#displayName()}。
     *
     * @return 按钮文案；永不返回 null
     */
    public static String toggleText() {
        return t(current.other() == Lang.EN ? Strings.LANG_SWITCH_TO_EN
                : Strings.LANG_SWITCH_TO_ZH);
    }

    // ------------------------------------------------------------------
    // 文案查询
    // ------------------------------------------------------------------

    /**
     * 取当前语言下的文案。
     *
     * <p>永不返回 null，见类注释的「t() 永不返回 null」一节。
     *
     * @param key 键名
     * @return 文案；{@code key} 为 null 时返回空串
     */
    public static String t(String key) {
        if (key == null) {
            return "";
        }
        String s = Strings.raw(current, key);
        if (s != null) {
            return s;
        }
        // 回退一：英文原文。中文漏译比英文漏译更常见，有英文也胜过显示键名。
        String en = Strings.raw(Lang.EN, key);
        if (en != null) {
            return en;
        }
        // 回退二：键名本身。难看，但可读、可搜索、能一眼认出是漏译。
        return key;
    }

    /**
     * 取当前语言下的文案，并替换 {@code {0}}、{@code {1}} 等占位符。
     *
     * <p><b>刻意不用 {@link java.text.MessageFormat}</b>：它把单引号当转义符，
     * 而本插件的英文原文里单引号极多（{@code 'Detect conda environments'}、
     * {@code 'Rebuild from form'}）。用 MessageFormat 时那些引号会吞掉后面的
     * 占位符，把 {@code {0}} 原样留在界面上，而且不报错——这是本包最容易踩、
     * 也最难查的一个坑，所以宁可自己写十行替换。
     *
     * <p>替换规则（有意做得简单可预测）：
     * <ul>
     *   <li>只识别 {@code {数字}} 形式，从 {@code {0}} 开始；</li>
     *   <li>参数为 null 时替换成空串，而不是 "null"；</li>
     *   <li>越界的占位符（文案里有 {@code {3}} 但只传了 2 个参数）
     *       <b>原样保留</b> {@code {3}}，而不是替换成空——保留才能让人
     *       一眼看出是调用点少传了参数，替换成空会变成一个语义不通的句子，
     *       根本看不出哪里错了；</li>
     *   <li>文案里没有对应占位符的多余参数被忽略。</li>
     * </ul>
     *
     * @param key  键名
     * @param args 占位符的值；可为 null
     * @return 替换后的文案；永不返回 null
     */
    public static String t(String key, Object... args) {
        String template = t(key);
        if (args == null || args.length == 0 || template.indexOf('{') < 0) {
            return template;
        }
        return substitute(template, args);
    }

    /**
     * 自己实现的占位符替换。
     *
     * <p>不用正则：正则需要转义、且对「文案里有落单的 {@code {}」（例如
     * shell 片段、JSON 示例）要格外小心。手写扫描对每个字符只做一次判断，
     * 行为完全可预测，也方便在越界时决定保留原文。
     */
    private static String substitute(String template, Object[] args) {
        StringBuilder sb = new StringBuilder(template.length() + 32);
        int i = 0;
        int n = template.length();
        while (i < n) {
            char c = template.charAt(i);
            if (c != '{') {
                sb.append(c);
                i++;
                continue;
            }
            // 从 '{' 后面开始读连续数字，直到 '}'。
            int j = i + 1;
            while (j < n && Character.isDigit(template.charAt(j))) {
                j++;
            }
            boolean wellFormed = (j > i + 1) && (j < n) && (template.charAt(j) == '}');
            if (!wellFormed) {
                // 不是占位符（例如落单的 '{'），原样输出。
                sb.append(c);
                i++;
                continue;
            }
            int index;
            try {
                index = Integer.parseInt(template.substring(i + 1, j));
            } catch (NumberFormatException e) {
                // 数字长到超出 int（理论上可能），当普通文本处理。
                sb.append(template, i, j + 1);
                i = j + 1;
                continue;
            }
            if (index >= 0 && index < args.length) {
                Object a = args[index];
                sb.append(a == null ? "" : a.toString());
            } else {
                // 越界：原样保留 {n}，让人看出调用点少传了参数。
                sb.append(template, i, j + 1);
            }
            i = j + 1;
        }
        return sb.toString();
    }

    /**
     * 取指定语言下的文案，与当前语言无关。
     *
     * <p>用于「用某语言显示」的少数场景（例如语言选择列表里同时列出两种语言）。
     * 回退规则与 {@link #t(String)} 相同。
     */
    public static String tIn(Lang lang, String key) {
        if (key == null) {
            return "";
        }
        String s = Strings.raw(lang, key);
        if (s != null) {
            return s;
        }
        String en = Strings.raw(Lang.EN, key);
        return en != null ? en : key;
    }

    // ------------------------------------------------------------------
    // 监听器
    // ------------------------------------------------------------------

    /**
     * 注册语言变化回调。
     *
     * <p>回调一定在 EDT 上被调用，可以直接改界面组件文案。
     * 同一个 Runnable <b>不要</b>重复注册（会收到多次通知）；
     * 通常的做法是注册一个一次性构建的 lambda/匿名类，或在面板销毁时
     * 用 {@link #removeListener(Runnable)} 注销。
     *
     * @param r 回调；null 被忽略
     */
    public static void addListener(Runnable r) {
        if (r != null) {
            LISTENERS.add(r);
        }
    }

    /**
     * 注销回调。
     *
     * <p>界面长期驻留（例如 TBtools 的主窗口）时不一定需要；
     * 但对话框/面板被反复创建的地方必须调用，否则每开一次就多留一份
     * 引用，累积起来会让一次语言切换执行几百个已废弃的刷新。
     */
    public static void removeListener(Runnable r) {
        if (r != null) {
            LISTENERS.remove(r);
        }
    }

    /** 当前注册的回调数量，供自检使用。 */
    public static int listenerCount() {
        return LISTENERS.size();
    }

    /**
     * 把通知派发到 EDT。
     *
     * <p>异常被逐个吞掉并继续通知其余监听器：某个面板的刷新代码抛异常，
     * 不应该导致排在它后面的所有面板都不刷新（那会让界面处于
     * 一半新语言、一半旧语言的错乱状态）。
     */
    private static void notifyListeners() {
        if (LISTENERS.isEmpty()) {
            return;
        }
        final List<Runnable> snapshot = new ArrayList<Runnable>(LISTENERS);
        Runnable dispatch = new Runnable() {
            @Override
            public void run() {
                for (Runnable r : snapshot) {
                    try {
                        r.run();
                    } catch (RuntimeException e) {
                        // 一个监听器失败不能连累其它的，否则界面会花掉一半。
                    }
                }
            }
        };
        // 无条件走 invokeLater，即使当前已经在 EDT 上（用户在 EDT 里点切换按钮，
        // 这是最常见的情况）也不直接调用。差异在时序上，不在结果上：无条件排队
        // 才能保证同一个监听器永远不在「有时同步、有时异步」两种时序下被触发，
        // 否则会出只有特定点击路径才复现的重入 bug（例如监听器里又调用了 set()）。
        SwingUtilities.invokeLater(dispatch);
    }

    // ------------------------------------------------------------------
    // 持久化
    // ------------------------------------------------------------------

    /**
     * 从磁盘读取上次的语言选择。
     *
     * <p>在插件启动时调用一次。任何失败（文件不存在、读不动、内容无法识别）
     * 都静默保持当前语言（默认中文）——语言偏好丢了只是小不便，
     * 为它在启动路径上抛异常会让插件直接起不来。
     *
     * @return 读到的语言；没有可用记录时为 {@link Lang#ZH}
     */
    public static Lang load() {
        File f = file;
        loaded = true;
        if (f == null || !f.isFile()) {
            current = Lang.ZH;
            return current;
        }
        BufferedReader br = null;
        try {
            // 显式 UTF-8：文件里可能被人手工写了注释，但不该出现非 ASCII；
            // 指定编码是为了避免平台默认编码带来的差异。
            br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), "UTF-8"));
            String line = br.readLine();
            current = Lang.fromCode(line);
            return current;
        } catch (Exception e) {
            current = Lang.ZH;
            return current;
        } finally {
            close(br);
        }
    }

    /** 若尚未读盘则读一次；已读则什么都不做。 */
    public static void loadOnce() {
        if (!loaded) {
            load();
        }
    }

    /**
     * 把语言写到磁盘。失败静默。
     *
     * <p>为什么失败要静默：写不进去最常见的原因是目录只读或被杀软锁住，
     * 这两种情况下用户仍然可以正常用完这一次会话，只是下次启动回到中文。
     * 为此弹一个错误框，代价远大于收益。
     */
    private static void save(Lang lang) {
        File f = file;
        if (f == null || lang == null) {
            return;
        }
        BufferedWriter bw = null;
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            bw = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(f), "UTF-8"));
            // 只写代码，不写枚举名，也不写任何其它内容：这个文件要能被用户
            // 手工改成 en 或 zh，所以格式越简单越好。
            bw.write(lang.code());
            bw.write("\n");
        } catch (Exception ignored) {
            // 见方法注释：写不进去不影响本次会话可用。
        } finally {
            close(bw);
        }
    }

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
                // nothing useful to do
            }
        }
    }

    /**
     * 仅供自检：把内存状态重置为默认中文，不影响磁盘。
     *
     * <p>生产代码不应调用。存在的理由是自检必须能在不读用户真实
     * 语言文件的前提下验证「默认就是 ZH」。
     */
    public static void resetForTest() {
        current = Lang.ZH;
        loaded = false;
    }

    /**
     * 仅供自检：直接对任意模板套用占位符替换。
     *
     * <p>为什么需要它：{@link #substitute} 的边界行为（越界占位符保留原文、
     * 落单的 {@code {} 原样输出、参数为 null 变空串）没法通过固定文案验证，
     * 而那些正是最容易写错的地方。公开一个入口给自检，比为了测试
     * 往语言包里塞一堆畸形文案要干净得多。
     *
     * <p>生产代码不应调用。
     */
    public static String substituteForTest(String template, Object... args) {
        if (template == null) {
            return "";
        }
        if (args == null || args.length == 0) {
            return template;
        }
        return substitute(template, args);
    }
}

package bioenv.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.UIManager;
import javax.swing.border.Border;

/**
 * 界面风格。
 *
 * <h2>三套风格</h2>
 *
 * <ul>
 *   <li>{@link #CLASSIC} —— 现在这套 Swing 原生外观，<b>默认就是它</b>；</li>
 *   <li>{@link #PERSONAL} —— 扁平、圆角、浅色卡片；</li>
 *   <li>{@link #DARK} —— 和上面同一套画法，只换一组深色取值。</li>
 * </ul>
 *
 * <h2>为什么默认是经典</h2>
 *
 * <p>风格是给人挑的，不是替人决定的。默认保持原样还带来一个好处：所有回归测试跑的
 * 都是原来的那条路径，新代码只在你主动切过去之后才生效。
 *
 * <h2>经典模式怎么"还原"</h2>
 *
 * <p>取值不是写死的常量，而是分两路：新风格读自己的表；经典读 {@code UIManager} 里
 * 那套原生取值。{@link #bg}／{@link #fg}／{@link #font}／{@link #border} 还会把控件
 * 原来的值记在客户属性里，切回经典时原样还回去 —— 这样来回切几次也不会越切越花。
 *
 * <h2>选择会记住</h2>
 *
 * <p>存成 {@code %USERPROFILE%\.TBtools\BioEnvCaller\theme.txt}，和语言、连接方式放在
 * 同一个目录里，用户只需要知道一个地方。
 */
public final class UiTheme {

    public static final String CLASSIC = "classic";
    public static final String PERSONAL = "personal";
    public static final String DARK = "dark";

    private static final String KEY = "bioenv.theme.";

    // ------------------------------------------------------------------
    // 取值表
    // ------------------------------------------------------------------

    private static final class T {
        final Color appBg, surface, sunken, line, hair;
        final Color text, text2, text3;
        final Color accent, accentSoft, accentLine;
        final Color fieldBg, termBg, termFg, termMuted;
        final Color ok, selBg, selText;
        final int radius;
        final boolean flat;

        T(int appBg, int surface, int sunken, int line, int hair,
          int text, int text2, int text3,
          int accent, int accentSoft, int accentLine,
          int fieldBg, int termBg, int termFg, int termMuted,
          int ok, int selBg, int selText, int radius, boolean flat) {
            this.appBg = new Color(appBg);
            this.surface = new Color(surface);
            this.sunken = new Color(sunken);
            this.line = new Color(line);
            this.hair = new Color(hair);
            this.text = new Color(text);
            this.text2 = new Color(text2);
            this.text3 = new Color(text3);
            this.accent = new Color(accent);
            this.accentSoft = new Color(accentSoft);
            this.accentLine = new Color(accentLine);
            this.fieldBg = new Color(fieldBg);
            this.termBg = new Color(termBg);
            this.termFg = new Color(termFg);
            this.termMuted = new Color(termMuted);
            this.ok = new Color(ok);
            this.selBg = new Color(selBg);
            this.selText = new Color(selText);
            this.radius = radius;
            this.flat = flat;
        }
    }

    /** 经典：扁平=false，颜色全部走 UIManager，这里只占位。 */
    private static final T CLASSIC_T = new T(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false);

    /** 个性：浅色卡片 + 靛蓝主色。 */
    private static final T PERSONAL_T = new T(
            0xEAEAF1, 0xFFFFFF, 0xF7F7FA, 0xE7E7EE, 0xEFEFF4,
            0x15151A, 0x63636F, 0x9A9AA6,
            0x4F46E5, 0xEEF0FF, 0xDCDEFB,
            0xFFFFFF, 0xFFFFFF, 0x15151A, 0x9A9AA6,
            0x0B9366, 0xEEF0FF, 0x2C25C0, 18, true);

    /** 深色：同一套画法，换取值。 */
    private static final T DARK_T = new T(
            0x101014, 0x191920, 0x20202A, 0x2B2B36, 0x24242E,
            0xEFEFF5, 0xA0A0AE, 0x6E6E7E,
            0x8B84FF, 0x22203C, 0x3A3766,
            0x20202A, 0x0A0F1C, 0xE8ECF5, 0x7C8598,
            0x34D399, 0x22203C, 0xC7C2FF, 18, true);

    private static T tokens = CLASSIC_T;

    // ------------------------------------------------------------------
    // 当前风格
    // ------------------------------------------------------------------

    private static String current = CLASSIC;

    private static final List<Runnable> listeners = new ArrayList<Runnable>();

    private UiTheme() {
    }

    public static String current() {
        return current;
    }

    /** 新风格（个性／深色）返回 true；经典返回 false。 */
    public static boolean flat() {
        return tokens.flat;
    }

    /**
     * 三套风格各自的界面名字见 {@code Strings.THEME_*}，由 {@link ThemeSwitcher} 取用。
     *
     * <p>这里原来有一个 {@code label(id)} 返回写死的中文。名字是界面文案，不是画法，
     * 放在这个纯画法与取值的类里会让它被迫依赖 {@code bioenv.i18n}——而它一直刻意
     * 不依赖。那个方法全项目只有风格切换器一个调用者，所以跟着调用者搬走了。
     */

    public static String[] all() {
        return new String[]{CLASSIC, PERSONAL, DARK};
    }

    /**
     * 换一套风格：先切取值，再落盘，最后通知所有注册过的刷新回调。
     *
     * <p>通知走 EDT —— 回调会去动控件。
     */
    private static boolean explicit;

    public static void set(final String id) {
        explicit = true;
        final String want = (PERSONAL.equals(id) || DARK.equals(id)) ? id : CLASSIC;
        if (want.equals(current)) {
            return;
        }
        current = want;
        tokens = tokenFor(want);
        save(want);
        javax.swing.SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < listeners.size(); i++) {
                    listeners.get(i).run();
                }
            }
        });
    }

    /**
     * 让 Swing 按当前表把整棵控件树重装一遍（方案 ③ 的核心动作）。
     *
     * <p>调用方在这一步之后要把自绘的东西补回来 —— 见 {@link UiThemeTables#refresh}。
     */
    public static void refreshTree(Container root) {
        UiThemeTables.refresh(root);
    }

    public static void addListener(Runnable r) {
        if (r != null) {
            listeners.add(r);
        }
    }

    private static T tokenFor(String id) {
        if (PERSONAL.equals(id)) {
            return PERSONAL_T;
        }
        if (DARK.equals(id)) {
            return DARK_T;
        }
        return CLASSIC_T;
    }

    // ------------------------------------------------------------------
    // 取值
    // ------------------------------------------------------------------

    private static Color ui(String key, Color fallback) {
        Color c = UIManager.getColor(key);
        return c != null ? c : fallback;
    }

    public static Color appBg() {
        return flat() ? tokens.appBg : ui("Panel.background", new Color(0xEE, 0xEE, 0xEE));
    }

    public static Color surface() {
        return flat() ? tokens.surface : ui("Panel.background", new Color(0xEE, 0xEE, 0xEE));
    }

    public static Color sunken() {
        return flat() ? tokens.sunken : ui("TextField.background", Color.WHITE);
    }

    public static Color line() {
        return flat() ? tokens.line : new Color(0xB4, 0xB4, 0xBC);
    }

    public static Color hair() {
        return flat() ? tokens.hair : new Color(0xD4, 0xD4, 0xDA);
    }

    public static Color text() {
        return flat() ? tokens.text : ui("Label.foreground", Color.BLACK);
    }

    public static Color text2() {
        return flat() ? tokens.text2 : new Color(0x47, 0x47, 0x51);
    }

    public static Color text3() {
        return flat() ? tokens.text3 : new Color(0x6E, 0x6E, 0x78);
    }

    public static Color accent() {
        return flat() ? tokens.accent : new Color(0x1A, 0x5F, 0xB4);
    }

    public static Color accentSoft() {
        return flat() ? tokens.accentSoft : new Color(0xEE, 0xF3, 0xFB);
    }

    public static Color accentLine() {
        return flat() ? tokens.accentLine : new Color(0xCC, 0xDD, 0xEE);
    }

    public static Color fieldBg() {
        return flat() ? tokens.fieldBg : ui("TextField.background", Color.WHITE);
    }

    public static Color termBg() {
        return flat() ? tokens.termBg : ui("TextArea.background", Color.WHITE);
    }

    public static Color termFg() {
        return flat() ? tokens.termFg : ui("TextArea.foreground", Color.BLACK);
    }

    public static Color termMuted() {
        return flat() ? tokens.termMuted : new Color(0x88, 0x88, 0x88);
    }

    public static Color ok() {
        return flat() ? tokens.ok : new Color(0x1B, 0x7F, 0x3B);
    }

    public static Color selBg() {
        return flat() ? tokens.selBg : new Color(0xE7, 0xE7, 0xFB);
    }

    public static Color selText() {
        return flat() ? tokens.selText : new Color(0x2C, 0x25, 0xC0);
    }

    public static int radius() {
        return flat() ? tokens.radius : 0;
    }

    // ------------------------------------------------------------------
    // 字体
    // ------------------------------------------------------------------

    /**
     * 按顺序尝试的中文字体。找不到就退回逻辑字体 —— 插件要装到别人机器上，
     * 不能假定某个字体一定存在。经典模式下返回 null，表示"别动原生字体"。
     */
    private static final String[] FAMILIES = {
        "Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC",
        "Noto Sans CJK SC", "Source Han Sans SC", "SimHei",
    };

    private static String family;

    private static synchronized String family() {
        if (family == null) {
            Set<String> have = new HashSet<String>(Arrays.asList(
                    GraphicsEnvironment.getLocalGraphicsEnvironment()
                            .getAvailableFontFamilyNames()));
            for (String f : FAMILIES) {
                if (have.contains(f)) {
                    family = f;
                    break;
                }
            }
            if (family == null) {
                family = Font.SANS_SERIF;
            }
        }
        return family;
    }

    /** 新风格下要用的字体；经典模式下返回 null。 */
    public static Font ui(int size, boolean bold) {
        return flat() ? new Font(family(), bold ? Font.BOLD : Font.PLAIN, size) : null;
    }

    /** 等宽字体（命令预览、日志）。经典模式下返回 null。 */
    public static Font mono(int size, boolean bold) {
        return flat() ? new Font(Font.MONOSPACED, bold ? Font.BOLD : Font.PLAIN, size)
                : null;
    }

    // ------------------------------------------------------------------
    // 按主题改控件（经典时还原）
    // ------------------------------------------------------------------

    private static Object original(JComponent c, String what, Object now) {
        // 用一个独立标记表示"抓过了"，而不是用"值不为 null"来判断。
        //
        // 以前是 saved == null 才记 —— 原值本身是 null 的控件（比如本来没有边框的
        // 标签）就永远记不下来：切回经典时再调用，它会把当时那套新风格的边框当成
        // "原值"记下，然后原样"还原"。表现就是参数名标签在经典模式下还带着浅底标签
        // 的边框，而且来回切会越切越花。
        String flag = KEY + what + ".saved";
        if (!Boolean.TRUE.equals(c.getClientProperty(flag))) {
            c.putClientProperty(KEY + what, now);
            c.putClientProperty(flag, Boolean.TRUE);
            return now;
        }
        return c.getClientProperty(KEY + what);
    }

    @SuppressWarnings("unchecked")
    private static <X> X back(JComponent c, String what) {
        return (X) c.getClientProperty(KEY + what);
    }

    /** 背景：新风格用给定色，经典还回控件原本的底色。 */
    public static void bg(JComponent c, Color personal) {
        Color was = (Color) original(c, "bg", c.getBackground());
        c.setBackground(flat() ? personal : was);
    }

    /** 前景色：同上。 */
    public static void fg(JComponent c, Color personal) {
        Color was = (Color) original(c, "fg", c.getForeground());
        c.setForeground(flat() ? personal : was);
    }

    /** 字体：传 null 表示这套风格不动字体（经典就是这么用的）。 */
    public static void font(JComponent c, Font personal) {
        Font was = (Font) original(c, "font", c.getFont());
        if (flat() && personal != null) {
            c.setFont(personal);
        } else if (was != null) {
            c.setFont(was);
        }
    }

    /** 边框：同上。 */
    public static void border(JComponent c, Border personal) {
        Border was = (Border) original(c, "border", c.getBorder());
        c.setBorder(flat() && personal != null ? personal : was);
    }

    /** 换成新风格的按钮外观；经典模式下把原生外观装回去。 */
    public static void buttons(Container root) {
        if (root == null) {
            return;
        }
        Component[] kids = root.getComponents();
        for (int i = 0; i < kids.length; i++) {
            if (kids[i] instanceof javax.swing.JButton
                    && !(kids[i] instanceof javax.swing.plaf.basic.BasicArrowButton)) {
                // 只给"真正的按钮"换外观。
                //
                // JCheckBox／JRadioButton 也是 AbstractButton，但它们的方框和勾是各自
                // 的 UI 画的：换成扁平按钮 UI 之后勾就没人画了（"能勾选但看不到 ✓"），
                // 而且会套上 14 像素横向内边距，方框变成横的长方形。
                //
                // 微调箭头（BasicArrowButton）同理：箭头是它自己画的，换了就没了。
                AbstractButton b = (AbstractButton) kids[i];
                if (flat()) {
                    b.setUI(new FlatButtonUI(isPrimary(b), isGhost(b)));
                } else {
                    b.updateUI();
                    FlatButtonUI.uninstall(b);
                }
            }
            if (kids[i] instanceof Container) {
                buttons((Container) kids[i]);
            }
        }
    }

    /** 只读一次：文件里的选择在第一次构造界面前就要生效。 */
    private static boolean loaded;

    public static synchronized void loadOnce() {
        if (!loaded) {
            loaded = true;
            if (!explicit) {
                load();          // 程序里已经指定过风格就不要用文件里的覆盖它
            }
        }
    }

    /** 名字里带 main/run 的当主按钮，给实心底色。 */
    /** 标成幽灵按钮：平时不画底和框。 */
    public static void ghost(AbstractButton b) {
        b.putClientProperty("bioenv.ghost", Boolean.TRUE);
    }

    private static boolean isGhost(AbstractButton b) {
        return Boolean.TRUE.equals(b.getClientProperty("bioenv.ghost"));
    }

    private static boolean isPrimary(AbstractButton b) {
        Object p = b.getClientProperty("bioenv.primary");
        return Boolean.TRUE.equals(p);
    }

    /** 标记一个按钮为主按钮（实心）。 */
    public static void primary(AbstractButton b) {
        b.putClientProperty("bioenv.primary", Boolean.TRUE);
    }

    // ------------------------------------------------------------------
    // 按"角色"刷整棵树
    // ------------------------------------------------------------------

    /**
     * 给控件标一个角色：{@code surface}／{@code app}／{@code term}／{@code muted}／
     * {@code text}／{@code accent}。
     *
     * <p>用客户属性而不是 {@code setName}：控件名是回归测试的抓手（bioenv.makeWorkdir
     * 那一批），拿它兼职做主题标记迟早会撞车。
     */
    public static void role(JComponent c, String r) {
        c.putClientProperty(KEY + "role", r);
    }

    /**
     * 给插件里<b>独立开的窗口</b>也刷一遍主题。
     *
     * <p>选项窗口（「在新窗口中打开」）是个独立的 {@code JFrame}，连接窗口是个
     * {@code JDialog} —— 它们都不在主面板的组件树里，所以主面板那几遍刷新永远走不到，
     * 里面会一直保持原生外观（原生下拉框、原生按钮）。窗口建好之后单独调一次这里。
     */
    public static void applyTo(Container root) {
        if (root == null) {
            return;
        }
        applyRoles(root);
        buttons(root);
        decorate(root);
    }

    /** 深度遍历，把带角色的控件按当前风格刷一遍。 */
    public static void applyRoles(Container root) {
        if (root == null) {
            return;
        }
        Component[] kids = root.getComponents();
        for (int i = 0; i < kids.length; i++) {
            Component k = kids[i];
            if (k instanceof JComponent) {
                Object r = ((JComponent) k).getClientProperty(KEY + "role");
                if (r != null) {
                    applyRole((JComponent) k, String.valueOf(r));
                }
            }
            if (k instanceof Container) {
                applyRoles((Container) k);
            }
        }
    }

    private static void applyRole(JComponent c, String r) {
        if ("surface".equals(r)) {
            bg(c, surface());
            if (c instanceof javax.swing.text.JTextComponent) {
                fg(c, text());
            }
        } else if ("app".equals(r)) {
            bg(c, appBg());
        } else if ("term".equals(r)) {
            bg(c, termBg());
            fg(c, termFg());
        } else if ("terminal".equals(r)) {
            // 命令预览：圆角深底。控件本身要非透明，圆角才露得出来。
            bg(c, termBg());
            border(c, flat() ? new CardBorder(termBg(), radius(), null) : null);
            if (c instanceof javax.swing.JScrollPane) {
                javax.swing.JScrollPane sp = (javax.swing.JScrollPane) c;
                opaque(sp, false);
                if (sp.getViewport() != null) {
                    opaque(sp.getViewport(), false);
                }
                FlatScrollBarUI.apply(sp.getVerticalScrollBar());
                FlatScrollBarUI.apply(sp.getHorizontalScrollBar());
            }
        } else if ("field".equals(r)) {
            applyField(c);
        } else if ("plain".equals(r)) {
            // 大段说明文字：跟卡片同底、不加边框，读起来是"正文"而不是"输入框"。
            bg(c, surface());
            fg(c, text2());
            border(c, javax.swing.BorderFactory.createEmptyBorder());
        } else if ("muted".equals(r)) {
            fg(c, text3());
        } else if ("text".equals(r)) {
            fg(c, text());
        } else if ("accent".equals(r)) {
            fg(c, accent());
        } else if ("chip".equals(r)) {
            // 参数名在新风格里是浅底小标签；经典里它就是普通标签。
            // 两句都写死，谁也不用记住"原来长什么样"。
            opaque(c, flat());
            bg(c, accentSoft());
            fg(c, accent());
            font(c, mono(12, true));
            border(c, flat() ? javax.swing.BorderFactory.createCompoundBorder(
                    javax.swing.BorderFactory.createLineBorder(accentLine(), 1, true),
                    javax.swing.BorderFactory.createEmptyBorder(2, 7, 2, 7)) : null);
        }
    }

    // ------------------------------------------------------------------
    // 深度装饰：让"卡片色"一路透到底
    // ------------------------------------------------------------------

    /**
     * 把当前风格刷到整棵树上，包括第一次漏掉的那些零件。
     *
     * <p><b>第一次为什么难看</b>：只刷了十几个大件，结果选项区那层不透明的原生灰面板
     * 把底下的卡片色整个盖住，表单里一半新一半旧。所以这里的规则是
     * <b>底色往下继承</b>：带 {@code surface}／{@code app}／{@code term} 角色的容器
     * 定下底色，它以下的普通面板、视口、滚动区统统跟着变，直到遇到下一个带角色的容器。
     *
     * <p>输入框、下拉框、滚动条这些"自带一套原生外观"的控件在这里一并接管。
     * 已经有角色的控件（日志区、命令预览、说明文字）不套输入框造型，免得把角色的意图盖掉。
     *
     * <p>经典模式直接返回：一个字符都不动。
     */
    public static void decorate(Container root) {
        if (root == null) {
            return;
        }
        // 两个方向都走：取值在经典下解析成表里的值，在新风格下解析成 token —— 同一段
        // 代码，两个数据源。没有"记住/还原"，也就没有"忘了还原"。
        // 起点要看 root 自己的角色。
        //
        // 以前写死 appBg()：SchemaFormPanel 自己是白卡片，但它下面的普通面板被刷成
        // 外层灰 —— 首次构建时 MainPanel 那一遍最后跑会纠正过来，而切中英文只重跑
        // SchemaFormPanel 这一遍，没人纠正，于是白卡片整块变成灰的。
        Color start = appBg();
        if (root instanceof JComponent) {
            String rootRole = roleOf((JComponent) root);
            if ("surface".equals(rootRole)) {
                start = surface();
            } else if ("term".equals(rootRole)) {
                start = termBg();
            }
        }
        // root 自己也要刷。
        //
        // paintDeep 只遍历子件，从不碰传入的 root —— 主面板那边是单独 setBackground 过的，
        // 所以一直没暴露；而选项窗口（applyTo(wide)）没有那一步，于是窗口底色一直是原生灰，
        // 里面搬进去的却是深色控件，混在一起看着就是"某个框忽然变黑了"。
        if (root instanceof JComponent) {
            JComponent rc = (JComponent) root;
            rc.setOpaque(true);
            rc.setBackground(start);
        }
        paintDeep(root, start);
    }

    /** 记录并设置"是否不透明"，经典模式下还原。 */
    public static void opaque(JComponent c, boolean personal) {
        Boolean was = (Boolean) original(c, "opaque", Boolean.valueOf(c.isOpaque()));
        c.setOpaque(flat() ? personal : Boolean.TRUE.equals(was));
    }

    private static void paintDeep(Container parent, Color bg) {
        Component[] kids = parent.getComponents();
        for (int i = 0; i < kids.length; i++) {
            Component k = kids[i];
            Color childBg = bg;
            if (k instanceof JComponent) {
                JComponent j = (JComponent) k;
                String role = roleOf(j);
                if ("app".equals(role)) {
                    childBg = appBg();
                } else if ("surface".equals(role)) {
                    childBg = surface();
                } else if ("term".equals(role)) {
                    childBg = termBg();
                }
                // 滚动视口要显式接管底色。
                //
                // JViewport 的默认底色是原生的白/灰，而圆角控件的四个角是"透明"的
                // —— 露出来的就是它。用户点一下输入框再点别的（触发重绘）时，
                // 之前被方形绘制盖住的圆角处就露出白色，看着像两端多了白块。
                if (j instanceof javax.swing.JViewport) {
                    j.setOpaque(true);
                    j.setBackground(childBg);
                }
                decorateOne(j, role, childBg);
            }
            if (k instanceof Container) {
                paintDeep((Container) k, childBg);
            }
        }
    }

    private static void decorateOne(JComponent j, String role, Color bg) {
        // 容器类永远处理：它们才是"底色透不下去"的元凶。
        if (j instanceof javax.swing.JScrollPane) {
            javax.swing.JScrollPane sp = (javax.swing.JScrollPane) j;
            // terminal 自己画圆角深底，这里不能再把它刷成不透明的方块色，
            // 否则方底会盖住圆角，深色终端就变成一块白板。
            boolean own = "terminal".equals(role);
            if (!own && role == null
                    && !(sp.getBorder() instanceof javax.swing.border.TitledBorder)) {
                sp.setBorder(javax.swing.BorderFactory.createEmptyBorder());
            }
            javax.swing.JViewport vp = sp.getViewport();
            if (!own) {
                opaque(sp, true);
                bg(sp, bg);
                if (vp != null) {
                    opaque(vp, true);
                    bg(vp, bg);
                }
            }
            FlatScrollBarUI.apply(sp.getVerticalScrollBar());
            FlatScrollBarUI.apply(sp.getHorizontalScrollBar());
            java.awt.Component head = sp.getColumnHeader();
            if (head instanceof JComponent) {
                opaque((JComponent) head, false);
            }
            return;
        }
        if (j instanceof javax.swing.JViewport) {
            opaque(j, true);
            bg(j, bg);
            return;
        }
        if (j instanceof javax.swing.JScrollBar) {
            FlatScrollBarUI.apply((javax.swing.JScrollBar) j);
            return;
        }
        if (j instanceof javax.swing.JSplitPane) {
            FlatSplitPaneUI.apply((javax.swing.JSplitPane) j);
            return;
        }
        if (j instanceof javax.swing.JPanel) {
            // 没有角色的普通面板继承上一层底色 —— "卡片色透到底"靠的就是这一步。
            opaque(j, true);
            bg(j, bg);
            return;
        }
        if (role != null) {
            return;                 // 外观已经由角色定好了，别再套一层
        }
        if (j instanceof javax.swing.JComboBox) {
            FlatComboBoxUI.apply((javax.swing.JComboBox<?>) j);
            return;
        }
        if (j instanceof javax.swing.text.JTextComponent) {
            if (isComboEditor(j)) {
                // 可编辑下拉框内部的编辑框：外观由外层下拉框那一圈边框负责。
                //
                // 它本身也是 JTextComponent，如果不拦下来，深度装饰会把它当普通输入框
                // 再套一圈 CardBorder —— 于是"外圈套内圈"。只有"有好几个选项"的参数
                // 用的是可编辑下拉框，所以只有它们会出现这个现象。
                j.setOpaque(false);
                border(j, javax.swing.BorderFactory.createEmptyBorder(0, 2, 0, 2));
                fg(j, text());
                font(j, ui(12, false));
                if (j instanceof javax.swing.text.JTextComponent) {
                    ((javax.swing.text.JTextComponent) j).setCaretColor(text());
                }
                return;
            }
            applyField(j);
            return;
        }
        if (j instanceof javax.swing.JCheckBox
                || j instanceof javax.swing.JRadioButton) {
            opaque(j, false);
            fg(j, text());
            font(j, ui(12, false));
            // 勾选框要跟着主题画。
            //
            // 不能换成 FlatButtonUI：方框和勾是勾选框自己的 UI 画的，换了就没人画勾
            // （"能勾选但看不到 ✓"）。但也不能放着原生不管 —— Windows 那个蓝色渐变
            // 方块放在浅色/深色里很突兀。所以给它一个自己画的主题版本；
            // 切回经典时 updateUI() 还原。
            if (j instanceof javax.swing.JCheckBox) {
                javax.swing.JCheckBox cb = (javax.swing.JCheckBox) j;
                if (flat()) {
                    if (!(cb.getUI() instanceof FlatCheckBoxUI)) {
                        cb.setUI(new FlatCheckBoxUI());
                    }
                } else if (cb.getUI() instanceof FlatCheckBoxUI) {
                    cb.updateUI();
                }
            }
            return;
        }
        if (j instanceof javax.swing.JSpinner) {
            javax.swing.JSpinner sp = (javax.swing.JSpinner) j;
            // 微调箭头换成自己画的主题版本。
            //
            // 原生 BasicArrowButton 的三角形是"组件自己 paint"的，不走 UI ——
            // 换 UI、调尺寸都改不掉那套原生 3D 配色（"又变成经典模式那种"）。
            // 换成一个自己画三角形的按钮即可；微调动作仍由 BasicSpinnerUI 接上。
            if (flat()) {
                if (!(sp.getUI() instanceof FlatSpinnerUI)) {
                    sp.setUI(new FlatSpinnerUI());
                }
            } else if (sp.getUI() instanceof FlatSpinnerUI) {
                // 先清空子件再重建。
                //
                // 新风格的箭头是我换过的（FlatSpinnerUI.Arrow）；updateUI() 不保证
                // 会把已有子件换掉，于是切回经典后可能留着我的箭头和它的度量 ——
                // 那正是"经典模式里箭头变了样、又小又不好点"。清空之后由原生 UI
                // 重新装编辑框和原生箭头，度量自然回到原生。
                sp.removeAll();
                sp.updateUI();
            }
            paintSpinner(sp);
        }
    }

    /** 输入框边框：新风格是圆角细边，经典取表里的原生边框。 */
    private static Border fieldBorder(JComponent c) {
        if (flat()) {
            return javax.swing.BorderFactory.createCompoundBorder(
                    javax.swing.BorderFactory.createLineBorder(line(), 1, true),
                    javax.swing.BorderFactory.createEmptyBorder(4, 8, 4, 8));
        }
        String key = c instanceof javax.swing.JTextArea ? "TextArea.border" : "TextField.border";
        Border b = UIManager.getBorder(key);
        return b != null ? b : javax.swing.BorderFactory.createEmptyBorder();
    }

    /** 输入框造型：浅底、圆角细边、内缩一点，不再用原生的凹槽边。 */
    private static void applyField(JComponent c) {
        // 底色必须由控件自己画（不透明），不能交给边框。
        //
        // 绘制顺序是 paintComponent() 画文字和光标、paintBorder() 才画边框。
        // 让 CardBorder 既填色又画线，填充就会盖在文字之后 ——
        // 表现是"敲进去的字不显示、光标也看不见，但值确实进了命令"
        // （数据和绘制是两回事）。下拉框已经踩过一次，这里同样。
        //
        // 非透明：方形底色由 BasicTextUI 画，会盖住圆角；所以关掉它，
        // 圆角填充完全交给自定义 UI 的 update()（见 FlatTextUIs）。
        opaque(c, false);
        bg(c, fieldBg());
        fg(c, text());
        font(c, c instanceof javax.swing.JTextArea ? mono(12, false) : ui(12, false));
        // 用 RoundBorder，不用 createLineBorder(...,true)：
        // 后者的圆弧固定 2 像素、无视 radius()，画出来永远是直角。
        // 微调框的编辑框：右边距留出箭头那一栏，文字才不会钻到箭头下面
        // （箭头是叠在编辑框右端内侧的，见 FlatSpinnerUI.createLayout）。
        int rightPad = isSpinnerEditor(c) ? FlatSpinnerUI.ARROW_W : 8;
        border(c, new RoundBorder(line(), radius(),
                new java.awt.Insets(4, 8, 4, rightPad)));
        // 光标用正文色，深色下才看得见。
        if (c instanceof javax.swing.text.JTextComponent) {
            ((javax.swing.text.JTextComponent) c).setCaretColor(text());
        }
        // 圆角底色交给自定义 UI 画：它在 update() 里，位于文字之前。
        // 边框只画线（上面那个 compound），所以文字和光标不会被盖住。
        if (c instanceof javax.swing.JTextArea) {
            javax.swing.JTextArea ta = (javax.swing.JTextArea) c;
            if (flat()) {
                if (!(ta.getUI() instanceof FlatTextUIs.Area)) {
                    ta.setUI(new FlatTextUIs.Area());
                }
            } else if (ta.getUI() instanceof FlatTextUIs.Area) {
                ta.updateUI();
            }
        } else if (c instanceof javax.swing.JTextField) {
            javax.swing.JTextField tf = (javax.swing.JTextField) c;
            if (flat()) {
                if (!(tf.getUI() instanceof FlatTextUIs.Field)) {
                    tf.setUI(new FlatTextUIs.Field());
                }
            } else if (tf.getUI() instanceof FlatTextUIs.Field) {
                tf.updateUI();
            }
        }
        if (c instanceof javax.swing.text.JTextComponent) {
            javax.swing.text.JTextComponent t = (javax.swing.text.JTextComponent) c;
            t.setCaretColor(text());
            t.setSelectionColor(accentSoft());
            t.setSelectedTextColor(accent());
        }
    }

    private static void paintSpinner(javax.swing.JSpinner sp) {
        opaque(sp, false);
        sp.setBorder(javax.swing.BorderFactory.createEmptyBorder());
        javax.swing.JComponent ed = sp.getEditor();
        if (ed instanceof javax.swing.JSpinner.DefaultEditor) {
            javax.swing.JTextField tf =
                    ((javax.swing.JSpinner.DefaultEditor) ed).getTextField();
            // 经助手设置：底色和不透明度都记下来，切回经典时能还回去。
            //
            // 裸 setBackground 在"切回经典"这个方向没有还原路径，编辑器会一直留着
            // 上一套风格的深色底 —— 用户看到的"经典面板里数字参数又变黑框了"就是它。
            // 非透明：方形底由 BasicTextUI 画，会盖住圆角填充。
            //
            // 这里原来是 opaque(tf, flat()) —— 新风格下等于 true，于是编辑器又被
            // 设回不透明，BasicTextUI 自己画的那层方形底就把 FlatTextUIs 画的圆角
            // 盖掉了（表现："输入框还是直角长方形"）。applyField 已经设成非透明，
            // 这一步必须保持一致。
            // 全部经助手设置：经典模式下会自动还原原生取值。
            //
            // 之前前景色和边框是裸设置的（setForeground / setBorder），没有还原路径，
            // 于是经典模式里数值用的是主题的正文色 —— 那套外观里它偏浅，数字就看不清。
            opaque(tf, false);
            bg(tf, fieldBg());
            fg(tf, text());
            border(tf, javax.swing.BorderFactory.createEmptyBorder(3, 7, 3, 7));
            // 经助手设置：字体也会被记下来，切回经典时还原。裸 setFont 会留下
            // 主题的字体，行高跟着变，整个表单就往下一两个像素。
            font(tf, ui(12, false));
        }
        // 微调按钮保持原生外观：它是靠 BasicArrowButton 画箭头的，
        // 换成扁平 UI 之后箭头就没人画了，只剩很小一块。
        // 这里只把尺寸给足，让上下箭头看得清、点得中。
        Component[] kids = sp.getComponents();
        for (int i = 0; i < kids.length; i++) {
            if (kids[i] instanceof AbstractButton) {
                AbstractButton b = (AbstractButton) kids[i];
                if (flat()) {
                    b.setPreferredSize(new java.awt.Dimension(20, 15));
                    b.setMinimumSize(new java.awt.Dimension(20, 15));
                } else {
                    // 经典模式交还原生度量。
                    //
                    // 以前是无条件设 20×15，经典下也照设；而且从新风格切回来时
                    // 这个尺寸没有还原路径，会一直留着 —— 箭头尺寸就不对了。
                    b.setPreferredSize(null);
                    b.setMinimumSize(null);
                }
            }
        }
    }

    /** 是不是微调框内部的编辑框。 */
    private static boolean isSpinnerEditor(java.awt.Component c) {
        java.awt.Container p = c.getParent();
        return p instanceof javax.swing.JSpinner
                || (p != null && p.getParent() instanceof javax.swing.JSpinner);
    }

    /** 是不是"可编辑下拉框"内部那个编辑框。 */
    private static boolean isComboEditor(java.awt.Component c) {
        java.awt.Container p = c.getParent();
        if (p instanceof javax.swing.JComboBox) {
            return true;
        }
        // 有些 L&F 会在中间垫一层面板
        return p != null && p.getParent() instanceof javax.swing.JComboBox;
    }

    private static String roleOf(JComponent c) {
        Object r = c.getClientProperty(KEY + "role");
        return r == null ? null : String.valueOf(r);
    }

    // ------------------------------------------------------------------
    // 落盘
    // ------------------------------------------------------------------

    /** 默认位置：{@code %USERPROFILE%\.TBtools\BioEnvCaller\theme.txt}。 */
    public static File defaultFile() {
        String home = System.getProperty("user.home");
        File dir = new File(home, ".TBtools" + File.separator + "BioEnvCaller");
        return new File(dir, "theme.txt");
    }

    /** 启动时读一次；文件缺失或读不出来就是经典。 */
    public static synchronized void load() {
        if (System.getProperty("bioenv.theme.readonly") != null) {
            return;          // 只读模式：不读也不写，永远从默认（经典）开始
        }
        String id = CLASSIC;
        BufferedReader br = null;
        try {
            File f = defaultFile();
            if (f.isFile()) {
                br = new BufferedReader(new InputStreamReader(
                        new FileInputStream(f), Charset.forName("UTF-8")));
                String line = br.readLine();
                if (line != null) {
                    line = line.trim();
                    if (PERSONAL.equals(line) || DARK.equals(line)) {
                        id = line;
                    }
                }
            }
        } catch (Exception ignored) {
            id = CLASSIC;                 // 读坏了就当没设置过
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Exception ignored) {
                    // 关不掉也无所谓
                }
            }
        }
        current = id;
        tokens = tokenFor(id);
    }

    private static void save(String id) {
        if (System.getProperty("bioenv.theme.readonly") != null) {
            return;              // 出图/自检用：只在本会话生效，不写用户的配置
        }
        Writer w = null;
        try {
            File f = defaultFile();
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory()) {
                dir.mkdirs();
            }
            w = new OutputStreamWriter(new FileOutputStream(f),
                    Charset.forName("UTF-8"));
            w.write(id);
        } catch (Exception ignored) {
            // 存不下就只在本次会话生效，不打扰用户
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Exception ignored) {
                    // 同上
                }
            }
        }
    }
}

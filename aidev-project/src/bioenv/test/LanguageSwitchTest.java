package bioenv.test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import javax.swing.border.TitledBorder;

import bioenv.i18n.I18n;
import bioenv.i18n.Lang;
import bioenv.i18n.Strings;
import bioenv.ui.MainPanel;

/**
 * 切中英文之后，界面上的文字是不是都跟着换了。
 *
 * <h2>回归的缺陷</h2>
 *
 * <p>工具栏上的字换了，但有几处停在旧语言上：面板里分区标题（左侧「命令（按环境分组）」、
 * 右侧「输出」）和底部状态栏。用户点一下切换，界面就变成两种语言混着。
 *
 * <p>根因有两类，性质不同：
 *
 * <ul>
 *   <li><b>「输出」标题刷的是一个已经废弃的字段。</b>那个标题后来从
 *       {@code TitledBorder} 搬成了一个普通 {@code JLabel}（{@code logTitle}），
 *       理由是切主题时 TitledBorder 会把「输出 / 清空」那一行覆盖回老样子。但语言
 *       刷新的代码还在写 {@code logBorder}，而它<b>从来没有被赋过值</b>，永远是 null，
 *       于是那句刷新从来没生效过。</li>
 *   <li><b>状态栏压根没刷。</b>{@code setStatus} 是"调用时按当前语言生成一句话存起来"，
 *       切语言时没人重算，之前那句就一直是旧语言写的。</li>
 * </ul>
 *
 * <p>这类缺陷靠人眼很容易漏：只有当界面恰好停在某个语言、又恰好切一次才会露出来。
 * 所以做成断言。
 *
 * <p>不需要显示器。
 */
public final class LanguageSwitchTest {

    private static int checks;
    private static int failures;

    private LanguageSwitchTest() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== LanguageSwitchTest ===");

        String oldHome = System.getProperty("user.home");
        File home = freshHome("bioenv-lang-");
        System.setProperty("user.home", home.getAbsolutePath());
        try {
            // 语言文件指向临时目录，起点固定是中文。不清这一步的话，开发机上的语言
            // 设置会决定这个测试测的是哪一边（这正是 Subcommand*Test 踩过的坑）。
            Method setFile = I18n.class.getDeclaredMethod("setFile", File.class);
            setFile.setAccessible(true);
            setFile.invoke(null, new File(home, "language.txt"));
            setLanguage("zh");
            I18n.resetForTest();
            I18n.loadOnce();

            MainPanel panel = openPanel();

            String zhTree = I18n.t(Strings.TITLE_TREE);

            check("起点：分区标题是「" + zhTree + "」", zhTree.equals(treeTitle(panel)),
                    "实际「" + treeTitle(panel) + "」");
            String zhOutput = I18n.t(Strings.TITLE_OUTPUT);
            String zhHint = I18n.t(Strings.HINT_COMMAND_AREA_LONG);
            check("起点：「输出」标题是「" + zhOutput + "」",
                    zhOutput.equals(logTitleText(panel)),
                    "实际「" + logTitleText(panel) + "」");

            // 走<b>按键设置</b>那条生产路径，而不是塞一句渲染好的中文。
            //
            // 第一版就是塞渲染好的字符串，结果测的是"能不能把一句已经是中文的话翻成
            // 英文"——那当然做不到，断言假红。真正要验的是：状态是<b>按 key 存的</b>，
            // 切语言时能按新语言重新渲染。所以这里用 setStatusKey。
            Method setStatusKey = MainPanel.class.getDeclaredMethod(
                    "setStatusKey", String.class, Object[].class);
            setStatusKey.setAccessible(true);
            setStatusKey.invoke(panel, Strings.STATUS_READY, new Object[0]);
            settle();

            // ---- 切成英文 ----
            switchLanguage("en");
            settle();

            String enTree = I18n.t(Strings.TITLE_TREE);
            check("切英文后取的文案确实变了（" + zhTree + " -> " + enTree + "）",
                    !zhTree.equals(enTree));

            check("切英文后左侧分区标题跟着换（期望「" + enTree + "」）",
                    enTree.equals(treeTitle(panel)),
                    "实际「" + treeTitle(panel) + "」");
            // 「输出」的<b>标题</b>跟着语言走，而它的<b>正文</b>不跟：正文是日志，记的是
            // 当时发生了什么、用当时的语言写的，已经打出来的行不该被回炉重译。
            check("切英文后「输出」标题跟着换（期望「"
                            + I18n.t(Strings.TITLE_OUTPUT) + "」）",
                    I18n.t(Strings.TITLE_OUTPUT).equals(logTitleText(panel)),
                    "实际「" + logTitleText(panel) + "」");
            check("切英文后命令预览那句说明也跟着换",
                    I18n.t(Strings.HINT_COMMAND_AREA_LONG).equals(commandHintText(panel)),
                    "实际「" + commandHintText(panel) + "」");
            check("切英文后状态栏不再停在旧语言（期望「"
                            + I18n.t(Strings.STATUS_READY) + "」）",
                    I18n.t(Strings.STATUS_READY).equals(statusText(panel)),
                    "实际「" + statusText(panel) + "」");
            check("切英文后「扫描 conda 环境」按钮也换了",
                    I18n.t(Strings.MENU_DETECT).equals(buttonText(panel, "detectButton")),
                    "实际「" + buttonText(panel, "detectButton") + "」");

            // ---- 切回中文 ----
            switchLanguage("zh");
            settle();
            check("切回中文后左侧分区标题还原", zhTree.equals(treeTitle(panel)),
                    "实际「" + treeTitle(panel) + "」");
            check("切回中文后「输出」标题还原", zhOutput.equals(logTitleText(panel)),
                    "实际「" + logTitleText(panel) + "」");
            check("切回中文后命令预览那句说明还原",
                    zhHint.equals(commandHintText(panel)),
                    "实际「" + commandHintText(panel) + "」");
            check("切回中文后状态栏还原",
                    I18n.t(Strings.STATUS_READY).equals(statusText(panel)),
                    "实际「" + statusText(panel) + "」");

            // ---- 风格切换器 ----
            // 三个风格名是自绘的（ThemeSwitcher 自己 drawString），没人替它重设文字，
            // 所以必须自己监听语言变化并重画。它原来写死中文，英文界面上那三格仍然是
            // 「经典 / 浅色 / 深色」。
            switchLanguage("en");
            settle();
            check("切英文后风格名跟着换（期望 " + I18n.t(Strings.THEME_CLASSIC) + " / "
                            + I18n.t(Strings.THEME_LIGHT) + " / "
                            + I18n.t(Strings.THEME_DARK) + "）",
                    "Classic".equals(I18n.t(Strings.THEME_CLASSIC))
                            && "Light".equals(I18n.t(Strings.THEME_LIGHT))
                            && "Dark".equals(I18n.t(Strings.THEME_DARK)));
            check("切英文后风格切换器的提示也跟着换",
                    I18n.t(Strings.THEME_TOOLTIP).equals(themeTooltip(panel)),
                    "实际「" + themeTooltip(panel) + "」");

            switchLanguage("zh");
            settle();
            check("切回中文后风格切换器的提示还原",
                    I18n.t(Strings.THEME_TOOLTIP).equals(themeTooltip(panel)),
                    "实际「" + themeTooltip(panel) + "」");

        } finally {
            System.setProperty("user.home", oldHome);
            deleteRecursively(home);
        }

        System.out.println(failures == 0
                ? "PASS " + checks + " checks"
                : "FAIL " + failures + " of " + checks);
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------

    /**
     * 像用户点语言按钮那样切一次。
     *
     * <p>走的是生产的 I18n.set：它会写盘并通知所有监听者。测试不该自己去调组件，
     * 否则测的就不是"用户做了这件事之后会怎样"。
     */
    private static void switchLanguage(String code) {
        I18n.set(code.equals("en") ? Lang.EN : Lang.ZH);
    }

    private static void setLanguage(String code) {
        I18n.set(code.equals("en") ? Lang.EN : Lang.ZH);
    }

    /**
     * 等界面把这次切换做完。
     *
     * <p>状态栏是通过 {@code FileDialogs.onEdt(...)} 排到事件线程上的，不是当场就改；
     * 断言紧跟在 set 之后会读到还没刷新的旧值，测试就会假红。
     */
    private static void settle() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
            }
        });
        Thread.sleep(300);
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
            }
        });
    }

    private static MainPanel openPanel() throws Exception {
        final MainPanel[] p = new MainPanel[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                p[0] = new MainPanel();
            }
        });
        Thread.sleep(2500);
        return p[0];
    }

    private static Object field(Object target, String name) throws Exception {
        Field f = MainPanel.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    /** 左侧分区标题：滚动区上的 TitledBorder。 */
    private static String treeTitle(MainPanel p) throws Exception {
        JScrollPane sc = (JScrollPane) field(p, "treeScrollRef");
        Border b = sc == null ? null : sc.getBorder();
        if (b instanceof TitledBorder) {
            return ((TitledBorder) b).getTitle();
        }
        // 没有 TitledBorder 时退到树根节点的文字：标题也可能画在那里。
        return String.valueOf(((javax.swing.JTree) field(p, "toolTree"))
                .getModel().getRoot());
    }

    private static String commandHintText(MainPanel p) throws Exception {
        return ((JLabel) field(p, "commandHint")).getText();
    }

    private static String themeTooltip(MainPanel p) throws Exception {
        javax.swing.JComponent sw =
                (javax.swing.JComponent) field(p, "themeSwitcher");
        return sw.getToolTipText();
    }

    private static String logTitleText(MainPanel p) throws Exception {
        return ((JLabel) field(p, "logTitle")).getText();
    }

    private static String statusText(MainPanel p) throws Exception {
        Object label = field(p, "statusLabel");
        return label instanceof JLabel ? ((JLabel) label).getText() : null;
    }

    private static String buttonText(MainPanel p, String fieldName) throws Exception {
        return ((javax.swing.JButton) field(p, fieldName)).getText();
    }

    private static File freshHome(String prefix) {
        File home = new File(System.getProperty("java.io.tmpdir"),
                prefix + System.nanoTime());
        if (!home.mkdirs()) {
            throw new IllegalStateException("无法创建临时 home " + home);
        }
        return home;
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteRecursively(k);
            }
        }
        f.delete();
    }

    private static void check(String what, boolean ok) {
        check(what, ok, null);
    }

    private static void check(String what, boolean ok, String detail) {
        checks++;
        if (ok) {
            System.out.println("PASS  " + what);
        } else {
            failures++;
            System.out.println("FAIL  " + what + (detail == null ? "" : " — " + detail));
        }
    }
}

package bioenv.test;

import java.awt.Color;

import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;

import bioenv.ui.UiTheme;

/**
 * 换风格时必须把上一套风格的底色收回去：深色 → 经典不得留下深色滚动条。
 *
 * <h2>回归的缺陷</h2>
 *
 * <p>从「深色」切到「经典」后，命令树两侧的滚动条变成两根<b>黑条</b>。问题是深色期间
 * 给滚动条设过底色，而切回经典时只调了 {@code updateUI()} 换外观，<b>没有把底色改回来</b>：
 *
 * <pre>
 * FlatScrollBarUI.apply(bar):
 *     if (!UiTheme.flat()) {
 *         if (bar.getUI() instanceof FlatScrollBarUI) { bar.updateUI(); }
 *         return;            // 底色仍是深色时设下的那个
 *     }
 *     bar.setUI(new FlatScrollBarUI());
 *     bar.setBackground(UiTheme.sunken());   // ← 深色值，切回去时没人撤销
 * </pre>
 *
 * <p>{@code updateUI()} 只换 UI 委托，不碰 {@code setBackground} 设过的颜色。而细滚动条
 * 的滑道是<b>什么都不画</b>的（见 {@code paintTrack}），露出来的正是这个底色——于是在
 * 经典主题里显示成一根黑条。
 *
 * <h2>为什么这不是"看起来差不多"的问题</h2>
 *
 * <p>底色是控件自己的状态，不该由"当前是不是深色"之外的东西决定。切换风格是一次完整
 * 的重涂：进去时设下的，出来时必须还回去。只改一半就会留下这种残留。
 *
 * <p>测试不需要显示器：只构造控件、驱动主题、读颜色。主题会落盘，所以 {code user.home}
 * 指向临时目录，免得改掉用户真实选择的风格。
 */
public final class ThemeSwitchResidueTest {

    private static int checks;
    private static int failures;
    /**
     * 深色主题下的 sunken 值，启动时取一次。
     *
     * <p>通过切到深色再读 {@code UiTheme.sunken()} 取得，而不是抄一份常量：抄下来的
     * 数字会随着主题微调而失真，那时这个测试就会开始误报。取完立刻切回经典，免得
     * 影响后面的场景。
     */
    private static Color DARK_SUNKEN;
    /** 从没进过深色的滚动条底色，作为"没有残留"的判断基准。 */
    private static Color CLASSIC_BASELINE;

    private ThemeSwitchResidueTest() {
    }

    public static void main(String[] args) throws Exception {
        java.io.File home = new java.io.File(System.getProperty("java.io.tmpdir"),
                "tbvm-theme-" + System.nanoTime());
        home.mkdirs();
        System.setProperty("user.home", home.getAbsolutePath());
        System.out.println("=== ThemeSwitchResidueTest ===");

        runOnEdt(new Runnable() {
            @Override
            public void run() {
                UiTheme.set(UiTheme.DARK);
                DARK_SUNKEN = UiTheme.sunken();
                UiTheme.set(UiTheme.CLASSIC);
            }
        });
        System.out.println("深色 sunken() = " + DARK_SUNKEN);
        System.out.println("经典 sunken() = " + UiTheme.sunken());
        System.out.println();

        classicBaselineIsRecorded();
        System.out.println();
        darkToClassicLeavesNoDarkScrollBar();
        darkToPersonalLeavesNoDarkScrollBar();

        deleteRecursively(home);

        System.out.println(failures == 0
                ? "PASS " + checks + " checks"
                : "FAIL: " + failures + " of " + checks + " checks failed");
        if (failures > 0) {
            System.exit(1);
        }
    }

    /** 主要场景：深色 → 经典，滚动条底色必须回到"从没进过深色"的样子。 */
    private static void darkToClassicLeavesNoDarkScrollBar() throws Exception {
        final JScrollPane[] sp = new JScrollPane[1];

        runOnEdt(new Runnable() {
            @Override
            public void run() {
                sp[0] = scrollingTree();
                UiTheme.set(UiTheme.DARK);
                UiTheme.applyTo(sp[0]);
            }
        });
        Color inDark = colorOf(sp[0].getVerticalScrollBar());
        System.out.println("深色下滚动条底色 = " + inDark);
        check("深色期间确实被设成了深色底", inDark.equals(DARK_SUNKEN));

        // 切到经典：这是出问题的那一步。
        runOnEdt(new Runnable() {
            @Override
            public void run() {
                UiTheme.set(UiTheme.CLASSIC);
                UiTheme.applyTo(sp[0]);
            }
        });
        Color afterClassic = colorOf(sp[0].getVerticalScrollBar());
        System.out.println("切回经典后底色 = " + afterClassic);
        System.out.println("基准（没进过深色）= " + CLASSIC_BASELINE);

        check("切回经典后不再是深色底", !afterClassic.equals(DARK_SUNKEN));
        check("切回经典后与基准一致（无残留）",
                afterClassic.equals(CLASSIC_BASELINE));
        System.out.println();
    }

    /** 同一缺陷的另一条出口：深色 → 浅色（personal），同样不得留深色底。 */
    private static void darkToPersonalLeavesNoDarkScrollBar() throws Exception {
        final JScrollPane[] sp = new JScrollPane[1];
        runOnEdt(new Runnable() {
            @Override
            public void run() {
                sp[0] = scrollingTree();
                UiTheme.set(UiTheme.DARK);
                UiTheme.applyTo(sp[0]);
            }
        });
        Color inDark = colorOf(sp[0].getHorizontalScrollBar());

        runOnEdt(new Runnable() {
            @Override
            public void run() {
                UiTheme.set(UiTheme.PERSONAL);
                UiTheme.applyTo(sp[0]);
            }
        });
        Color after = colorOf(sp[0].getHorizontalScrollBar());
        System.out.println("深色→浅色 横向滚动条 = " + inDark + " -> " + after);

        check("横向滚动条也回到了当前主题的底色",
                after.equals(UiTheme.sunken()));
        System.out.println();
    }

    /**
     * 反向对照：一直在经典的滚动条，底色是什么样。
     *
     * <p>这条给出的是<b>基准值</b>：判断"有没有残留"不该拿某个写死的颜色去比，
     * 而该拿"从没进过深色的同一控件"去比。经典下底色是 Look-and-Feel 给的，
     * 不是 {@code sunken()}(那是深色主题才设的值)。
     */
    private static void classicBaselineIsRecorded() throws Exception {
        final JScrollPane[] sp = new JScrollPane[1];
        runOnEdt(new Runnable() {
            @Override
            public void run() {
                sp[0] = scrollingTree();
                UiTheme.set(UiTheme.CLASSIC);
                UiTheme.applyTo(sp[0]);
            }
        });
        CLASSIC_BASELINE = colorOf(sp[0].getVerticalScrollBar());
        System.out.println("基准（从没进过深色）滚动条底色 = " + CLASSIC_BASELINE);
        check("基准值不是深色那个", !CLASSIC_BASELINE.equals(DARK_SUNKEN));
    }

    /** 一个带滚动条的真实控件：命令树。 */
    private static JScrollPane scrollingTree() {
        JTree tree = new JTree();
        JScrollPane sp = new JScrollPane(tree);
        sp.setSize(300, 400);
        return sp;
    }

    private static Color colorOf(final JScrollBar bar) throws Exception {
        final Color[] out = new Color[1];
        runOnEdt(new Runnable() {
            @Override
            public void run() {
                out[0] = bar.getBackground();
            }
        });
        return out[0];
    }

    private static void runOnEdt(Runnable r) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeAndWait(r);
        }
    }

    private static void check(String label, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println("  " + (ok ? "PASS  " : "FAIL  ") + label);
    }

    private static void deleteRecursively(java.io.File f) {
        if (f == null || !f.exists()) {
            return;
        }
        java.io.File[] kids = f.listFiles();
        if (kids != null) {
            for (java.io.File k : kids) {
                deleteRecursively(k);
            }
        }
        f.delete();
    }
}

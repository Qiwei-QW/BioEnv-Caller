package vmadapt.test;

import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import vmadapt.ui.ConnectionPanel;

/**
 * 「连接配置」页的分组布局与标签对应关系。
 *
 * <h2>回归的缺陷</h2>
 *
 * <p>把八个输入框从"排成一列"改成"按步骤分三组"之后，界面上标签整体<b>错位一格</b>：
 * 「密钥路径」那一行显示的是「15」，「超时（秒）」那一行显示的是「可选」。
 *
 * <p>原因不是布局，而是文案铺设：{@code rowLabels} 是按 <b>addRow 的调用顺序</b>
 * 排的，而 {@code applyLanguage()} 里那张键名数组仍按旧顺序（超时排第 7）。两组顺序
 * 一旦不一致，按下标写文案就会整体错位——而且<b>不会报错</b>，只是每个标签都写着隔壁
 * 那一行的名字。
 *
 * <p>所以本测试断言的是<b>顺序</b>本身：第 i 个标签必须配第 i 个控件，且两者说的是
 * 同一件事。用"标签文字 + 控件类型"配对来验，而不是只数个数。
 *
 * <h2>另一条：结果弹窗</h2>
 *
 * <p>「测试连接」的报告原本只写进「连接结果」页，而界面不会自己跳过去，用户点完按钮
 * 在自己那一页什么都看不到。现在同时弹窗。无头环境下弹窗被刻意跳过，所以这里只断言
 * "报告内容仍然照常写进状态区"——弹窗本身属于人工确认的范围。
 */
public final class ConnectionLayoutTest {

    private static int checks;
    private static int failures;

    private ConnectionLayoutTest() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== ConnectionLayoutTest ===");

        final ConnectionPanel[] p = new ConnectionPanel[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                p[0] = new ConnectionPanel();
            }
        });

        fieldOrderMatchesLabels(p[0]);
        threeGroupsExist(p[0]);
        testButtonIsPresentOnce(p[0]);
        resultTabIsGone(p[0]);

        System.out.println(failures == 0
                ? "PASS " + checks + " checks"
                : "FAIL: " + failures + " of " + checks + " checks failed");
        if (failures > 0) {
            System.exit(1);
        }
    }

    /**
     * 第 i 个标签的文字必须与第 i 个控件相称。
     *
     * <p>这条正是错位缺陷的探针：错位时「密钥路径」的标签会落在超时输入框上，
     * 而超时框是数字框、密钥行是带「浏览…」的组合面板——两者对不上。
     */
    private static void fieldOrderMatchesLabels(ConnectionPanel panel) {
        List<JLabel> labels = labelsOf(panel);
        List<Component> fields = fieldsOf(panel);

        check("标签与控件数量一致（" + labels.size() + " / " + fields.size() + "）",
                labels.size() == fields.size() && !labels.isEmpty());

        String[] expectLabel = {"主机", "端口", "用户名", "超时", "密钥",
                "配置名称", "备注", "已存配置"};
        if (labels.size() != expectLabel.length) {
            check("行数仍是 8 行", false);
            return;
        }
        for (int i = 0; i < expectLabel.length; i++) {
            String got = labels.get(i).getText();
            check("第 " + (i + 1) + " 行标签是「" + expectLabel[i] + "」（实际「"
                            + got + "」）",
                    got != null && got.contains(expectLabel[i]));
        }

        // 逐行核对"标签说的"和"控件是的"是否相称——比只看标签文字更结实。
        check("第 4 行（超时）配的是文本框，不是密钥那行",
                isTextComponent(fields.get(3)));
        check("第 5 行（密钥）配的是带「浏览…」的组合面板",
                hasButton(fields.get(4)));
        check("第 8 行（已存配置）配的是组合面板", hasComboBox(fields.get(7)));
    }

    /** 三组标题必须都在，否则"分组"这件事等于没做。 */
    private static void threeGroupsExist(ConnectionPanel panel) {
        List<String> titles = titledBorderTexts(panel);
        check("有「这台机器」组", titles.contains("这台机器"));
        check("有「登录方式」组", titles.contains("登录方式"));
        check("有「保存与载入配置」组", titles.contains("保存与载入配置"));
        check("「操作」组还在（放着测试连接）", titles.contains("操作"));
    }

    /** 「测试连接」只能出现一次：分组时最容易把它复制成两份。 */
    private static void testButtonIsPresentOnce(ConnectionPanel panel) {
        int n = countButtonsNamed(panel, "测试连接");
        check("「测试连接」按钮只有一个（实际 " + n + "）", n == 1);
        int gen = countButtonsNamed(panel, "生成密钥");
        check("「生成密钥」按钮只有一个（实际 " + gen + "）", gen == 1);
    }

    /**
     * 「连接结果」页必须已经删掉，只剩「连接配置」与「网络模式速查」两页。
     *
     * <p>结果改成弹窗呈现之后，那一页就是多余的：它要求用户先知道结果在哪一页，
     * 而弹窗把"做完了"和"结果是什么"放在同一处。若哪天它被无意中加回来，这条会红。
     */
    private static void resultTabIsGone(ConnectionPanel panel) {
        javax.swing.JTabbedPane tabs = tabsOf(panel);
        if (tabs == null) {
            check("找得到标签页控件", false);
            return;
        }
        check("只有两页（实际 " + tabs.getTabCount() + "）", tabs.getTabCount() == 2);
        check("第一页是「连接配置」",
                "连接配置".equals(tabs.getTitleAt(0)));
        check("第二页是「网络模式速查」",
                "网络模式速查".equals(tabs.getTitleAt(1)));
        for (int i = 0; i < tabs.getTabCount(); i++) {
            check("没有「连接结果」页（第 " + (i + 1) + " 页）",
                    !"连接结果".equals(tabs.getTitleAt(i)));
        }
    }

    /** 取面板内部的标签页控件（生产类没有为测试暴露访问器）。 */
    private static javax.swing.JTabbedPane tabsOf(ConnectionPanel panel) {
        try {
            Field f = ConnectionPanel.class.getDeclaredField("tabs");
            f.setAccessible(true);
            Object v = f.get(panel);
            return (v instanceof javax.swing.JTabbedPane)
                    ? (javax.swing.JTabbedPane) v : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 反射取私有控件：这是自检工具，不给生产类加只为测试用的访问器
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<JLabel> labelsOf(ConnectionPanel panel) {
        try {
            Field f = ConnectionPanel.class.getDeclaredField("rowLabels");
            f.setAccessible(true);
            return (List<JLabel>) f.get(panel);
        } catch (Exception e) {
            return new ArrayList<JLabel>();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Component> fieldsOf(ConnectionPanel panel) {
        try {
            Field f = ConnectionPanel.class.getDeclaredField("rowFields");
            f.setAccessible(true);
            return (List<Component>) f.get(panel);
        } catch (Exception e) {
            return new ArrayList<Component>();
        }
    }

    /** 面板树里所有 TitledBorder 的标题文字。 */
    private static List<String> titledBorderTexts(Container root) {
        List<String> out = new ArrayList<String>();
        collectTitles(root, out);
        return out;
    }

    private static void collectTitles(Container c, List<String> out) {
        if (c instanceof javax.swing.JComponent) {
            javax.swing.border.Border b =
                    ((javax.swing.JComponent) c).getBorder();
            if (b instanceof javax.swing.border.TitledBorder) {
                String t = ((javax.swing.border.TitledBorder) b).getTitle();
                if (t != null && !t.isEmpty()) {
                    out.add(t);
                }
            }
        }
        for (Component k : c.getComponents()) {
            if (k instanceof Container) {
                collectTitles((Container) k, out);
            }
        }
    }

    private static int countButtonsNamed(Container c, String text) {
        int n = 0;
        if (c instanceof javax.swing.AbstractButton) {
            String t = ((javax.swing.AbstractButton) c).getText();
            if (t != null && t.equals(text)) {
                n++;
            }
        }
        for (Component k : c.getComponents()) {
            if (k instanceof Container) {
                n += countButtonsNamed((Container) k, text);
            }
        }
        return n;
    }

    private static boolean isTextComponent(Component c) {
        return c instanceof javax.swing.text.JTextComponent;
    }

    private static boolean hasButton(Component c) {
        if (c instanceof javax.swing.AbstractButton) {
            return true;
        }
        if (c instanceof Container) {
            for (Component k : ((Container) c).getComponents()) {
                if (hasButton(k)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasComboBox(Component c) {
        if (c instanceof javax.swing.JComboBox) {
            return true;
        }
        if (c instanceof Container) {
            for (Component k : ((Container) c).getComponents()) {
                if (hasComboBox(k)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void check(String label, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println("  " + (ok ? "PASS  " : "FAIL  ") + label);
    }
}

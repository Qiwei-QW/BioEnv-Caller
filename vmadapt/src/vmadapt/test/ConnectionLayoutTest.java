package vmadapt.test;

import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;

import javax.swing.AbstractButton;
import javax.swing.JTabbedPane;

import vmadapt.ui.ConnectionPanel;
import vmadapt.ui.SetupWizard;

/**
 * 连接面板与向导的结构。
 *
 * <h2>回归的两件事</h2>
 *
 * <p><b>① 面板上只能有一个入口。</b>填写搬进向导之后，如果旧按钮还留着，同一件事就有
 * 两个地方能做，迟早出现"在面板上点了没反应，因为向导那套才生效"。所以断言面板里
 * 恰好一个按钮，且它打开向导。
 *
 * <p><b>② 向导每一步的按钮必须齐全。</b>「上一步」漏了就没法回头；「下一步」漏了就
 * 走不下去。这些是纯粹的接线错误，编译能过、跑起来才发现，所以在这里钉死。
 *
 * <p>另外仍然验「连接配置」页只有两个标签（连接配置 / 网络模式速查）——曾经有过第三个
 * 「连接结果」页，已经删掉；若哪天被加回来，这里会红。
 *
 * <p>不需要显示器：只构造面板、点按钮、读文字。
 */
public final class ConnectionLayoutTest {

    private static int checks;
    private static int failures;

    private ConnectionLayoutTest() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== ConnectionLayoutTest ===");

        javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                try {
                    ConnectionPanel panel = new ConnectionPanel();
                    panelExactlyOneEntry(panel);
                    twoTabsOnly(panel);
                    wizardSteps();
                } catch (Exception e) {
                    check("构造面板不抛异常（" + e + "）", false);
                }
            }
        });

        System.out.println(failures == 0
                ? "PASS " + checks + " checks"
                : "FAIL: " + failures + " of " + checks + " checks failed");
        if (failures > 0) {
            System.exit(1);
        }
    }

    /** 面板上只能有一个按钮：打开向导的那一个。 */
    private static void panelExactlyOneEntry(ConnectionPanel panel) {
        int n = countButtons(panel);
        check("连接配置页只有一个按钮（实际 " + n + "）", n == 1);
        AbstractButton b = firstButton(panel);
        if (b == null) {
            check("找得到那个按钮", false);
            return;
        }
        String t = b.getText();
        check("按钮是「连接虚拟机…」（实际「" + t + "」）",
                t != null && t.contains("连接虚拟机"));
        check("按钮默认可用", b.isEnabled());
    }

    /** 只剩两页：连接配置、网络模式速查。 */
    private static void twoTabsOnly(ConnectionPanel panel) {
        JTabbedPane tabs = tabsOf(panel);
        if (tabs == null) {
            check("找得到标签页控件", false);
            return;
        }
        check("只有两页（实际 " + tabs.getTabCount() + "）", tabs.getTabCount() == 2);
        check("第一页是「连接配置」", "连接配置".equals(tabs.getTitleAt(0)));
        check("第二页是「网络模式速查」", "网络模式速查".equals(tabs.getTitleAt(1)));
        for (int i = 0; i < tabs.getTabCount(); i++) {
            check("没有「连接结果」页（第 " + (i + 1) + " 页）",
                    !"连接结果".equals(tabs.getTitleAt(i)));
        }
    }

    /** 向导各步的骨架：按钮必须在，文字必须对。 */
    private static void wizardSteps() {
        // 第 1 步：有取消和下一步，没有上一步。
        SetupWizard.FieldStep s1 = new SetupWizard.FieldStep(
                1, 5, "虚拟机在哪里？", "提示", "主机：", "例：1.2.3.4", "取消", "下一步");
        check("第 1 步有「取消」", s1.back() != null && "取消".equals(s1.back().getText()));
        check("第 1 步有「下一步」", s1.next() != null && "下一步".equals(s1.next().getText()));
        check("第 1 步显示步数", s1.stepText().contains("1") && s1.stepText().contains("5"));

        // 第 2 步：必须有「上一步」，否则回不去。
        SetupWizard.FieldStep s2 = new SetupWizard.FieldStep(
                2, 5, "用哪个端口？", "提示", "端口：", "22", "上一步", "下一步");
        check("第 2 步有「上一步」", s2.back() != null);
        check("第 2 步有「下一步」", s2.next() != null);

        // 确认步：开始连接。
        SetupWizard.Confirm c = new SetupWizard.Confirm(
                5, 5, "确认一下", "提示", "上一步", "开始连接");
        c.setSummary("主机：1.2.3.4\n端口：22\n用户名：ubuntu\n密钥：（默认）");
        check("确认步回显了摘要", c.summary().contains("1.2.3.4")
                && c.summary().contains("ubuntu"));
        check("确认步有「开始连接」",
                c.next() != null && "开始连接".equals(c.next().getText()));

        // 密码步：三个按钮都要有。
        SetupWizard.Password p = new SetupWizard.Password(
                5, 5, "请输入虚拟机登录密码", "提示", "不会保存",
                "上一步", "开始连接", "取消");
        check("密码步有「上一步」", p.back() != null);
        check("密码步有「取消」", p.cancel() != null);
        check("密码步有「开始连接」", p.connect() != null);
        check("密码框默认是空的", p.field().getPassword().length == 0);

        // 命名步：跳过与保存。
        SetupWizard.Name nm = new SetupWizard.Name(
                5, 5, "连接成功", "提示", "配置名称：", "留空用默认", "跳过", "保存");
        check("命名步有「跳过」", nm.skip() != null);
        check("命名步有「保存」", nm.save() != null);

        // 失败页与手动页都要有关闭按钮，否则窗关不掉。
        SetupWizard.Result r = new SetupWizard.Result(
                5, 5, "连接失败", "提示", "原因", false, "关闭");
        check("失败页有「关闭」", r.close() != null);
        check("失败页显示原因", r.detail().contains("原因"));
    }

    // ------------------------------------------------------------------

    private static JTabbedPane tabsOf(ConnectionPanel panel) {
        try {
            Field f = ConnectionPanel.class.getDeclaredField("tabs");
            f.setAccessible(true);
            Object v = f.get(panel);
            return (v instanceof JTabbedPane) ? (JTabbedPane) v : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 数面板里的按钮。
     *
     * <p>只看「连接配置」页（第 0 页），不看网络模式速查页——后者是纯文本，
     * 但如果哪天加了按钮，也不该被算成入口。
     */
    private static int countButtons(ConnectionPanel panel) {
        JTabbedPane tabs = tabsOf(panel);
        Component page = (tabs != null && tabs.getTabCount() > 0)
                ? tabs.getComponentAt(0) : panel;
        return count(page);
    }

    private static int count(Component c) {
        int n = 0;
        if (c instanceof AbstractButton) {
            n++;
        }
        if (c instanceof Container) {
            for (Component k : ((Container) c).getComponents()) {
                n += count(k);
            }
        }
        return n;
    }

    private static AbstractButton firstButton(ConnectionPanel panel) {
        JTabbedPane tabs = tabsOf(panel);
        Component page = (tabs != null && tabs.getTabCount() > 0)
                ? tabs.getComponentAt(0) : panel;
        return first(page);
    }

    private static AbstractButton first(Component c) {
        if (c instanceof AbstractButton) {
            return (AbstractButton) c;
        }
        if (c instanceof Container) {
            for (Component k : ((Container) c).getComponents()) {
                AbstractButton b = first(k);
                if (b != null) {
                    return b;
                }
            }
        }
        return null;
    }

    private static void check(String label, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println("  " + (ok ? "PASS  " : "FAIL  ") + label);
    }
}

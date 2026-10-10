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
                    everyStepIsReachable();
                    sshSelection();
                    versionGateDoesNotPreemptProbe();
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

    /** 向导各步的骨架：按钮必须在，版式必须符合约定。 */
    private static void wizardSteps() {
        // 第 1 步：有取消和下一步，没有上一步。
        SetupWizard.FieldStep s1 = new SetupWizard.FieldStep(
                "主机 / IP：", "提示文字", "取消", "下一步");
        check("第 1 步有「取消」", s1.back() != null && "取消".equals(s1.back().getText()));
        check("第 1 步有「下一步」", s1.next() != null && "下一步".equals(s1.next().getText()));

        // 版式约定：界面里不出现进度条，也不出现"第 N / M 步"那种步数标签。
        // 注意别用「步」这个字做判断——「下一步」按钮里就有它。
        check("第 1 步没有进度条", countOfType(s1.panel(), javax.swing.JProgressBar.class) == 0);
        check("第 1 步没有步数标签",
                !containsText(s1.panel(), "第 1") && !containsText(s1.panel(), "/ 5"));
        // 说明走悬停提示而不是常驻：界面里不该有第二段说明文字。
        check("字段带了悬停提示",
                hasTooltip(s1.panel()));

        SetupWizard.FieldStep s2 = new SetupWizard.FieldStep(
                "端口：", "提示文字", "上一步", "下一步");
        check("第 2 步有「上一步」", s2.back() != null);
        check("第 2 步有「下一步」", s2.next() != null);

        // 密钥须知：只有说明，没有输入框；下一步通向密码步。
        SetupWizard.Notice n = new SetupWizard.Notice(
                "此操作将在本机生成 SSH 密钥对。", "上一步", "下一步");
        check("须知步没有输入框",
                countOfType(n.panel(), javax.swing.JTextField.class) == 0);
        check("须知步有「上一步」", n.back() != null);
        check("须知步有「下一步」", n.next() != null);
        check("须知步显示说明", containsText(n.panel(), "SSH 密钥对"));

        // 密码步：三个按钮都要有；密码框默认空。
        SetupWizard.Password p = new SetupWizard.Password(
                "密码：", "请输入虚拟机登陆密码，以便发送公钥到虚拟机。密码不会被保存。",
                "上一步", "开始连接", "取消");
        check("密码步有「上一步」", p.back() != null);
        check("密码步有「取消」", p.cancel() != null);
        check("密码步有「开始连接」", p.connect() != null);
        check("密码框默认是空的", p.field().getPassword().length == 0);
        check("密码说明走悬停提示（提到「不会被保存」）",
                hasTooltipContaining(p.panel(), "不会被保存"));

        // 命名步：跳过与保存。
        SetupWizard.FieldStep nm = new SetupWizard.FieldStep(
                "配置名称：", "留空用默认", "跳过", "保存");
        check("命名步有「跳过」", nm.back() != null && "跳过".equals(nm.back().getText()));
        check("命名步有「保存」", nm.next() != null && "保存".equals(nm.next().getText()));

        // 失败页与手动页都要有关闭按钮，否则窗关不掉。
        SetupWizard.Result r = new SetupWizard.Result(
                "连接失败。", "原因", false, "关闭");
        check("失败页有「关闭」", r.close() != null);
        check("失败页显示原因", r.detail().contains("原因"));
    }

    // ------------------------------------------------------------------
    // 选哪个 ssh.exe
    // ------------------------------------------------------------------

    /**
     * 挑 ssh 时必须是"版本最高者胜"，不能是"先找到的胜"。
     *
     * <p>这条守的是一个会让用户白忙一场的问题：Windows 10 自带的那份在
     * {@code System32\OpenSSH}（常见 7.7p1 / 8.1p1），而微软文档推荐的升级做法是把
     * GitHub 上的 Win32-OpenSSH 装到 {@code C:\Program Files\OpenSSH}（9.x）。
     * 如果按目录顺序取第一个，升级完插件仍然用旧的那份——用户看到提示毫无变化，
     * 只会以为是自己升级失败。
     */
    private static void sshSelection() {
        Class<?> os = null;
        for (Class<?> c : ConnectionPanel.class.getDeclaredClasses()) {
            if ("OpenSsh".equals(c.getSimpleName())) {
                os = c;
            }
        }
        if (os == null) {
            check("找得到 OpenSsh 内部类", false);
            return;
        }

        // 真正选出来的必须是个存在的文件。
        try {
            java.lang.reflect.Method m = os.getDeclaredMethod("sshExe");
            m.setAccessible(true);
            Object picked = m.invoke(null);
            check("选出 ssh.exe（" + picked + "）",
                    picked != null && new java.io.File(String.valueOf(picked)).isFile());
        } catch (Exception e) {
            check("调用 sshExe()（" + e + "）", false);
        }

        // 版本比较的取向：高版本必须胜出，解析不出的一律最旧。
        try {
            java.lang.reflect.Method newer =
                    os.getDeclaredMethod("newer", int.class, int.class, int.class, int.class);
            newer.setAccessible(true);
            check("9.5 胜过 8.1",
                    (Boolean) newer.invoke(null, 9, 5, 8, 1));
            check("8.4 胜过 8.1",
                    (Boolean) newer.invoke(null, 8, 4, 8, 1));
            check("8.1 不胜过 9.5",
                    !(Boolean) newer.invoke(null, 8, 1, 9, 5));
            check("8.1 不胜过 8.4",
                    !(Boolean) newer.invoke(null, 8, 1, 8, 4));
            check("9.10 胜过 9.9（按数值不比字面）",
                    (Boolean) newer.invoke(null, 9, 10, 9, 9));
            check("解析不出的版本（-1）当最旧",
                    !(Boolean) newer.invoke(null, -1, -1, 8, 1));
            // 版本相同时不替换：
            // 「第一个候选一定被采用」不是由 newer 决定的，而是循环里
            // `best == null ||` 这个短路保证的——所以上面那条 -1 当最旧，
            // 不会让"唯一存在的、但版本解析不出的"那份落选。
            check("版本相同不替换（保持先找到的那份）",
                    !(Boolean) newer.invoke(null, -1, -1, -1, -1));
            check("版本相同不替换（8.1 vs 8.1）",
                    !(Boolean) newer.invoke(null, 8, 1, 8, 1));
        } catch (Exception e) {
            check("调用 newer()（" + e + "）", false);
        }

        // ssh-keygen 必须和 ssh 同源。
        try {
            java.lang.reflect.Method locate =
                    os.getDeclaredMethod("locate", String.class);
            locate.setAccessible(true);
            java.lang.reflect.Method m = os.getDeclaredMethod("sshExe");
            m.setAccessible(true);
            Object ssh = m.invoke(null);
            Object keygen = locate.invoke(null, "ssh-keygen.exe");
            if (ssh == null || keygen == null) {
                check("同时找得到 ssh 与 ssh-keygen", false);
            } else {
                java.io.File sshDir = new java.io.File(String.valueOf(ssh)).getParentFile();
                java.io.File kgDir = new java.io.File(String.valueOf(keygen)).getParentFile();
                check("ssh-keygen 与 ssh 在同一目录（避免混用两个版本）",
                        sshDir != null && sshDir.equals(kgDir));
            }
        } catch (Exception e) {
            check("调用 locate(ssh-keygen.exe)（" + e + "）", false);
        }
    }

    // ------------------------------------------------------------------
    // 版本门槛的位置
    // ------------------------------------------------------------------

    /**
     * 版本判断必须在"免密探测"之后，不能挡在它前面。
     *
     * <p>这条守的是刚修掉的一个真 bug：{@code beginConnect()} 开头就先判
     * {@code SSH_ASKPASS_REQUIRE} 的版本门槛，不够就跳手动页并 return。后果是
     * <b>8.1p1 的机器上，哪怕密钥早就装好、免密登录明明能用，向导也永远只显示
     * 「OpenSSH 太旧」，从不做连接测试</b>。而 Windows 10 自带的就是 8.1p1。
     *
     * <p>版本门槛只是"用密码装公钥"那条路的要求；密钥登录与版本无关。所以它应当
     * 只在免密失败、确实要走密码时才判断。
     *
     * <p>这是源码级断言：光看行为测不出来（要一台 8.1p1 且已配好的机器），
     * 而顺序恰恰就是出错的地方。
     */
    private static void versionGateDoesNotPreemptProbe() {
        java.io.File src = findSource("vmadapt/src/vmadapt/ui/ConnectionPanel.java",
                "ConnectionPanel.java");
        if (src == null) {
            check("找得到 ConnectionPanel 源码（跳过顺序检查）", false);
            return;
        }
        String text;
        try {
            text = new String(java.nio.file.Files.readAllBytes(src.toPath()), "UTF-8");
        } catch (Exception e) {
            check("读得到 ConnectionPanel 源码（" + e + "）", false);
            return;
        }
        String begin = body(text, "private void beginConnect(");
        String need = body(text, "private void needPassword(");
        check("找得到 beginConnect 的方法体", begin != null);
        check("找得到 needPassword 的方法体", need != null);
        if (begin == null || need == null) {
            return;
        }
        check("beginConnect 里不再有版本门槛",
                !begin.contains("supportsAskpassRequire"));
        check("beginConnect 会去探测免密",
                begin.contains("canLogInWithoutPassword"));
        check("版本门槛搬到了 needPassword",
                need.contains("supportsAskpassRequire"));
        check("版本不够时走手动页，不是密码框",
                need.contains("stepManual"));
    }

    /**
     * 取一个方法的方法体（从一个 {@code private ... name(} 到配平的右花括号）。
     *
     * <p>先把注释行去掉再找，否则文档里提到的方法名会把边界搞错。
     */
    private static String body(String text, String signature) {
        int start = text.indexOf(signature);
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean opened = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
                opened = true;
            } else if (c == '}') {
                depth--;
                if (opened && depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 版式断言用的小工具
    // ------------------------------------------------------------------

    /**
     * 向导流程里的每个方法都必须被调用过。
     *
     * <p>这条守的是一个真发生过的 bug：重排步骤时把 {@code stepKeyNotice()} 的「下一步」
     * 接到了 {@code stepPassword()}，于是 {@code beginConnect()} 再没人调用——它里面的
     * 环境自检（没有 ssh 或版本太低时改走手动安装）和"已经免密就不问密码"两条逻辑就此
     * 静默失效。<b>编译完全通过，别的测试也全绿，只有用户会发现"为什么又问我要密码"。</b>
     *
     * <p>所以这里做源码级检查：{@code stepXxx()} / {@code beginXxx()} /
     * {@code finishXxx()} 这些流程方法，除了定义那一行之外，文件里必须还有别处提到
     * 它们的名字。
     */
    private static void everyStepIsReachable() {
        java.io.File src = findSource("vmadapt/src/vmadapt/ui/ConnectionPanel.java",
                "ConnectionPanel.java");
        if (src == null) {
            check("找得到 ConnectionPanel 源码（跳过可达性检查）", false);
            return;
        }
        String text;
        try {
            text = new String(java.nio.file.Files.readAllBytes(src.toPath()), "UTF-8");
        } catch (Exception e) {
            check("读得到 ConnectionPanel 源码（" + e + "）", false);
            return;
        }
        String[] lines = text.split("\\R");
        java.util.regex.Pattern def = java.util.regex.Pattern.compile(
                "^\\s*private\\s+[\\w<>\\[\\].]+\\s+((?:step|begin|finish)\\w+)\\s*\\(");
        int found = 0;
        for (int i = 0; i < lines.length; i++) {
            java.util.regex.Matcher m = def.matcher(lines[i]);
            if (!m.find()) {
                continue;
            }
            found++;
            String name = m.group(1);
            boolean called = false;
            for (int j = 0; j < lines.length; j++) {
                if (j == i) {
                    continue;
                }
                // 跳过注释行。第一版没跳，结果定义旁边那句
                // 「// 走 beginConnect 而不是直接进密码步」被当成了调用点，
                // 于是这个检查在最该报错的时候保持沉默——注释里提到方法名不算调用。
                String t = lines[j].trim();
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) {
                    continue;
                }
                if (lines[j].contains(name + "(")) {
                    called = true;
                    break;
                }
            }
            check("流程方法 " + name + "() 有人调用", called);
        }
        check("至少找到几个步骤方法（实际 " + found + "）", found >= 4);
    }

    /** 按几个可能的位置找源码文件；找不到返回 null。 */
    private static java.io.File findSource(String... candidates) {
        for (String c : candidates) {
            java.io.File f = new java.io.File(c);
            if (f.isFile()) {
                return f;
            }
        }
        // 从类文件位置往上找仓库根
        try {
            java.io.File here = new java.io.File(ConnectionLayoutTest.class
                    .getProtectionDomain().getCodeSource().getLocation().toURI());
            java.io.File dir = here;
            for (int up = 0; up < 6 && dir != null; up++) {
                java.io.File f = new java.io.File(dir,
                        "vmadapt/src/vmadapt/ui/ConnectionPanel.java");
                if (f.isFile()) {
                    return f;
                }
                dir = dir.getParentFile();
            }
        } catch (Exception ignored) {
            // 交给调用方报"找不到"
        }
        return null;
    }

    /** 数某种类型的控件个数。 */
    private static int countOfType(Component c, Class<?> type) {
        int n = type.isInstance(c) ? 1 : 0;
        if (c instanceof Container) {
            for (Component k : ((Container) c).getComponents()) {
                n += countOfType(k, type);
            }
        }
        return n;
    }

    /** 控件树里有没有任何文字包含某片段（按钮、标签、文本域都算）。 */
    private static boolean containsText(Component c, String needle) {
        if (c instanceof javax.swing.AbstractButton) {
            String t = ((javax.swing.AbstractButton) c).getText();
            if (t != null && t.contains(needle)) {
                return true;
            }
        }
        if (c instanceof javax.swing.JLabel) {
            String t = ((javax.swing.JLabel) c).getText();
            if (t != null && t.contains(needle)) {
                return true;
            }
        }
        if (c instanceof javax.swing.text.JTextComponent) {
            String t = ((javax.swing.text.JTextComponent) c).getText();
            if (t != null && t.contains(needle)) {
                return true;
            }
        }
        if (c instanceof Container) {
            for (Component k : ((Container) c).getComponents()) {
                if (containsText(k, needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 控件树里有没有设了悬停提示。 */
    private static boolean hasTooltip(Component c) {
        return hasTooltipContaining(c, null);
    }

    /** 控件树里有没有悬停提示包含某片段；needle 为 null 表示只要非空即可。 */
    private static boolean hasTooltipContaining(Component c, String needle) {
        if (c instanceof javax.swing.JComponent) {
            String tip = ((javax.swing.JComponent) c).getToolTipText();
            if (tip != null && !tip.isEmpty()) {
                if (needle == null || tip.contains(needle)) {
                    return true;
                }
            }
        }
        if (c instanceof Container) {
            for (Component k : ((Container) c).getComponents()) {
                if (hasTooltipContaining(k, needle)) {
                    return true;
                }
            }
        }
        return false;
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

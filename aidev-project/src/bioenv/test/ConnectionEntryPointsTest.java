package bioenv.test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;

import javax.swing.JComboBox;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;

import bioenv.ui.MainPanel;

/**
 * 连接配置的入口在不在、以及下拉框里那一项"动作"有没有被当成配置。
 *
 * <h2>回归的缺陷一：连过一次就再也进不去配置窗</h2>
 *
 * <p>连接窗原本<b>只有一个入口</b>：切到 SSH 方式时自动弹出。而那个自动弹出由
 * {@code shouldAutoOpenDialog()} 把关，它在 {@code LinkState.hasEverConnected()}
 * 为真时直接返回 false —— 那是写在盘上的持久事实，第一次连接成功就永久为真。
 *
 * <p>于是第一次连上之后，用户既加不了第二台机器，也改不了填错的 IP：面板上没有按钮，
 * 菜单里没有项，双击也没绑。想改只能把配置删掉重建。
 *
 * <p>现在入口是下拉框里的最后一项「＋ 新增配置」。它不是一个配置，而是一个动作，
 * 混在纯数据的列表里 —— 所以本测试的重点之一就是：它必须被排除在"当前选中的配置"
 * 之外，否则删除会去找一条叫「＋ 新增配置」的配置。
 *
 * <h2>回归的缺陷二：只装一个发行版时看不到自己用的是哪个</h2>
 *
 * <p>发行版下拉框原本在 {@code size() > 1} 时才显示。自动探测本身没问题，被藏掉的
 * 只是"当前用的是它"这条信息。现在改成显示 + 置灰。
 *
 * <p>不需要显示器：只构造面板、读控件的可见性、选中项与文字。
 */
public final class ConnectionEntryPointsTest {

    private static int checks;
    private static int failures;

    private ConnectionEntryPointsTest() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== ConnectionEntryPointsTest ===");

        String oldHome = System.getProperty("user.home");
        File home = freshHome("bioenv-entry-");
        System.setProperty("user.home", home.getAbsolutePath());
        try {
            setLinkStateFile(new File(home, "link-state.json"));

            MainPanel panel = openPanel();
            JComboBox<?> box = distroBox(panel);

            // -----------------------------------------------------------
            // 0. 浏览 Linux 目录的接线必须在构造时就装好
            // -----------------------------------------------------------
            // 这条守的是用户报的「点小企鹅没反应」：接线原来装在 adoptRegistry 里，而
            // 它只在扫描/恢复之后才跑。在一台还没连过的机器上点那个按钮，dirBrowser
            // 还是 null——不报错，只是什么都不发生。
            check("构造完就已经装好浏览目录的接线（不依赖有没有连过）",
                    dirBrowserOf(panel) != null);

            // -----------------------------------------------------------
            // 1. SSH 方式：下拉框必须有「＋ 新增配置」这一项
            // -----------------------------------------------------------
            selectMode(panel, 1);
            waitForItems(box);
            check("SSH 方式下下拉框可见", box.isVisible());
            check("SSH 方式下下拉框可点", box.isEnabled());
            check("一条配置都没有时也有「＋ 新增配置」这一项（实际 "
                            + box.getItemCount() + " 项）",
                    box.getItemCount() == 1
                            && newItemText(panel).equals(
                                    String.valueOf(box.getItemAt(0))));
            check("没有任何配置时不选中任何项（选中项应当是空的）",
                    box.getSelectedIndex() < 0,
                    "实际选中索引 " + box.getSelectedIndex());

            // -----------------------------------------------------------
            // 2. 右键菜单：只剩「删除配置」
            // -----------------------------------------------------------
            JPopupMenu menu = profileMenu(panel);
            check("右键菜单只剩一项（实际 " + menu.getComponentCount() + "）",
                    menu.getComponentCount() == 1);
            check("那一项是「删除配置」（实际「" + itemText(menu, 0) + "」）",
                    "删除配置".equals(itemText(menu, 0)));

            // -----------------------------------------------------------
            // 3. 选中「＋ 新增配置」时，不能被当成"选中了一条配置"
            // -----------------------------------------------------------
            // 这是本次改动最容易出错的地方：列表里混了一个动作项，而"当前选中"的
            // 取法到处都在用。selectedProfileName() 必须把它滤掉。
            runOnEdt(new Runnable() {
                @Override
                public void run() {
                    box.setSelectedIndex(0);
                }
            });
            check("选中「＋ 新增配置」后 selectedProfileName() 为 null",
                    selectedProfileName(panel) == null,
                    "实际「" + selectedProfileName(panel) + "」");

            // -----------------------------------------------------------
            // 4. WSL：一个发行版 → 显示但不可点
            // -----------------------------------------------------------
            showDistros(panel, Collections.singletonList("Ubuntu"), "Ubuntu");
            check("只有一个发行版时下拉框仍显示", box.isVisible());
            check("只有一个发行版时下拉框不可点", !box.isEnabled());
            check("框里就是那个发行版",
                    "Ubuntu".equals(String.valueOf(box.getSelectedItem())),
                    "实际「" + box.getSelectedItem() + "」");

            showDistros(panel, Arrays.asList("Ubuntu", "Debian"), "Debian");
            check("两个发行版时下拉框显示", box.isVisible());
            check("两个发行版时下拉框可点", box.isEnabled());
            check("能选中指定的那个", "Debian".equals(String.valueOf(box.getSelectedItem())));

            // 空列表不走这里：showDistros 对空列表直接返回，把"没找到发行版"交给调用方
            // 用状态栏消息交代。判据是"前后完全不变"，而不是某个具体状态。
            boolean visBefore = box.isVisible();
            boolean enBefore = box.isEnabled();
            showDistros(panel, Collections.<String>emptyList(), null);
            check("空列表不改可见性（契约：空列表由调用方处理）",
                    box.isVisible() == visBefore);
            check("空列表不改可点状态", box.isEnabled() == enBefore);

            // -----------------------------------------------------------
            // 5. WSL（单发行版，框被禁用）→ SSH：框必须恢复可点
            // -----------------------------------------------------------
            // 不还原的话，SSH 方式下这个框会一直灰着，而新增配置就在里面。
            showDistros(panel, Collections.singletonList("Ubuntu"), "Ubuntu");
            check("前置：WSL 单发行版已把框禁用", !box.isEnabled());
            selectMode(panel, 1);
            waitForItems(box);
            check("切到 SSH 后下拉框恢复可点", box.isEnabled());
            check("切到 SSH 后下拉框恢复显示", box.isVisible());

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
    // 反射小工具：这些都该是私有的，不该为了测试放宽成 public
    // ------------------------------------------------------------------

    private static void runOnEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    private static MainPanel openPanel() throws Exception {
        final MainPanel[] p = new MainPanel[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                p[0] = new MainPanel();
            }
        });
        Thread.sleep(3000);
        return p[0];
    }

    private static Object field(Object target, String name) throws Exception {
        Field f = MainPanel.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    /**
     * 取 formPanel 上"浏览 Linux 目录"的接线。
     *
     * <p>用户报的「点小企鹅没反应」就是这条线断在 adoptRegistry 里：那个方法只在
     * 扫描/恢复之后才跑，而在一台还没连过的机器上面板照样显示工作目录那一行。所以
     * 这里断言它<b>构造完就已经接好</b>，不依赖有没有连过。
     */
    private static Object dirBrowserOf(MainPanel p) throws Exception {
        Object form = field(p, "formPanel");
        java.lang.reflect.Field f = form.getClass().getDeclaredField("dirBrowser");
        f.setAccessible(true);
        return f.get(form);
    }

    private static JComboBox<?> distroBox(MainPanel p) throws Exception {
        return (JComboBox<?>) field(p, "distroBox");
    }

    private static JPopupMenu profileMenu(MainPanel p) throws Exception {
        return (JPopupMenu) field(p, "profileMenu");
    }

    private static String itemText(JPopupMenu menu, int i) {
        return ((JMenuItem) menu.getComponent(i)).getText();
    }

    /** 下拉框里那个"新增配置"项的实际文字。 */
    private static String newItemText(MainPanel p) throws Exception {
        Method m = MainPanel.class.getDeclaredMethod("newProfileItemText");
        m.setAccessible(true);
        return String.valueOf(m.invoke(p));
    }

    private static String selectedProfileName(MainPanel p) throws Exception {
        Method m = MainPanel.class.getDeclaredMethod("selectedProfileName");
        m.setAccessible(true);
        return (String) m.invoke(p);
    }

    /** 切换连接方式，走的是和用户点选一样的那条监听。 */
    private static void selectMode(final MainPanel p, final int index)
            throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                try {
                    ((JComboBox<?>) field(p, "modeBox")).setSelectedIndex(index);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        Thread.sleep(500);
    }

    /**
     * 等 SSH 那份配置列表读完。
     *
     * <p>列表在 SwingWorker 里异步读盘，读完才会往框里填「＋ 新增配置」。
     * 不等的话断言会跑在列表还是空的时刻上。
     */
    private static void waitForItems(JComboBox<?> box) throws Exception {
        for (int i = 0; i < 60; i++) {
            if (box.getItemCount() > 0) {
                return;
            }
            Thread.sleep(250);
        }
    }

    /** 直接喂一份发行版列表，绕开 wsl.exe 枚举。 */
    private static void showDistros(final MainPanel p, final java.util.List<String> list,
                                    final String chosen) throws Exception {
        final Method m = MainPanel.class.getDeclaredMethod("showDistros",
                java.util.List.class, String.class);
        m.setAccessible(true);
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                try {
                    m.invoke(p, list, chosen);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    private static void setLinkStateFile(File f) throws Exception {
        Class<?> ls = Class.forName("bioenv.ui.LinkState");
        Method m = ls.getDeclaredMethod("setFile", File.class);
        m.setAccessible(true);
        m.invoke(null, f);
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

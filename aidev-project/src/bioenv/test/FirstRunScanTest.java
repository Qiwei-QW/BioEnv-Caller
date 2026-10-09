package bioenv.test;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import javax.swing.JLabel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import bioenv.ui.MainPanel;

/**
 * 首次安装的启动路径：必须引导用户选连接方式，且不得谎称"正在扫描"。
 *
 * <h2>回归的缺陷</h2>
 *
 * <p>第一次装上插件（这台机器从没连成功过、也没有任何扫描缓存）时，打开面板会显示
 * <b>「没有上次结果，正在扫描环境…」</b>，但<b>并没有任何扫描在跑</b>：自动路径刻意
 * 不做长任务，它把扫描留给用户主动点。用户看到的是一个永远停在"正在扫描"、却什么
 * 也不会发生的界面——而真正该发生的事（提示用户选连接方式）根本没出现。
 *
 * <p>根因是自动路径在"没有缓存、已放弃扫描"这条分支上复用了扫描中的状态文案。
 * 同一句文案在另外三处是<b>对的</b>，因为那三处紧跟着就真的调用 {@code scan()}；
 * 只有"决定不扫"的两个分支在撒谎。WSL 与 SSH 各有一处，两边都要改。
 *
 * <h2>期望行为</h2>
 *
 * <ol>
 *   <li><b>首次安装</b>：提示用户选择连接方式，再由用户的动作发起扫描；</li>
 *   <li><b>第二次起</b>：直接自动加载上次的结果，不再打扰。</li>
 * </ol>
 *
 * <p>{@code AutoLoadTest} 覆盖的是第 2 条里"连过但连不上"的情形（它显式写明
 * "首次安装是另一条路径，这里不测"），本测试补上首次安装这一条。
 *
 * <h2>两个隔离要点</h2>
 *
 * <ul>
 *   <li>{@code user.home} 指向临时目录，免得读到真实配置、语言与"已连过"标记；</li>
 *   <li>{@code LinkState} 的标记路径是<b>类加载时求值一次</b>的静态字段，只改
 *       {@code user.home} 不够——第二个场景会继续用第一个场景的路径。所以每个
 *       场景都通过 {@code setFile} 显式重设（那正是它存在的理由：自检不能读写
 *       用户真实的"已连过"状态）。</li>
 * </ul>
 */
public final class FirstRunScanTest {

    private static int checks;
    private static int failures;

    private FirstRunScanTest() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== FirstRunScanTest ===");

        firstInstallGuidesAndDoesNotLie();
        secondRunWithNoCacheTellsTheTruth();

        System.out.println(failures == 0
                ? "PASS " + checks + " checks"
                : "FAIL: " + failures + " of " + checks + " checks failed");
        if (failures > 0) {
            System.exit(1);
        }
    }

    /**
     * 首次安装：干净的 home，没有 linked.txt，没有任何缓存。
     *
     * <p>断言"说到做到"：没有任何扫描在跑，所以状态栏不得说在扫；同时必须出现
     * 引导——真机上打开连接配置对话框，无头环境下退化为"打不开对话框"的说明。
     * 两者都是有话直说，都不是"正在扫描"。
     */
    private static void firstInstallGuidesAndDoesNotLie() throws Exception {
        File home = freshHome("tbvm-firstrun-");
        System.setProperty("user.home", home.getAbsolutePath());
        // 没有 linked.txt：这台机器从没连成功过。显式指向一个不存在的路径，
        // 而不是依赖默认值——默认值可能在类加载时已经指向别的场景的目录。
        setLinkStateFile(new File(home, ".TBtools/BioEnvCaller/linked.txt"));

        MainPanel panel = openPanel();
        System.out.println("首次安装 · 状态栏: " + statusOf(panel));

        String status = statusOf(panel);
        String log = logOf(panel);

        // 一、不得谎称正在扫描。这是缺陷本身。
        check("首次安装不得显示「正在扫描」", !status.contains("正在扫描"));

        // 二、也不得声称有"上次结果"——第一次装上没有上次。
        check("首次安装不得提「上次结果」", !status.contains("上次"));

        // 三、必须出现引导：要么是首次使用提示，要么是无头环境下"对话框打不开"
        //     的说明（那说明引导确实被尝试过）。
        check("首次安装出现引导（首次提示或等价说明）",
                status.contains("首次使用") || status.contains("图形界面")
                        || status.contains("选择连接方式"));

        // 四、确实没有扫描发生：日志里不该有扫描完成的痕迹。
        check("首次安装没有真的扫描",
                !log.contains("Scanned ") && !log.contains("=== 命令发现 ==="));

        deleteRecursively(home);
    }

    /**
     * 第二次起、但这个目标还没有缓存：仍然不许说"正在扫描"，而要说清"要你点一下"。
     *
     * <p>这条路径与首次安装共用同一段代码，区别只在 {@code linked.txt} 在不在。
     * 两者都不能撒谎，但话不一样：首次安装要教用户怎么开始，已经用过的机器只需
     * 提醒他点扫描。
     */
    private static void secondRunWithNoCacheTellsTheTruth() throws Exception {
        File home = freshHome("tbvm-secondrun-");
        System.setProperty("user.home", home.getAbsolutePath());

        File stateDir = new File(home, ".TBtools" + File.separator + "BioEnvCaller");
        stateDir.mkdirs();
        File marker = new File(stateDir, "linked.txt");
        FileWriter w = new FileWriter(marker);
        try {
            w.write("linked\n");
        } finally {
            w.close();
        }
        setLinkStateFile(marker);

        MainPanel panel = openPanel();
        String status = statusOf(panel);
        System.out.println("第二次运行 · 状态栏: " + status);

        check("第二次运行不得显示「正在扫描」", !status.contains("正在扫描"));
        // 已经连过的机器不该再被当成首次使用来引导。
        check("第二次运行不再走首次使用引导", !status.contains("首次使用"));

        deleteRecursively(home);
    }

    private static MainPanel openPanel() throws Exception {
        final MainPanel[] p = new MainPanel[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                p[0] = new MainPanel();
            }
        });
        // 自动路径要跑 WSL 枚举 + conda 探测，留足余量。
        Thread.sleep(12000);
        return p[0];
    }

    /**
     * 重设 {@code LinkState} 的标记路径。
     *
     * <p>用反射而不是把它改成 public：那个方法是给自检用的，不该出现在生产 API 里。
     */
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

    private static String statusOf(MainPanel panel) throws Exception {
        Field f = MainPanel.class.getDeclaredField("statusLabel");
        f.setAccessible(true);
        final String[] out = new String[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                try {
                    out[0] = ((JLabel) f.get(panel)).getText();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        return out[0] == null ? "" : out[0];
    }

    private static String logOf(MainPanel panel) throws Exception {
        Field f = MainPanel.class.getDeclaredField("logArea");
        f.setAccessible(true);
        final String[] out = new String[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                try {
                    out[0] = ((JTextArea) f.get(panel)).getText();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        return out[0] == null ? "" : out[0];
    }

    private static void check(String label, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println("  " + (ok ? "PASS  " : "FAIL  ") + label);
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
}

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
        reopeningInVmModeDoesNotPopTheDialog();
        pickingVmModeOpensTheDialog();

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

        // 一、不得谎称正在扫描。这是最初报的缺陷。
        check("首次安装不得显示「正在扫描」", !status.contains("正在扫描"));

        // 二、也不得声称有"上次结果"——第一次装上没有上次。
        check("首次安装不得提「上次结果」", !status.contains("上次"));

        // 三、必须告诉用户下一步做什么。
        check("首次安装给出可执行的下一步（选择连接方式 / 点扫描）",
                status.contains("选择连接方式") || status.contains("扫描"));

        // 四、**不得弹出虚拟机配置窗**。
        //
        //     默认方式是 WSL，而那个弹窗是「Linux 虚拟机连接配置」——在 WSL 模式下
        //     它跟用户当下要做的事无关，弹出来只会打断人。它只该在用户从下拉框里
        //     主动选了虚拟机方式时出现。
        //
        //     无头环境下弹窗会退化成"图形界面打不开"那句状态文案，所以那句话正好
        //     可以当作"弹窗被尝试过"的探针——这正是上一版测试漏掉的地方：它把
        //     "对话框打不开"也算成了合格引导，于是掩盖了弹窗被误触发。
        check("首次安装（WSL 方式）不得弹出虚拟机配置窗",
                !status.contains("图形界面"));
        check("状态栏是首次使用引导，而不是弹窗的回退文案",
                status.contains("首次使用"));

        // 五、确实没有扫描发生：日志里不该有扫描完成的痕迹。
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
        // WSL 方式下同样不该弹虚拟机配置窗。
        check("第二次运行（WSL 方式）不得弹出虚拟机配置窗",
                !status.contains("图形界面"));

        deleteRecursively(home);
    }

    /**
     * 上次用的是虚拟机方式，这次<b>只是打开面板</b>：不许自动弹配置窗。
     *
     * <h2>回归的缺陷</h2>
     *
     * <p>"要不要弹窗"只看两件事：方式是虚拟机、这台机器从没连成功过。于是只要用户
     * 上次用过虚拟机方式，之后<b>每次打开插件都会弹</b>——即使他只是想看看命令列表。
     *
     * <p>这与最初那条要求相冲突：弹窗应当由"用户主动从下拉框里选了虚拟机方式"触发，
     * 而不是由"上次恰好用的是虚拟机方式"触发。恢复上次的选择是<b>便利</b>，不该顺带
     * 变成一次打断。
     *
     * <p>区分这两条路径靠的是调用来源：打开面板走 {@code loadDistroAsync}，用户改下拉框
     * 走 {@code applyConnectionMode}。两者最终都会读配置列表，但只有后者该弹出引导。
     */
    private static void reopeningInVmModeDoesNotPopTheDialog() throws Exception {
        File home = freshHome("tbvm-reopen-");
        System.setProperty("user.home", home.getAbsolutePath());
        // 从没连过：这是弹窗的另一个前提条件，必须成立才能验出"打开面板不弹"。
        setLinkStateFile(new File(home, ".TBtools/BioEnvCaller/linked.txt"));

        new bioenv.core.ConnectionModeStore().save(
                bioenv.core.ConnectionModeStore.MODE_VM);

        MainPanel panel = openPanel();
        String status = statusOf(panel);
        System.out.println("上次是虚拟机方式 · 状态栏: " + status);

        check("只是打开面板，不得弹出配置窗", !status.contains("图形界面"));

        deleteRecursively(home);
    }

    /**
     * 用户主动把下拉框改成「虚拟机」：这时<b>应该</b>弹配置窗。
     *
     * <p>与上一条成对：只测"打开面板不弹"的话，把弹窗整个删掉也能通过——那样第一次
     * 想用虚拟机的人就再没人引导他填地址了。
     */
    private static void pickingVmModeOpensTheDialog() throws Exception {
        File home = freshHome("tbvm-pick-");
        System.setProperty("user.home", home.getAbsolutePath());
        setLinkStateFile(new File(home, ".TBtools/BioEnvCaller/linked.txt"));

        // 启动时是 WSL（默认），然后由测试模拟用户去改下拉框。
        new bioenv.core.ConnectionModeStore().save(
                bioenv.core.ConnectionModeStore.MODE_WSL);

        MainPanel panel = openPanel();
        check("WSL 下打开面板不弹窗", !statusOf(panel).contains("图形界面"));

        // 模拟用户在下拉框里选「Linux 虚拟机」。
        selectVmMode(panel);
        Thread.sleep(4000);

        String status = statusOf(panel);
        System.out.println("用户切到虚拟机方式 · 状态栏: " + status);
        // 无头环境下弹窗退化为"图形界面打不开"，说明弹窗确实被尝试过。
        check("用户主动选虚拟机方式时弹出配置窗", status.contains("图形界面"));

        deleteRecursively(home);
    }

    /** 把「连接方式」下拉框切到虚拟机，走的是和用户点选一样的那条监听。 */
    private static void selectVmMode(MainPanel panel) throws Exception {
        final java.lang.reflect.Field f =
                MainPanel.class.getDeclaredField("modeBox");
        f.setAccessible(true);
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                try {
                    ((javax.swing.JComboBox<?>) f.get(panel)).setSelectedIndex(1);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
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

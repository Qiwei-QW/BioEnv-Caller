package bioenv.test;

import java.io.File;
import java.io.FileWriter;

import javax.swing.JLabel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import bioenv.core.ConnectionModeStore;
import bioenv.ui.MainPanel;

/**
 * 端到端：打开面板时自动连接 + 可达性卡点。
 *
 * <p>回归的是一条体验要求：以前打开插件永远停在 WSL、而且每次都要手动点一次扫描。
 * 现在打开面板会回到上次用的方式与那一台机器，并在<b>连接之前</b>先问一次
 * 「虚拟机开没开机」——因为直接 ssh 去问，虚拟机关机时要等完整握手加重试，
 * 用户看到的是「插件一打开就卡住十几秒」。
 *
 * <p>测试用一台不可路由的地址来制造「连不上」，于是能确定地断言：
 *
 * <ul>
 *   <li>面板确实回到了上次用的 Linux VM 方式（而不是默认的 WSL）；</li>
 *   <li>自动路径真的发起了可达性检查；</li>
 *   <li>不可达时给出的是那条具体提示，而不是通用的「连不上」；</li>
 *   <li><b>没有发生扫描</b>——自动路径绝不做长任务。</li>
 * </ul>
 *
 * <p>把 {@code user.home} 指到临时目录，否则会读到用户真实的连接配置与语言设置。
 */
public final class AutoLoadTest {

    private static int checks;
    private static int failures;

    private AutoLoadTest() {
    }

    public static void main(String[] args) throws Exception {
        File home = new File(System.getProperty("java.io.tmpdir"),
                "tbvm-autoload-" + System.nanoTime());
        if (!home.mkdirs()) {
            System.out.println("FAIL: 无法创建临时 home " + home);
            System.exit(1);
        }
        System.setProperty("user.home", home.getAbsolutePath());
        System.out.println("=== AutoLoadTest ===");

        // 1. 模拟「曾经连接成功过」：这样打开面板不会再弹引导窗，直接走自动路径。
        //    首次安装必须由用户手动点一次扫描，那是另一条路径，这里不测它。
        File stateDir = new File(home, ".TBtools" + File.separator + "BioEnvCaller");
        if (!stateDir.mkdirs()) {
            System.out.println("FAIL: 无法创建状态目录");
            System.exit(1);
        }
        FileWriter w = new FileWriter(new File(stateDir, "linked.txt"));
        try {
            w.write("1\n");
        } finally {
            w.close();
        }

        // 2. 存一台不可达的虚拟机，并记住上次用的就是 Linux VM 方式。
        vmadapt.core.SshTarget t =
                new vmadapt.core.SshTarget("10.255.255.1", 22, "nobody", null);
        t.label = "unreachable-test";
        check("测试用配置写入成功", new vmadapt.core.ConnectionStore().save(t));
        new ConnectionModeStore().save(ConnectionModeStore.MODE_VM);

        // 3. 打开面板。构造函数里会恢复方式 → 读配置 → 自动发起连接。
        final MainPanel[] p = new MainPanel[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                p[0] = new MainPanel();
            }
        });

        // 4. 等自动路径跑完：可达性检查的上限是 5 秒，留足余量。
        Thread.sleep(12000);

        final java.lang.reflect.Field logF = MainPanel.class.getDeclaredField("logArea");
        logF.setAccessible(true);
        final java.lang.reflect.Field statF = MainPanel.class.getDeclaredField("statusLabel");
        statF.setAccessible(true);
        final java.lang.reflect.Field modeF = MainPanel.class.getDeclaredField("modeBox");
        modeF.setAccessible(true);
        final String[] out = new String[2];
        final int[] mode = new int[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                try {
                    out[0] = ((JTextArea) logF.get(p[0])).getText();
                    out[1] = ((JLabel) statF.get(p[0])).getText();
                    mode[0] = ((javax.swing.JComboBox<?>) modeF.get(p[0]))
                            .getSelectedIndex();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        String log = out[0] == null ? "" : out[0];
        String status = out[1] == null ? "" : out[1];

        System.out.println("连接方式索引 = " + mode[0] + "（1 = Linux VM）");
        System.out.println("状态栏: " + status);
        System.out.println("日志:");
        String[] lines = log.split("\n");
        for (int i = 0; i < lines.length; i++) {
            System.out.println("  | " + lines[i]);
        }

        // 断言一：回到了上次用的方式，而不是重置成 WSL。
        check("打开面板回到了 Linux VM 方式", mode[0] == 1);

        // 断言二：自动路径确实做了可达性检查。
        check("自动路径发起了可达性检查",
                log.contains("自动连接前先检查目标机器是否可达"));

        // 断言三：不可达时给的是具体的那条，而不是通用的「连不上虚拟机」。
        check("给出了具体的不可达提示",
                log.contains("对端没有应答") || log.contains("没有服务在听")
                        || log.contains("无法解析或到达"));
        check("状态栏说明未自动连接", status.contains("目标机器不可达"));

        // 断言四：自动路径没有扫描。这是整条设计的底线——
        // 自动路径只做「连上 + 读缓存」，扫十几秒的事必须由用户主动发起。
        check("自动路径没有扫描",
                !log.contains("=== 命令发现 ===") && !log.contains("Scanned "));

        deleteRecursively(home);

        System.out.println(failures == 0
                ? "PASS " + checks + " checks"
                : "FAIL: " + failures + " of " + checks + " checks failed");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] kids = f.listFiles();
        if (kids != null) {
            for (int i = 0; i < kids.length; i++) {
                deleteRecursively(kids[i]);
            }
        }
        f.delete();
    }

    private static void check(String label, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("  FAIL: " + label);
        }
    }
}

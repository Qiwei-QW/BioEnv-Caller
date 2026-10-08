package bioenv.test;

import java.io.File;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import bioenv.ui.MainPanel;

/**
 * Linux VM 方式下「还没选配置就点扫描」的文案测试。
 *
 * <p>回归的是一个真实误报：这种情况下 {@code DiscoveryResult.sshTarget} 仍然是
 * null（因为压根没解析出目标），而判断通道用的是 {@code sshTarget != null}，
 * 于是走了 WSL 分支，在 Linux VM 模式下打出「WSL 不可达」——和用户在做的事
 * 毫无关系，把人往完全错误的方向带。
 *
 * <p>正确行为是分开三件事：<b>WSL 不可达</b>、<b>虚拟机连不上</b>、
 * <b>还没选虚拟机配置</b>。三者的下一步动作不同，文案必须不同。
 *
 * <p>测试把 {@code user.home} 指到临时目录，否则会读到用户真实的连接配置，
 * 结果随机器而变。
 */
public final class SshNoProfileMessageTest {

    private static int checks;
    private static int failures;

    private SshNoProfileMessageTest() {
    }

    public static void main(String[] args) throws Exception {
        // 第一件事就隔离 home：MainPanel 的三个落盘状态（连接配置、界面语言、
        // 已连成功标记）都基于 user.home，且那些类是在首次加载时读取它的。
        File home = new File(System.getProperty("java.io.tmpdir"),
                "tbvm-noprofile-" + System.nanoTime());
        if (!home.mkdirs()) {
            System.out.println("FAIL: 无法创建临时 home " + home);
            System.exit(1);
        }
        System.setProperty("user.home", home.getAbsolutePath());

        System.out.println("=== SshNoProfileMessageTest ===");

        final MainPanel[] p = new MainPanel[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                p[0] = new MainPanel();
            }
        });

        // 切到 Linux VM。此时连接配置是空的（home 已被隔离），所以没有任何可选项。
        final java.lang.reflect.Field modeF =
                MainPanel.class.getDeclaredField("modeBox");
        modeF.setAccessible(true);
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                try {
                    ((JComboBox<?>) modeF.get(p[0])).setSelectedIndex(1);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        Thread.sleep(1500);

        // 点「扫描 conda 环境」。
        final java.lang.reflect.Field detF =
                MainPanel.class.getDeclaredField("detectButton");
        detF.setAccessible(true);
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                try {
                    ((JButton) detF.get(p[0])).doClick();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        Thread.sleep(3000);

        final java.lang.reflect.Field logF = MainPanel.class.getDeclaredField("logArea");
        logF.setAccessible(true);
        final java.lang.reflect.Field statF = MainPanel.class.getDeclaredField("statusLabel");
        statF.setAccessible(true);
        final String[] out = new String[2];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                try {
                    out[0] = ((JTextArea) logF.get(p[0])).getText();
                    out[1] = ((JLabel) statF.get(p[0])).getText();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        String log = out[0] == null ? "" : out[0];
        String status = out[1] == null ? "" : out[1];

        System.out.println("日志：");
        String[] lines = log.split("\n");
        for (int i = 0; i < lines.length; i++) {
            System.out.println("  | " + lines[i]);
        }
        System.out.println("状态栏: " + status);

        // 核心断言：说清楚「还没选配置」，而不是扯到 WSL。
        check("日志说明了要先选配置", log.contains("还没有选中可用的 Linux 虚拟机配置"));
        check("日志不再误报 WSL", !log.contains("WSL 不可达"));
        check("状态栏提示还没有可用配置", status.contains("还没有可用的 Linux 虚拟机配置"));
        check("状态栏不只说「连不上」", !status.contains("连不上 Linux 虚拟机"));

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

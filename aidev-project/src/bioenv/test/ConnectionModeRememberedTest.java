package bioenv.test;

import java.io.File;

import javax.swing.JComboBox;
import javax.swing.SwingUtilities;

import bioenv.core.ConnectionModeStore;
import bioenv.ui.MainPanel;

/**
 * 「连接方式」跨重启记忆的测试。
 *
 * <p>回归的是一个纯体验缺陷：面板每次构造都把连接方式重置成 WSL。一个只在
 * Linux VM 上干活的人，每次打开插件都要手动切一次；而切模式本身会清空工具列表，
 * 于是还得再扫一遍。用户的原话是「退出 TBtools 再进来，它默认会到 WSL，
 * 而且还得重新扫」。
 *
 * <p>测试把 {@code user.home} 指到临时目录，否则会读到用户真实的连接配置和
 * 模式记录，结果随机器而变。
 */
public final class ConnectionModeRememberedTest {

    private static int checks;
    private static int failures;

    private ConnectionModeRememberedTest() {
    }

    public static void main(String[] args) throws Exception {
        // 第一件事就隔离 home：模式记录、连接配置、已连标记都基于 user.home。
        File home = new File(System.getProperty("java.io.tmpdir"),
                "tbvm-mode-" + System.nanoTime());
        if (!home.mkdirs()) {
            System.out.println("FAIL: 无法创建临时 home " + home);
            System.exit(1);
        }
        System.setProperty("user.home", home.getAbsolutePath());

        System.out.println("=== ConnectionModeRememberedTest ===");

        // 1. 全新环境：没有记录时应当落到 WSL（默认）。
        MainPanel first = newPanel();
        check("没有记录时默认 WSL", modeOf(first) == 0);

        // 2. 切到 Linux VM：应当被记下来。
        setMode(first, 1);
        ConnectionModeStore store = new ConnectionModeStore();
        check("模式文件写到了插件状态目录",
                store.getFile().getAbsolutePath().startsWith(home.getAbsolutePath()));
        check("模式文件记住了 vm",
                ConnectionModeStore.MODE_VM.equals(store.load()));

        // 3. 模拟重启：新建面板应当直接回到 Linux VM。
        MainPanel second = newPanel();
        check("重建后回到 Linux VM", modeOf(second) == 1);

        // 4. 切回 WSL 也要记住，不能只记一个方向。
        setMode(second, 0);
        check("切回后记住了 wsl",
                ConnectionModeStore.MODE_WSL.equals(new ConnectionModeStore().load()));
        MainPanel third = newPanel();
        check("重建后回到 WSL", modeOf(third) == 0);

        // 5. 文件损坏时要退回默认，而不是抛异常或乱猜。
        java.io.FileWriter w = new java.io.FileWriter(store.getFile());
        try {
            w.write("这不是一个合法值\n");
        } finally {
            w.close();
        }
        check("乱码内容退回默认 WSL",
                ConnectionModeStore.MODE_WSL.equals(new ConnectionModeStore().load()));

        deleteRecursively(home);

        System.out.println(failures == 0
                ? "PASS " + checks + " checks"
                : "FAIL: " + failures + " of " + checks + " checks failed");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static MainPanel newPanel() throws Exception {
        final MainPanel[] p = new MainPanel[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                p[0] = new MainPanel();
            }
        });
        return p[0];
    }

    private static int modeOf(final MainPanel p) throws Exception {
        final java.lang.reflect.Field f = MainPanel.class.getDeclaredField("modeBox");
        f.setAccessible(true);
        final int[] out = new int[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                try {
                    out[0] = ((JComboBox<?>) f.get(p)).getSelectedIndex();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        return out[0];
    }

    private static void setMode(final MainPanel p, final int index) throws Exception {
        final java.lang.reflect.Field f = MainPanel.class.getDeclaredField("modeBox");
        f.setAccessible(true);
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                try {
                    ((JComboBox<?>) f.get(p)).setSelectedIndex(index);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        Thread.sleep(300);
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

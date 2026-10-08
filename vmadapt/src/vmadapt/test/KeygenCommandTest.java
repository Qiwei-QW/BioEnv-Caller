package vmadapt.test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

import vmadapt.ui.ConnectionPanel;

/**
 * 生成密钥的回归测试：真的跑一次 ssh-keygen，检查两个文件都生成出来。
 *
 * <p>为什么要真跑：这个功能坏过一次，而且坏得很隐蔽。
 *
 * <p>{@code ssh-keygen -N ""} 需要一个<b>空参数</b>表示空口令。Java 的
 * {@code ProcessBuilder} 在 Windows 上会把空字符串参数静默丢掉，于是
 * {@code -N} 把下一个选项当成口令，ssh-keygen 报 {@code Too many arguments}
 * 并且一个文件都不生成。表面上"参数也都传了"，实际却错了位。
 *
 * <p>所以这个测试不检查字符串长得像不像，而是真的执行一遍并看磁盘结果——
 * 只有这样才能区分"命令拼对了"和"命令真的能用"。
 *
 * <p>找不到 ssh-keygen 时自动跳过（非 Windows 环境）。
 */
public final class KeygenCommandTest {

    private static int checks;
    private static int failures;

    private KeygenCommandTest() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== KeygenCommandTest ===");

        String keygen = locate("ssh-keygen.exe");
        if (keygen == null) {
            System.out.println("SKIP 没有找到 ssh-keygen.exe，跳过（非 Windows 环境）");
            System.out.println("PASS 0 checks (skipped)");
            return;
        }

        // 目录名故意带空格：验证 cmd 引号确实生效，而不是"恰好路径没有空格"。
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "tbvm keygen test " + System.nanoTime());
        if (!dir.mkdirs()) {
            System.out.println("FAIL: 无法创建临时目录 " + dir);
            System.exit(1);
        }

        try {
            File key = new File(dir, "id_test");
            String comment = "tbvm-test";
            String line = ConnectionPanel.keygenCommandLine(
                    keygen, comment, key.getAbsolutePath());

            // 1. 命令行必须原样带上 -N ""。
            check("命令行含 -N \"\"（空口令）", line.contains("-N \"\""));

            // 2. 真实执行——走生产用的那条路径（cmd.exe /s /c）。
            //
            //    刻意不在测试里重写一遍调用形式：这个 bug 的关键恰恰在调用形式
            //    （cmd /c 会剥掉以引号开头的命令串的首尾引号，/s 才不剥），
            //    测试若自己拼一遍就测不到生产代码到底怎么调的。
            ConnectionPanel.OpenSsh.Result r = ConnectionPanel.OpenSsh.runViaCmd(
                    "Execution failed: ", "Command: ", line);
            int rc = r.exitCode;
            check("ssh-keygen 退出码为 0（实际 " + rc + "）", rc == 0);
            if (rc != 0) {
                System.out.println("  输出：" + r.output.trim());
            }

            // 3. 两个文件都要生成，且都不能是空文件。
            //
            //    这一条才是关键：曾经出现过私钥生成了、.pub 却是 0 字节的情况，
            //    只看退出码会以为成功。
            File pub = new File(dir, "id_test.pub");
            check("私钥已生成", key.isFile());
            check("公钥已生成", pub.isFile());
            check("私钥非空", key.isFile() && key.length() > 0);
            check("公钥非空", pub.isFile() && pub.length() > 0);

            // 4. 公钥格式应形如 "ssh-rsa AAAA..."
            if (pub.isFile() && pub.length() > 0) {
                String content = read(pub);
                check("公钥以 ssh-rsa 开头", content.startsWith("ssh-rsa "));
                check("公钥带注释", content.contains(comment));
            } else {
                check("公钥以 ssh-rsa 开头", false);
                check("公钥带注释", false);
            }

            // 5. 注释里的 cmd 元字符必须被清理掉，不能原样进入命令行。
            String dirty = ConnectionPanel.keygenCommandLine(
                    keygen, "a&b|c>d", key.getAbsolutePath());
            check("注释里的 cmd 元字符被清理", !dirty.contains("a&b|c>d"));
        } finally {
            deleteRecursively(dir);
        }

        System.out.println(failures == 0
                ? "PASS " + checks + " checks"
                : "FAIL: " + failures + " of " + checks + " checks failed");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static String read(File f) throws Exception {
        BufferedReader br = new BufferedReader(new java.io.FileReader(f));
        try {
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = br.readLine()) != null) {
                sb.append(l).append('\n');
            }
            return sb.toString().trim();
        } finally {
            br.close();
        }
    }

    private static String locate(String exe) {
        String[] dirs = {
            "C:\\WINDOWS\\System32\\OpenSSH\\",
            "C:\\Program Files\\OpenSSH\\",
            "C:\\Program Files (x86)\\OpenSSH\\",
        };
        for (int i = 0; i < dirs.length; i++) {
            File f = new File(dirs[i], exe);
            if (f.isFile()) {
                return f.getAbsolutePath();
            }
        }
        return null;
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

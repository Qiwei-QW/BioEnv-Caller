package vmadapt.test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import vmadapt.core.KeyInstaller;
import vmadapt.core.SshAskpass;

/**
 * 对着<b>真实 sshd</b> 跑一遍"用密码装公钥"，验完再验免密登录。
 *
 * <h2>为什么需要这个</h2>
 *
 * <p>这是一键流程里唯一会改动虚拟机的操作，也是最容易"看起来对、其实没通"的地方——
 * 密码通道要凑齐好几件事：版本够、辅助程序能被启动、classpath 能被找到、远端命令
 * 真的执行。任何一件不成立，表现都是"密码错误"，而用户几乎不可能猜到真正的原因。
 *
 * <h2>它测的是真正跑的那份代码</h2>
 *
 * <p>直接调 {@link KeyInstaller#install}——就是界面调用的那个方法，不是抄一遍命令。
 * 抄一份来测，抄的那份和跑的那份可以各自漂移，测了等于没测。
 *
 * <h2>需要一台测试服务器</h2>
 *
 * <p>通过环境变量指定；没配就跳过，并明确说明"没有验证"，不假装通过：
 *
 * <pre>
 *   TBVM_LIVE_HOST=127.0.0.1
 *   TBVM_LIVE_PORT=2222
 *   TBVM_LIVE_USER=tbvmt
 *   TBVM_LIVE_PASSWORD=...
 * </pre>
 *
 * <p>它会动那台机器上该用户的 {@code authorized_keys}（追加一把公钥）。所以只对
 * 一次性的测试机用，别指向真在用的虚拟机。
 */
public final class KeyInstallLiveTest {

    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception {
        System.out.println("=== KeyInstallLiveTest ===");

        String host = env("TBVM_LIVE_HOST");
        String port = env("TBVM_LIVE_PORT");
        String user = env("TBVM_LIVE_USER");
        String password = env("TBVM_LIVE_PASSWORD");

        // 不需要服务器的那几条：无论有没有测试机都该成立。
        staticChecks();

        if (host == null || port == null || user == null || password == null) {
            System.out.println();
            System.out.println("未配置测试服务器，跳过端到端验证。");
            System.out.println("  $env:TBVM_LIVE_HOST='127.0.0.1'");
            System.out.println("  $env:TBVM_LIVE_PORT='2222'");
            System.out.println("  $env:TBVM_LIVE_USER='tbvmt'");
            System.out.println("  $env:TBVM_LIVE_PASSWORD='...'");
            System.out.println();
            System.out.println("注意：本次<b>没有</b>验证公钥安装是否真的可用。");
            System.out.println(failures == 0
                    ? "PASS " + checks + " checks (端到端未验证)"
                    : "FAIL: " + failures + " of " + checks);
            if (failures > 0) {
                System.exit(1);
            }
            return;
        }

        String ssh = firstExisting(
                "C:\\WINDOWS\\System32\\OpenSSH\\ssh.exe", "/usr/bin/ssh");
        check("找得到 ssh.exe（" + ssh + "）", ssh != null);
        if (ssh == null) {
            report();
            return;
        }

        // 密钥对：装到一个临时目录，不碰用户真正的 ~/.ssh。
        File dir = Files.createTempDirectory("tbvm-live").toFile();
        File key = new File(dir, "id_live");
        String keygen = ssh.replace("ssh.exe", "ssh-keygen.exe")
                .replace("/ssh", "/ssh-keygen");
        check("生成测试私钥", keygen(keygen, key));

        // 公钥用生产同一段逻辑取：优先 .pub，取不到就从私钥现推。
        // 这个构建沙箱里 ssh-keygen 写 .pub 会失败（Bad file descriptor），
        // 正好顺带验证了兜底路径。
        String pub = KeyInstaller.publicKeyOf(keygen, key);
        check("取得公钥正文（" + (pub == null ? "null" : pub.length() + " 字符")
                + "）", pub != null && !pub.isEmpty());
        if (pub == null || pub.isEmpty()) {
            deleteTree(dir);
            report();
            return;
        }

        int portNo = Integer.parseInt(port);

        // ① 密码错时必须分型为 AUTH，而不是含混的失败。
        KeyInstaller.Outcome bad = KeyInstaller.install(
                ssh, host, portNo, user, pub, password + "-definitely-wrong", 10);
        check("密码错时报 AUTH（实际 " + bad.kind + "）",
                bad.kind == KeyInstaller.Kind.AUTH);
        check("密码错的说明指出是认证问题",
                bad.reason != null && bad.reason.contains("认证"));

        // ② 正确密码：应该装上。
        KeyInstaller.Outcome ok = KeyInstaller.install(
                ssh, host, portNo, user, pub, password, 10);
        check("正确密码能装上公钥（kind=" + ok.kind + "）", ok.ok());
        if (!ok.ok()) {
            System.out.println("      失败原因: " + ok.reason);
            System.out.println("      原始输出: " + ok.output);
        }

        // ③ 再装一次：必须仍然成功（去重逻辑不能把自己判成失败）。
        KeyInstaller.Outcome again = KeyInstaller.install(
                ssh, host, portNo, user, pub, password, 10);
        check("重复安装仍然成功（不会重复写入）", again.ok());

        // ④ 关键一步：免密登录真的能用吗？
        //    这一步才证明"装上去了"，而不是"命令退出码是 0"。
        check("公钥登录可用（BatchMode 免密）", keyLoginWorks(ssh, host, portNo, user, key));

        // ⑤ 不带 -i 必须连不上。
        //
        //    这条不是凑数：我们的密钥叫 id_rsa_tbvm，不在 ssh 的默认密钥名里
        //    （id_rsa / id_ed25519 …），所以只有显式 -i 才会被用上。曾经
        //    finishConnect() 就没带 -i，后果是"公钥装好了但连接仍然失败/仍要密码"，
        //    而没有任何一处会提示原因。把「不带 -i 会失败」钉下来，
        //    就等于钉住了"连接时必须传 -i"这个前提。
        check("不带 -i 时按默认密钥名连不上（这正是必须传 -i 的原因）",
                !keyLoginWithoutDashI(ssh, host, portNo, user));

        // ⑤ 远端 authorized_keys 里那把 key 只应出现一次。
        String count = run(ssh, "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=no",
                "-i", key.getAbsolutePath(), "-p", String.valueOf(portNo),
                user + "@" + host,
                "grep -cxF '" + pub + "' ~/.ssh/authorized_keys");
        // ssh 会把 known_hosts 之类的告警混进同一路输出，所以只取最后一行数字，
        // 不对整段输出做等值比较。
        String n = lastNumber(count);
        check("authorized_keys 里该公钥恰好 1 行（实际 " + n + "）", "1".equals(n));

        cleanup(ssh, host, portNo, user, key, pub, dir);
        report();
    }

    // ------------------------------------------------------------------
    // 不需要服务器也能验的几条
    // ------------------------------------------------------------------

    private static void staticChecks() {
        // 分型逻辑：这是"密码错"和"网络不通"能不能分开说的根据。
        check("认得出 Permission denied",
                KeyInstaller.classify("tbvmt@h: Permission denied (publickey,password).", 255)
                        == KeyInstaller.Kind.AUTH);
        check("认得出 Connection refused",
                KeyInstaller.classify("ssh: connect to host h port 22: Connection refused", 255)
                        == KeyInstaller.Kind.NETWORK);
        check("认得出 Connection timed out",
                KeyInstaller.classify("ssh: connect to host h port 22: Connection timed out", 255)
                        == KeyInstaller.Kind.NETWORK);
        check("认得出 Host key verification failed",
                KeyInstaller.classify("Host key verification failed.", 255)
                        == KeyInstaller.Kind.AUTH);
        check("认不出的归 OTHER",
                KeyInstaller.classify("something else entirely", 1)
                        == KeyInstaller.Kind.OTHER);

        // 版本门槛：不够时界面会走手动那条路，所以这个判断必须准。
        check("8.4 支持 askpass", SshAskpass.supportsAskpassRequire(8, 4));
        check("9.5 支持 askpass", SshAskpass.supportsAskpassRequire(9, 5));
        check("8.3 不支持 askpass", !SshAskpass.supportsAskpassRequire(8, 3));

        // classpath 必须从本类位置推出来。TBtools 的 java.class.path 里没有插件 jar，
        // 用它会 ClassNotFound，表现成"密码错误"——所以这条要钉住。
        String cp = SshAskpass.classpath();
        check("classpath() 非空", cp != null && !cp.isEmpty());
        File self = new File(cp);
        check("classpath() 指向真实存在的路径（" + cp + "）", self.exists());

        // 验证命令必须带 -i。
        //
        // 我们的密钥叫 id_rsa_tbvm，不在 ssh 的默认密钥名里，所以只有显式 -i 才会被用。
        // 曾经漏掉过：装公钥那一步自己带了 -i，所以装得上；而验证与随后的连接都按默认
        // 密钥名去找，必然失败——用户看到的是"公钥装好了却连不上"，无从排查。
        java.util.List<String> keyed = KeyInstaller.verifyCommand(
                "ssh.exe", "h", 22, "u", "/home/u/.ssh/id_rsa_tbvm", 15);
        check("有密钥时验证命令带 -i", keyed.contains("-i"));
        check("有密钥时 -i 后面就是那把密钥", keyed.contains("/home/u/.ssh/id_rsa_tbvm"));
        check("验证命令带 -p 和端口号", keyed.contains("-p") && keyed.contains("22"));
        check("验证命令用 BatchMode（免密才算通过）", keyed.contains("BatchMode=yes"));
        check("验证命令带登录标记", containsSubstring(keyed, KeyInstaller.LOGIN_MARKER));

        java.util.List<String> plain = KeyInstaller.verifyCommand(
                "ssh.exe", "h", 22, "u", null, 15);
        check("没密钥时不带 -i", !plain.contains("-i"));
        java.util.List<String> blank = KeyInstaller.verifyCommand(
                "ssh.exe", "h", 22, "u", "   ", 15);
        check("密钥是空白也不带 -i", !blank.contains("-i"));
    }

    // ------------------------------------------------------------------

    /**
     * 生成测试私钥。
     *
     * <p>必须走 {@code cmd.exe /s /c}，不能直接 {@code ProcessBuilder(ssh-keygen, ..., "-N", "")}：
     * Windows 下空参数会被丢掉，ssh-keygen 报 {@code Too many arguments}。生产代码
     * （{@code ConnectionPanel.keygenCommandLine} + {@code OpenSsh.runViaCmd}）也是
     * 走 {@code cmd.exe /s /c}，原因同上——这里保持一致。
     */
    private static boolean keygen(String keygenExe, File key) {
        try {
            String line = "\"" + keygenExe + "\" -t ed25519 -N \"\""
                    + " -C \"tbvm-live-test\" -f \"" + key.getAbsolutePath() + "\"";
            ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/s", "/c", "\"" + line + "\"");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            KeyInstaller.drain(p.getInputStream());
            p.waitFor();
            // 只看私钥在不在：.pub 是否写出不作为判据（见 publicKeyOf 的说明）。
            return new File(key.getAbsolutePath()).isFile();
        } catch (Exception e) {
            return false;
        }
    }

    /** 免密登录：不提供任何密码，只用刚装上的那把私钥。 */
    private static boolean keyLoginWorks(String ssh, String host, int port,
                                         String user, File key) {
        String out = run(ssh, "-o", "BatchMode=yes",
                "-o", "StrictHostKeyChecking=no",
                "-o", "ConnectTimeout=10",
                "-i", key.getAbsolutePath(),
                "-p", String.valueOf(port),
                user + "@" + host, "echo TBVM_KEYLOGIN_OK");
        return out.contains("TBVM_KEYLOGIN_OK");
    }

    /** 同上，但故意不传 {@code -i}——应当失败，因为密钥名不是 ssh 的默认名。 */
    private static boolean keyLoginWithoutDashI(String ssh, String host, int port,
                                               String user) {
        String out = run(ssh, "-o", "BatchMode=yes",
                "-o", "StrictHostKeyChecking=no",
                "-o", "ConnectTimeout=10",
                "-o", "IdentitiesOnly=yes",
                "-p", String.valueOf(port),
                user + "@" + host, "echo TBVM_KEYLOGIN_OK");
        return out.contains("TBVM_KEYLOGIN_OK");
    }

    /** 跑一条 ssh 命令，返回合并后的输出。 */
    private static String run(String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = KeyInstaller.drain(p.getInputStream());
            p.waitFor();
            return out;
        } catch (Exception e) {
            return "";
        }
    }

    /** 把测试公钥从远端 authorized_keys 里摘掉，恢复原状。 */
    private static void cleanup(String ssh, String host, int port, String user,
                                File key, String pub, File dir) {
        run(ssh, "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=no",
                "-i", key.getAbsolutePath(), "-p", String.valueOf(port),
                user + "@" + host,
                "grep -vxF '" + pub + "' ~/.ssh/authorized_keys > ~/.ssh/ak.tmp && "
                        + "mv ~/.ssh/ak.tmp ~/.ssh/authorized_keys");
        deleteTree(dir);
        System.out.println("  (已清理测试公钥与本地临时文件)");
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        f.delete();
    }

    private static String firstExisting(String... paths) {
        for (String p : paths) {
            if (new File(p).isFile()) {
                return p;
            }
        }
        return null;
    }

    private static String env(String name) {
        String v = System.getenv(name);
        return (v == null || v.trim().isEmpty()) ? null : v.trim();
    }

    /**
     * 参数列表里有没有哪一项包含这段文字。
     *
     * <p>不能直接 {@code list.contains(...)}：登录标记是搭在远端命令里的
     * （{@code echo TBVM_OK; uname -sr}），不是单独一个参数。第一版就用错了，
     * 结果这条检查永远红——错的不是产品，是断言。
     */
    static boolean containsSubstring(List<String> args, String needle) {
        for (String a : args) {
            if (a != null && a.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取输出里最后一行的纯数字。
     *
     * <p>ssh 会把 {@code Warning: ...} {@code Failed to add the host ...} 这类告警写进
     * 和命令输出同一路，所以不能拿整段输出做等值比较——那样测的是 ssh 的啰嗦程度，
     * 不是真实的计数。
     */
    static String lastNumber(String out) {
        if (out == null) {
            return "";
        }
        String[] lines = out.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String t = lines[i].trim();
            if (t.matches("\\d+")) {
                return t;
            }
        }
        return "";
    }

    private static void check(String label, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println("  " + (ok ? "PASS  " : "FAIL  ") + label);
    }

    private static void report() {
        System.out.println(failures == 0
                ? "PASS " + checks + " checks"
                : "FAIL: " + failures + " of " + checks + " checks failed");
        if (failures > 0) {
            System.exit(1);
        }
    }
}

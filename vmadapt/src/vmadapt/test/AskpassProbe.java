package vmadapt.test;

import java.io.File;

import vmadapt.core.SshAskpass;

/**
 * 验证 {@code SSH_ASKPASS_REQUIRE=force} 在**这台** Windows 上真的能被
 * JVM 起的 ssh.exe 接受。
 *
 * <h2>为什么必须先验</h2>
 *
 * <p>那个环境变量从 OpenSSH 8.4 起才支持，而 Windows 自带的版本随系统而异，老机器上
 * 还是 8.1。在没验证之前就照它写代码，可能写完才发现目标机器压根不认这个变量——
 * 那时"弹密码框"就成了一条注定失败的路径，而用户会比没有这个功能更困惑。
 *
 * <h2>怎么验（不需要真虚拟机）</h2>
 *
 * <p>不需要真的有一台虚拟机：只要让 ssh <b>走到"需要密码"那一步</b>，它就会去调
 * askpass。这里连一个能解析、但认证必然失败的地址（{@code 127.0.0.1} 的一个没人
 * 听的端口），并限定 {@code PreferredAuthentications=password}，于是 ssh 会尝试
 * 密码认证。辅助进程被调用时会留下一个标记文件——那就是"机制生效"的证据。
 */
public final class AskpassProbe {

    private static int checks;
    private static int failures;

    private AskpassProbe() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== AskpassProbe ===");

        String ssh = locate("ssh.exe");
        System.out.println("ssh.exe      : " + ssh);
        check("找得到 ssh.exe", ssh != null);
        if (ssh == null) {
            report();
            return;
        }

        String ver = versionOf(ssh);
        System.out.println("版本输出     : " + ver);
        int major = SshAskpass.majorOf(ver);
        int minor = SshAskpass.minorOf(ver);
        System.out.println("解析出的版本 : " + major + "." + minor);
        check("版本能解析出来", major > 0);
        check("版本 >= 8.4（SSH_ASKPASS_REQUIRE 的下限）",
                SshAskpass.supportsAskpassRequire(major, minor));

        File marker = new File(System.getProperty("java.io.tmpdir"),
                "tbvm-askpass-" + System.nanoTime() + ".txt");
        System.out.println("标记文件     : " + marker);
        System.out.println("java.exe     : " + SshAskpass.javaExe());

        // 需要一个"会要求密码"的 sshd 才能真正验到 askpass。
        //
        // 关键教训：本探针最初连的是 127.0.0.1 的一个没人听的端口，那会在 TCP 阶段就
        // Connection refused，ssh 根本走不到认证，于是 askpass 永远不会被调用——
        // 探针报 FAIL，看起来像"机制不生效"，实际是"探针没测到点子上"。
        // 所以这里必须连一个**真的在监听、且启用了密码认证**的 sshd。
        //
        // 通过环境变量指定；没指定就只验版本与辅助程序本身，并明说验到哪一步。
        String host = System.getenv("TBVM_ASKPASS_TEST_HOST");
        String port = System.getenv("TBVM_ASKPASS_TEST_PORT");
        String user = System.getenv("TBVM_ASKPASS_TEST_USER");

        if (host == null || host.trim().isEmpty()) {
            System.out.println();
            System.out.println("未设置 TBVM_ASKPASS_TEST_HOST，跳过端到端验证。");
            System.out.println("只验到：ssh 存在、版本支持、辅助程序可执行。");
            System.out.println("要完整验证，先起一个启用密码认证的 sshd，然后：");
            System.out.println("  $env:TBVM_ASKPASS_TEST_HOST='127.0.0.1'");
            System.out.println("  $env:TBVM_ASKPASS_TEST_PORT='2222'");
            System.out.println("  $env:TBVM_ASKPASS_TEST_USER=\"$env:USERNAME\"");
            check("辅助程序能输出密码（本地直跑）", helperPrintsPassword());
            report();
            return;
        }

        long t0 = System.currentTimeMillis();
        SshAskpass.selftest(ssh, marker, host.trim(),
                port == null || port.trim().isEmpty() ? "22" : port.trim(),
                user == null || user.trim().isEmpty() ? "nobody" : user.trim());
        long ms = System.currentTimeMillis() - t0;

        boolean called = marker.isFile();
        check("askpass 被 ssh 调用过（" + ms + "ms）", called);
        if (called) {
            System.out.println("留痕内容     : " + readAll(marker));
            marker.delete();
        } else {
            System.out.println("  → 说明这台机器上 SSH_ASKPASS_REQUIRE=force 没生效，");
            System.out.println("    密码通道必须降级为「显示公钥让用户自己去装」。");
        }

        report();
    }

    /** 直接跑一次辅助进程，确认它会把密码打到 stdout。 */
    private static boolean helperPrintsPassword() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    SshAskpass.javaExe(), "-cp", classpath(),
                    SshAskpass.class.getName(), "--tbvm-askpass");
            pb.environment().put("TBVM_ASKPASS_VALUE", "probe-value-42");
            // 不要把 stderr 并进来。JVM 会往 stderr 打 "Picked up JAVA_TOOL_OPTIONS..."
            // 之类的横幅，合流之后第一行就不是密码了——而 askpass 的 stdout 必须
            // 第一行就是密码。这里要是合流，探针会因为自己的读法而误报失败。
            pb.redirectErrorStream(false);
            Process p = pb.start();
            // 先把 stderr 抽干，否则管道满了子进程会卡住。
            drainAsync(p.getErrorStream());
            String out = readFirstLine(p.getInputStream());
            p.waitFor();
            System.out.println("辅助程序 stdout : " + out);
            return "probe-value-42".equals(out);
        } catch (Exception e) {
            System.out.println("辅助程序跑不起来: " + e);
            return false;
        }
    }

    private static String readFirstLine(java.io.InputStream in) {
        try {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) >= 0 && c != '\n') {
                if (c != '\r') {
                    sb.append((char) c);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static void drainAsync(final java.io.InputStream in) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] b = new byte[512];
                    while (in.read(b) >= 0) {
                        // 丢弃
                    }
                } catch (Exception ignored) {
                    // 读不到就算了
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static String classpath() {
        String cp = System.getProperty("java.class.path");
        return cp == null ? "" : cp;
    }

    private static String versionOf(String ssh) {
        try {
            ProcessBuilder pb = new ProcessBuilder(ssh, "-V");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            byte[] b = new byte[256];
            int n = p.getInputStream().read(b);
            p.waitFor();
            return n <= 0 ? "" : new String(b, 0, n, "UTF-8").trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static String locate(String exe) {
        String[] dirs = {
            "C:\\WINDOWS\\System32\\OpenSSH\\",
            "C:\\Program Files\\OpenSSH\\",
            "C:\\Program Files (x86)\\OpenSSH\\",
        };
        for (String d : dirs) {
            File f = new File(d, exe);
            if (f.isFile()) {
                return f.getAbsolutePath();
            }
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String part : path.split(File.pathSeparator)) {
                if (part == null || part.trim().isEmpty()) {
                    continue;
                }
                File f = new File(part.trim(), exe);
                if (f.isFile()) {
                    return f.getAbsolutePath();
                }
            }
        }
        return null;
    }

    private static String readAll(File f) {
        try {
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                int n = in.read(b);
                return new String(b, 0, Math.max(0, n), "UTF-8").trim();
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return "(读不到: " + e + ")";
        }
    }

    private static void report() {
        System.out.println(failures == 0
                ? "PASS " + checks + " checks"
                : "FAIL: " + failures + " of " + checks + " checks failed");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static void check(String label, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println("  " + (ok ? "PASS  " : "FAIL  ") + label);
    }
}

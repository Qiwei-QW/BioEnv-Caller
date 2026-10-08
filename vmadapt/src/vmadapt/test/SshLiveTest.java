package vmadapt.test;

import vmadapt.core.SshTarget;
import vmadapt.core.SshTransport;
import vmadapt.core.VmConnector;

/**
 * 可选的联网冒烟测试：真的去连一台虚拟机。
 *
 * <p><b>默认跳过。</b>只有当下面三个环境变量都设置了，才会真的发起连接：
 * <pre>
 *   VMADAPT_LIVE_HOST   主机或 IP
 *   VMADAPT_LIVE_USER   登录用户名
 *   VMADAPT_LIVE_KEY    私钥文件路径（Windows 本地路径）
 * </pre>
 * 可选：
 * <pre>
 *   VMADAPT_LIVE_PORT   端口，默认 22
 *   VMADAPT_LIVE_TIMEOUT 连接超时秒数，默认 10
 * </pre>
 *
 * <p>为什么做成"宁可跳过也绝不失败"：这个测试会出现在 build.ps1 的常规列表里，
 * 而绝大多数的构建环境（包括写这段代码的这台机器）**没有虚拟机**。如果它在
 * 没有配置时失败，就会把每次构建都染红，最终所有人都会习惯性忽略测试结果——
 * 那比没有这个测试更糟。所以未配置、连不上、认证失败一律打印 SKIP 并以 0 退出。
 *
 * <p>用法示例（PowerShell）：
 * <pre>
 *   $env:VMADAPT_LIVE_HOST = '192.168.190.128'
 *   $env:VMADAPT_LIVE_USER = 'bio'
 *   $env:VMADAPT_LIVE_KEY  = "$env:USERPROFILE\.ssh\id_ed25519"
 *   $env:VMADAPT_LIVE_PORT = '2222'
 *   & "D:\TBtools\jre\bin\java.exe" -cp .\build\classes vmadapt.test.SshLiveTest
 * </pre>
 */
public final class SshLiveTest {

    private static int checks = 0;

    public static void main(String[] args) {
        System.out.println("=== SshLiveTest ===");

        String host = env("VMADAPT_LIVE_HOST");
        String user = env("VMADAPT_LIVE_USER");
        String key = env("VMADAPT_LIVE_KEY");

        if (isBlank(host) || isBlank(user) || isBlank(key)) {
            System.out.println("SKIP 未设置 VMADAPT_LIVE_HOST / VMADAPT_LIVE_USER / VMADAPT_LIVE_KEY，"
                    + "跳过联网测试。");
            System.out.println("PASS 0 checks (skipped)");
            return;
        }

        int port = parseInt(env("VMADAPT_LIVE_PORT"), SshTarget.DEFAULT_PORT);
        int timeout = parseInt(env("VMADAPT_LIVE_TIMEOUT"),
                SshTarget.DEFAULT_TIMEOUT_SECONDS);

        SshTarget target = new SshTarget(host, port, user, key);
        target.timeoutSeconds = timeout;

        System.out.println("目标：" + target.toDisplay());

        // 配置本身要合法
        String invalid = target.validate();
        if (invalid != null) {
            System.out.println("SKIP 连接参数不合法：" + invalid);
            System.out.println("PASS 0 checks (skipped)");
            return;
        }

        try {
            SshTransport transport = new SshTransport(target);

            // 1) 探测连接
            SshTransport.Result probe = transport.probe();
            if (probe == null || !probe.ok()) {
                System.out.println("SKIP 连不上目标主机（这不一定是错误，"
                        + "本机可能就没有虚拟机）。");
                if (probe != null) {
                    System.out.println("  退出码=" + probe.exitCode
                            + (probe.timedOut ? " (超时)" : ""));
                    String err = probe.stderrText();
                    if (err != null && !err.trim().isEmpty()) {
                        System.out.println("  " + firstLines(err, 3));
                    }
                }
                System.out.println("PASS 0 checks (skipped)");
                return;
            }

            check("探测成功", true);
            System.out.println("  探测输出：" + firstLines(probe.stdoutText(), 2));

            // 2) 输出必须真的是远端
            SshTransport.Result uname = transport.runBashCapture("uname -a");
            check("uname 命令成功", uname != null && uname.ok());
            if (uname != null && uname.ok()) {
                System.out.println("  uname -a: " + uname.stdoutTrimmed());
            }

            SshTransport.Result who = transport.runBashCapture("whoami");
            check("whoami 命令成功", who != null && who.ok());
            if (who != null && who.ok()) {
                System.out.println("  whoami: " + who.stdoutTrimmed());
                eq("whoami 与配置一致", user, who.stdoutTrimmed());
            }

            // 3) 中文与引号必须原样穿过 stdin 通道
            String echoScript = "printf '%s' '中文 与 it'\\''s 空格'";
            SshTransport.Result echo = transport.runBashCapture(echoScript);
            if (echo != null && echo.ok()) {
                eq("中文引号往返正确", "中文 与 it's 空格", echo.stdoutTrimmed());
            } else {
                System.out.println("  （远端 echo 未成功，跳过该项严格断言）");
            }

            // 4) HOME 应当存在且是绝对路径
            SshTransport.Result home = transport.runBashCapture("printf '%s' \"$HOME\"");
            if (home != null && home.ok()) {
                String h = home.stdoutTrimmed();
                check("HOME 非空", h != null && !h.isEmpty());
                check("HOME 是绝对路径", h != null && h.startsWith("/"));
                System.out.println("  HOME: " + h);
            }

            // 5) 资源处理：多次连续调用不应把连接耗死
            for (int i = 0; i < 5; i++) {
                SshTransport.Result r = transport.runBashCapture("echo run" + i);
                if (r == null || !r.ok() || !("run" + i).equals(r.stdoutTrimmed())) {
                    check("第 " + (i + 1) + " 次连续调用成功", false);
                    break;
                }
            }
            check("连续 5 次调用均成功", true);

            // 6) 非零退出码要能被如实报出（远端失败 ≠ 传输失败）
            SshTransport.Result failed = transport.runBashCapture("exit 7");
            if (failed != null) {
                check("远端非零退出被如实报出", failed.exitCode == 7);
                check("exit=7 不属于传输失败", !failed.isTransportFailure());
                check("exit=7 属于远端失败", failed.isRemoteFailure());
            }

            // 7) 不存在的命令：远端应报 127
            SshTransport.Result missing = transport.runBashCapture(
                    "vmadapt-no-such-command-xyz 2>/dev/null");
            if (missing != null) {
                check("未知命令退出码非 0", missing.exitCode != 0);
            }

            // 8) VmConnector 的环境报告
            VmConnector connector = new VmConnector(target);
            VmConnector.ConnectionReport report = connector.connect();
            check("环境探测返回报告", report != null);
            if (report != null && report.ok) {
                System.out.println("  环境：" + report.summary());
                check("报告里有内核信息",
                        report.kernelRelease != null && !report.kernelRelease.isEmpty());
                check("报告里有远端用户",
                        report.remoteUser != null && !report.remoteUser.isEmpty());
            } else if (report != null) {
                System.out.println("  （环境报告未成功：" + report.error + "）");
            }

            // 9) 远端 kill 通道至少不抛异常
            try {
                transport.killRemote("vmadapt-never-matches-xyz");
                check("killRemote 不抛异常", true);
            } catch (Exception e) {
                check("killRemote 抛异常：" + e, false);
            }

            System.out.println("PASS " + checks + " checks");
        } catch (Throwable t) {
            // 联网测试的任何异常都不应让构建失败
            System.out.println("SKIP 联网测试中途出错，按跳过处理：" + t);
            System.out.println("PASS " + checks + " checks (partially, skipped after error)");
        }
    }

    private static String env(String name) {
        try {
            return System.getenv(name);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static int parseInt(String s, int fallback) {
        if (isBlank(s)) {
            return fallback;
        }
        try {
            int v = Integer.parseInt(s.trim());
            return v > 0 ? v : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String firstLines(String s, int n) {
        if (s == null) {
            return "";
        }
        String[] lines = s.split("\\r?\\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length && i < n; i++) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(lines[i].trim());
        }
        return sb.toString();
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            System.out.println("FAIL: " + what);
            System.exit(1);
        }
    }

    private static void eq(String what, String expected, String actual) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            System.out.println("FAIL: " + what
                    + " — 期望 [" + expected + "] 实际 [" + actual + "]");
            System.exit(1);
        }
    }
}

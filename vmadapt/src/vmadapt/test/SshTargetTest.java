package vmadapt.test;

import vmadapt.core.SshTarget;

import java.io.File;

/**
 * SshTarget 的构造、校验与显示测试。
 *
 * <p>重点在两处容易出错的地方：
 * <ol>
 *   <li><b>端口边界</b>：构造器把非正数一律回退成 22，但 {@code validate()} 又要
 *       拦下 65536 这种越界值。两处规则不同，必须分别断言，否则会出现
 *       "构造器悄悄改成了 22、校验器却永远通不过"的矛盾。</li>
 *   <li><b>私钥路径归一化</b>：用户会粘贴带引号的路径、WSL 风格的 {@code /mnt/c/...}、
 *       以及 {@code ~/...}。归一化必须真的把它们变成 ssh.exe 认得的 Windows 路径。</li>
 * </ol>
 *
 * <p>不联网。
 */
public final class SshTargetTest {

    private static int checks = 0;

    public static void main(String[] args) {
        System.out.println("=== SshTargetTest ===");

        // ---------------------------------------------------------------
        // 1. 基本构造与字段
        // ---------------------------------------------------------------
        SshTarget t = new SshTarget("192.168.1.100", 22, "ubuntu", null);
        eq("host", "192.168.1.100", t.host);
        eq("port", "22", String.valueOf(t.port));
        eq("user", "ubuntu", t.user);
        eq("keyPath 为 null", null, t.keyPath);
        eq("默认超时", String.valueOf(SshTarget.DEFAULT_TIMEOUT_SECONDS),
                String.valueOf(t.timeoutSeconds));
        check("默认常量端口是 22", SshTarget.DEFAULT_PORT == 22);

        // 空构造
        SshTarget empty = new SshTarget();
        eq("空构造 host", "", empty.host);
        eq("空构造 port", "22", String.valueOf(empty.port));
        eq("空构造 user", "", empty.user);
        eq("空构造 keyPath", null, empty.keyPath);

        // 首尾空格被 trim
        SshTarget padded = new SshTarget("  10.0.0.5  ", 2222, "  bio  ", null);
        eq("host 被 trim", "10.0.0.5", padded.host);
        eq("user 被 trim", "bio", padded.user);

        // null 字段不炸
        SshTarget nulls = new SshTarget(null, 22, null, null);
        eq("null host 变空串", "", nulls.host);
        eq("null user 变空串", "", nulls.user);

        // ---------------------------------------------------------------
        // 2. 端口边界
        // ---------------------------------------------------------------
        eq("端口 0 回退到 22", "22", String.valueOf(new SshTarget("h", 0, "u", null).port));
        eq("端口 -1 回退到 22", "22", String.valueOf(new SshTarget("h", -1, "u", null).port));
        eq("端口 -9999 回退到 22", "22", String.valueOf(new SshTarget("h", -9999, "u", null).port));
        eq("端口 1 保留", "1", String.valueOf(new SshTarget("h", 1, "u", null).port));
        eq("端口 22 保留", "22", String.valueOf(new SshTarget("h", 22, "u", null).port));
        eq("端口 2222 保留", "2222", String.valueOf(new SshTarget("h", 2222, "u", null).port));
        eq("端口 65535 保留", "65535", String.valueOf(new SshTarget("h", 65535, "u", null).port));
        eq("端口 65536 保留（由 validate 拦截）", "65536",
                String.valueOf(new SshTarget("h", 65536, "u", null).port));

        // 65536 由 validate 拦下
        SshTarget tooHigh = new SshTarget("h", 65536, "u", null);
        check("65536 校验失败", tooHigh.validate() != null);
        check("65536 错误信息提到端口", tooHigh.validate().contains("65535"));

        // ---------------------------------------------------------------
        // 3. validate()：合法与各类非法
        // ---------------------------------------------------------------
        check("合法目标校验通过", new SshTarget("192.168.1.100", 22, "ubuntu", null)
                .validate() == null);
        check("合法目标（带端口）校验通过",
                new SshTarget("127.0.0.1", 2222, "bio", null).validate() == null);

        SshTarget noHost = new SshTarget("", 22, "ubuntu", null);
        check("空 host 校验失败", noHost.validate() != null);
        check("空 host 提示给出 host name", noHost.validate().contains("host name"));

        SshTarget noUser = new SshTarget("10.0.0.1", 22, "", null);
        check("空 user 校验失败", noUser.validate() != null);
        check("空 user 提示给出 user name", noUser.validate().contains("user name"));

        // 主机里带空格
        SshTarget spacedHost = new SshTarget("my host", 22, "u", null);
        check("host 含空格校验失败", spacedHost.validate() != null);

        // 误填 ssh:// 前缀
        SshTarget scheme = new SshTarget("ssh://10.0.0.1", 22, "u", null);
        check("ssh:// 前缀校验失败", scheme.validate() != null);
        check("ssh:// 提示说明前缀", scheme.validate().contains("ssh://"));

        // 误把 user@host 整串填进 host
        SshTarget atHost = new SshTarget("bio@10.0.0.1", 22, "bio", null);
        check("user@host 误填校验失败", atHost.validate() != null);
        check("user@host 提示拆分", atHost.validate().contains("user name"));

        // ---------------------------------------------------------------
        // 4. 私钥存在性校验
        // ---------------------------------------------------------------
        // 用一个真实存在的文件（本测试类自身的源文件路径不可靠，改用 java.io.tmpdir）
        String existing = null;
        try {
            File tmp = File.createTempFile("vmadapt-key-", ".txt");
            tmp.deleteOnExit();
            existing = tmp.getAbsolutePath();
        } catch (Exception e) {
            check("无法创建临时文件：" + e, false);
        }
        SshTarget withRealKey = new SshTarget("10.0.0.1", 22, "u", existing);
        check("存在的私钥校验通过", withRealKey.validate() == null);

        String missing = existing + ".definitely-not-here";
        SshTarget withMissingKey = new SshTarget("10.0.0.1", 22, "u", missing);
        check("不存在的私钥校验失败", withMissingKey.validate() != null);
        check("缺私钥提示给出 Private key", withMissingKey.validate().contains("Private key"));

        // keyPath 为 null 时不报错（允许走 ssh 默认密钥）
        check("keyPath 为 null 校验通过",
                new SshTarget("10.0.0.1", 22, "u", null).validate() == null);

        // ---------------------------------------------------------------
        // 5. 超时边界
        // ---------------------------------------------------------------
        SshTarget badTimeout = new SshTarget("10.0.0.1", 22, "u", null);
        badTimeout.timeoutSeconds = 0;
        check("超时 0 校验失败", badTimeout.validate() != null);
        badTimeout.timeoutSeconds = -5;
        check("超时负数校验失败", badTimeout.validate() != null);
        badTimeout.timeoutSeconds = 601;
        check("超时 601 校验失败", badTimeout.validate() != null);
        badTimeout.timeoutSeconds = 600;
        check("超时 600 校验通过", badTimeout.validate() == null);
        badTimeout.timeoutSeconds = 1;
        check("超时 1 校验通过", badTimeout.validate() == null);

        // ---------------------------------------------------------------
        // 6. usesKey / hasUsableKey
        // ---------------------------------------------------------------
        check("无密钥 usesKey 为假", !new SshTarget("h", 22, "u", null).usesKey());
        check("空密钥 usesKey 为假", !new SshTarget("h", 22, "u", "   ").usesKey());
        check("有密钥 usesKey 为真", new SshTarget("h", 22, "u", "x").usesKey());
        check("存在密钥 hasUsableKey 为真", withRealKey.hasUsableKey());
        check("不存在密钥 hasUsableKey 为假", !withMissingKey.hasUsableKey());
        check("无密钥 hasUsableKey 为假", !new SshTarget("h", 22, "u", null).hasUsableKey());

        // ---------------------------------------------------------------
        // 7. normalizeKeyPath：粘贴路径的机械修正
        // ---------------------------------------------------------------
        eq("null 保持 null", null, SshTarget.normalizeKeyPath(null));
        eq("空串变 null", null, SshTarget.normalizeKeyPath(""));
        eq("纯空格变 null", null, SshTarget.normalizeKeyPath("   "));
        eq("普通 Windows 路径原样", "C:\\Users\\me\\.ssh\\id_rsa",
                SshTarget.normalizeKeyPath("C:\\Users\\me\\.ssh\\id_rsa"));
        eq("去掉双引号", "C:\\Users\\me\\id_rsa",
                SshTarget.normalizeKeyPath("\"C:\\Users\\me\\id_rsa\""));
        eq("去掉单引号", "C:\\Users\\me\\id_rsa",
                SshTarget.normalizeKeyPath("'C:\\Users\\me\\id_rsa'"));
        // WSL 风格挂载点 → Windows 盘符
        eq("/mnt/c 转盘符", "C:\\Users\\me\\id_rsa",
                SshTarget.normalizeKeyPath("/mnt/c/Users/me/id_rsa"));
        eq("/mnt/d 转盘符", "D:\\keys\\k.pem",
                SshTarget.normalizeKeyPath("/mnt/d/keys/k.pem"));
        // 首尾空格被去掉
        eq("首尾空格被 trim", "C:\\k.pem", SshTarget.normalizeKeyPath("  C:\\k.pem  "));

        // ~ 展开到家目录下的 .ssh
        String home = System.getProperty("user.home");
        String expanded = SshTarget.normalizeKeyPath("~/.ssh/id_ed25519");
        check("~ 被展开掉", expanded != null && expanded.indexOf('~') < 0);
        check("~ 展开后用反斜杠", expanded != null && expanded.indexOf('/') < 0);
        if (home != null && !home.trim().isEmpty()) {
            check("~ 展开到 user.home 下", expanded.startsWith(home));
            check("~ 展开带 .ssh", expanded.contains(".ssh"));
            check("~ 展开带文件名", expanded.endsWith("id_ed25519"));
        }

        // ---------------------------------------------------------------
        // 8. toDisplay
        // ---------------------------------------------------------------
        eq("无别名显示", "ubuntu@192.168.1.100:22",
                new SshTarget("192.168.1.100", 22, "ubuntu", null).toDisplay());
        eq("端口出现在显示里", "bio@127.0.0.1:2222",
                new SshTarget("127.0.0.1", 2222, "bio", null).toDisplay());

        SshTarget labeled = new SshTarget("192.168.1.100", 22, "ubuntu", null);
        labeled.label = "测序分析机";
        eq("带别名显示", "测序分析机 (ubuntu@192.168.1.100:22)", labeled.toDisplay());

        SshTarget noUserDisplay = new SshTarget("10.0.0.1", 22, "", null);
        eq("无用户名显示不带 @", "10.0.0.1:22", noUserDisplay.toDisplay());

        SshTarget noHostDisplay = new SshTarget("", 22, "u", null);
        check("无主机有占位提示", noHostDisplay.toDisplay().contains("(no host)"));

        // toString 与 toDisplay 一致
        eq("toString 委托 toDisplay", labeled.toDisplay(), labeled.toString());

        System.out.println("PASS " + checks + " checks");
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

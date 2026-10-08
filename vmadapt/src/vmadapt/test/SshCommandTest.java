package vmadapt.test;

import vmadapt.core.SshCommand;
import vmadapt.core.SshTarget;

import java.util.ArrayList;
import java.util.List;

/**
 * ssh.exe / scp.exe 参数组装测试。
 *
 * <p>这组测试守的是两条**安全与正确性的底线**：
 *
 * <ol>
 *   <li><b>脚本绝不能出现在 argv 里。</b>本项目的策略是「脚本一律走 stdin 交给远端
 *       {@code bash -s}」，命令行里只放主机、端口、密钥这些短参数。一旦有人图省事
 *       把脚本拼进命令行，Windows 的引号规则会立刻改写 {@code "}、{@code $}、中文
 *       路径，命令要么报错要么被注入。所以这里断言 argv 中**不含**脚本内容。</li>
 *   <li><b>三个 {@code -o} 选项一个都不能少。</b>少一个的后果是"卡死"而不是"报错"：
 *       没有 {@code BatchMode=yes}，ssh 在无 TTY 时会挂起等密码；没有
 *       {@code ConnectTimeout}，网络不通时要等几十秒。</li>
 * </ol>
 *
 * <p>另外断言 {@code ssh} 用小写 {@code -p}、{@code scp} 用大写 {@code -P}——
 * 写反了 scp 会把端口号当源文件，报出完全误导人的"文件不存在"。
 *
 * <p>不联网、不启动进程，只看组装出来的字符串数组。
 */
public final class SshCommandTest {

    private static int checks = 0;

    public static void main(String[] args) {
        System.out.println("=== SshCommandTest ===");

        SshTarget t = new SshTarget("192.168.1.100", 2222, "bio", null);

        // ---------------------------------------------------------------
        // 1. ssh 基本组装
        // ---------------------------------------------------------------
        String[] ssh = SshCommand.build(t);
        check("argv 非空", ssh != null && ssh.length > 0);
        check("包含 user@host", contains(ssh, "bio@192.168.1.100"));
        check("使用小写 -p", contains(ssh, "-p"));
        check("不含大写 -P", !contains(ssh, "-P"));
        check("端口值紧随其后", indexOf(ssh, "-p") >= 0
                && indexOf(ssh, "-p") + 1 < ssh.length
                && "2222".equals(ssh[indexOf(ssh, "-p") + 1]));
        check("末尾是 bash", contains(ssh, "bash"));
        check("末尾是 -s", contains(ssh, "-s"));
        check("最后两个参数是 bash -s",
                ssh.length >= 2
                        && "bash".equals(ssh[ssh.length - 2])
                        && "-s".equals(ssh[ssh.length - 1]));

        // 可执行文件名应是 ssh.exe（或退回裸名 ssh.exe）
        String exe0 = ssh[0].toLowerCase(java.util.Locale.ROOT);
        check("可执行文件是 ssh.exe", exe0.endsWith("ssh.exe"));

        // ---------------------------------------------------------------
        // 2. 三个必需的 -o 选项
        // ---------------------------------------------------------------
        check("含 BatchMode=yes", contains(ssh, "BatchMode=yes"));
        check("含 StrictHostKeyChecking=accept-new",
                contains(ssh, "StrictHostKeyChecking=accept-new"));
        check("含 ConnectTimeout", hasPrefix(ssh, "ConnectTimeout="));
        check("含 -o 选项开关", contains(ssh, "-o"));
        check("ssh 带 -T 禁用伪终端", contains(ssh, "-T"));

        // BatchMode 必须在，且不能是 no
        for (int i = 0; i < ssh.length; i++) {
            if (ssh[i].startsWith("BatchMode=")) {
                eq("BatchMode 是 yes", "BatchMode=yes", ssh[i]);
            }
            check("StrictHostKeyChecking 不是 no",
                    !"StrictHostKeyChecking=no".equals(ssh[i]));
        }

        // ConnectTimeout 的值必须是正整数
        // valueOfPrefix 返回的是整个元素（"ConnectTimeout=15"），
        // 断言前必须先把前缀切掉，否则 parseInt 一定失败。
        String timeoutVal = valueOfPrefix(ssh, "ConnectTimeout=");
        check("ConnectTimeout 有值", timeoutVal != null);
        check("ConnectTimeout 是正整数",
                isPositiveInt(timeoutVal.substring("ConnectTimeout=".length())));

        // ---------------------------------------------------------------
        // 3. 密钥参数
        // ---------------------------------------------------------------
        SshTarget withKey = new SshTarget("10.0.0.1", 22, "u",
                "C:\\Users\\me\\.ssh\\id_ed25519");
        // 该文件不存在，但 SshCommand.build 不做存在性校验（那是 validate 的事），
        // 所以 -i 仍然应当出现。
        String[] keyed = SshCommand.build(withKey);
        check("有密钥时含 -i", contains(keyed, "-i"));
        int ki = indexOf(keyed, "-i");
        check("-i 后面跟着密钥路径", ki >= 0 && ki + 1 < keyed.length
                && "C:\\Users\\me\\.ssh\\id_ed25519".equals(keyed[ki + 1]));

        String[] noKey = SshCommand.build(t);
        check("无密钥时不含 -i", !contains(noKey, "-i"));

        // ---------------------------------------------------------------
        // 4. scp：大写 -P
        // ---------------------------------------------------------------
        String[] scp = SshCommand.build(t, true);
        check("scp 使用大写 -P", contains(scp, "-P"));
        check("scp 不含小写 -p", !contains(scp, "-p"));
        check("scp 端口值正确", indexOf(scp, "-P") >= 0
                && indexOf(scp, "-P") + 1 < scp.length
                && "2222".equals(scp[indexOf(scp, "-P") + 1]));
        String scpExe = scp[0].toLowerCase(java.util.Locale.ROOT);
        check("可执行文件是 scp.exe", scpExe.endsWith("scp.exe"));
        // scp 不需要远端命令，因此不应有 bash -s
        check("scp 不含 bash", !contains(scp, "bash"));
        check("scp 不含 -s", !contains(scp, "-s"));
        check("scp 仍含 BatchMode", contains(scp, "BatchMode=yes"));
        check("scp 仍含 accept-new", contains(scp, "StrictHostKeyChecking=accept-new"));
        check("scp 仍含 ConnectTimeout", hasPrefix(scp, "ConnectTimeout="));
        check("scp 含 user@host", contains(scp, "bio@192.168.1.100"));

        // ---------------------------------------------------------------
        // 5. 关键安全断言：argv 里绝不能有脚本内容
        // ---------------------------------------------------------------
        // 构造一个"如果被拼进命令行就一定会露馅"的脚本：
        // 含空格、单引号、双引号、$、分号、中文、反斜杠。
        String script = "echo \"hello world\"\n"
                + "X='it'\\''s'\n"
                + "echo $HOME; rm -f /tmp/x\n"
                + "echo 中文路径\n"
                + "echo 'C:\\Users\\me'\n";

        // build 之后 argv 是固定的那几个短参数，脚本根本不该有机会参与。
        String joined = join(SshCommand.build(t));
        check("argv 不含脚本的 echo", joined.indexOf("echo") < 0);
        check("argv 不含脚本的 rm -f", joined.indexOf("rm -f") < 0);
        check("argv 不含脚本的中文", joined.indexOf("中文路径") < 0);
        check("argv 不含脚本的 hello world", joined.indexOf("hello world") < 0);
        check("argv 不含换行", joined.indexOf('\n') < 0);
        check("argv 不含分号", joined.indexOf(';') < 0);
        check("argv 不含 $ 展开", joined.indexOf("$HOME") < 0);
        check("argv 不含 C:\\Users", joined.indexOf("C:\\Users") < 0);

        // buildArgs 同样只接受"已切分好的参数"，脚本不应作为一个整串进去。
        List<String> extra = new ArrayList<String>();
        extra.add("-l");
        extra.add("-c");
        String[] withArgs = SshCommand.buildArgs(t, extra);
        check("buildArgs 含追加参数", contains(withArgs, "-l") && contains(withArgs, "-c"));
        check("buildArgs 使用小写 -p", contains(withArgs, "-p"));
        check("buildArgs 不含大写 -P", !contains(withArgs, "-P"));
        check("buildArgs 含 accept-new", contains(withArgs, "StrictHostKeyChecking=accept-new"));
        check("buildArgs 含 BatchMode", contains(withArgs, "BatchMode=yes"));
        check("buildArgs 含 user@host", contains(withArgs, "bio@192.168.1.100"));

        // null 追加参数不炸
        String[] nullArgs = SshCommand.buildArgs(t, null);
        check("buildArgs(null) 仍产出主机", contains(nullArgs, "bio@192.168.1.100"));

        // ---------------------------------------------------------------
        // 6. 默认值与回退
        // ---------------------------------------------------------------
        SshTarget noPort = new SshTarget("h", 0, "u", null);
        check("端口回退为 22",
                indexOf(SshCommand.build(noPort), "-p") >= 0
                        && "22".equals(SshCommand.build(noPort)[
                                indexOf(SshCommand.build(noPort), "-p") + 1]));

        SshTarget noTimeout = new SshTarget("h", 22, "u", null);
        noTimeout.timeoutSeconds = 0;
        eq("超时回退为默认值",
                "ConnectTimeout=" + SshTarget.DEFAULT_TIMEOUT_SECONDS,
                valueOfPrefix(SshCommand.build(noTimeout), "ConnectTimeout="));

        SshTarget customTimeout = new SshTarget("h", 22, "u", null);
        customTimeout.timeoutSeconds = 42;
        eq("自定义超时被采用", "ConnectTimeout=42",
                valueOfPrefix(SshCommand.build(customTimeout), "ConnectTimeout="));

        // 无用户名时只给 host
        String[] noUser = SshCommand.build(new SshTarget("10.0.0.1", 22, "", null));
        check("无用户名时不含 @", !contains(noUser, "@"));
        check("无用户名时含主机", contains(noUser, "10.0.0.1"));

        // null target 必须明确报错而不是抛 NPE 之外的怪东西
        boolean threw = false;
        try {
            SshCommand.build(null);
        } catch (IllegalArgumentException e) {
            threw = true;
        } catch (Exception e) {
            threw = false;
        }
        check("null target 抛 IllegalArgumentException", threw);

        // ---------------------------------------------------------------
        // 7. 可选：exe 路径解析
        // ---------------------------------------------------------------
        String sshExePath = SshCommand.sshExe();
        String scpExePath = SshCommand.scpExe();
        check("sshExe 非空", sshExePath != null && !sshExePath.isEmpty());
        check("scpExe 非空", scpExePath != null && !scpExePath.isEmpty());
        check("sshExe 以 ssh.exe 结尾",
                sshExePath.toLowerCase(java.util.Locale.ROOT).endsWith("ssh.exe"));
        check("scpExe 以 scp.exe 结尾",
                scpExePath.toLowerCase(java.util.Locale.ROOT).endsWith("scp.exe"));

        // ---------------------------------------------------------------
        // 8. toDisplayString 只用于显示，不参与执行
        // ---------------------------------------------------------------
        String display = SshCommand.toDisplayString(SshCommand.build(t));
        check("显示串含主机", display.contains("bio@192.168.1.100"));
        check("显示串含 -p", display.contains("-p"));
        eq("null 显示为空串", "", SshCommand.toDisplayString(null));

        System.out.println("PASS " + checks + " checks");
    }

    // -----------------------------------------------------------------
    // 小工具
    // -----------------------------------------------------------------

    private static boolean contains(String[] arr, String want) {
        return indexOf(arr, want) >= 0;
    }

    private static int indexOf(String[] arr, String want) {
        if (arr == null || want == null) {
            return -1;
        }
        for (int i = 0; i < arr.length; i++) {
            if (want.equals(arr[i])) {
                return i;
            }
        }
        return -1;
    }

    /** argv 中是否存在以某前缀开头的元素（例如 ConnectTimeout=15）。 */
    private static boolean hasPrefix(String[] arr, String prefix) {
        return valueOfPrefix(arr, prefix) != null;
    }

    /** 取以某前缀开头的元素本身；没有返回 null。 */
    private static String valueOfPrefix(String[] arr, String prefix) {
        if (arr == null || prefix == null) {
            return null;
        }
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] != null && arr[i].startsWith(prefix)) {
                return arr[i];
            }
        }
        return null;
    }

    private static String join(String[] arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(arr[i]);
        }
        return sb.toString();
    }

    private static boolean isPositiveInt(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return Integer.parseInt(s) > 0;
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

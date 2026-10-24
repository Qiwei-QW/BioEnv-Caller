package vmadapt.test;

import vmadapt.core.KeyInstaller;
import vmadapt.core.Shell;

/**
 * 远端安装公钥脚本的引用测试。
 *
 * <p>回归的是一个「密码明明对了，却只看到一句笼统失败」的缺陷：以前公钥正文被直接
 * 塞进单引号里（{@code grep -qxF '<公钥>' ...}）。{@code ssh-keygen -C "Alice's laptop"}
 * 生成的注释里带单引号，整条远端命令因此变成未闭合的引号，bash 报语法错误；
 * 界面上看不出是引号的问题，用户只会以为密码错或安装失败。
 *
 * <p>现在两处公钥都走 {@link Shell#quote}。测试不启动 ssh、不联网，只对
 * {@link KeyInstaller#remoteScript} 生成的脚本做结构断言与「按 bash 规则解引用后
 * 原样还原」的语义断言；顺带钉住等待上限存在——半开的 sshd 不能让向导永远停在
 * 「正在安装」。
 */
public final class KeyInstallScriptTest {

    private static int checks;
    private static int failures;

    private KeyInstallScriptTest() {
    }

    public static void main(String[] args) {
        System.out.println("=== KeyInstallScriptTest ===");

        // 真实会出现的公钥：注释里带单引号（ssh-keygen -C "Alice's laptop"），
        // 还有 $ 这种会被 shell 展开的字符。
        String key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIMockKey Alice's laptop $HOME";
        String script = KeyInstaller.remoteScript(key);
        String quoted = Shell.quote(key);

        check("脚本非空", script != null && script.length() > 0);
        check("grep 用的是引用后的公钥",
                script.contains("grep -qxF " + quoted + " ~/.ssh/authorized_keys"));
        check("echo 用的是引用后的公钥",
                script.contains("echo " + quoted + " >> ~/.ssh/authorized_keys"));
        check("两处公钥都由 Shell.quote 产生（出现两次）",
                count(script, quoted) == 2);
        check("旧的裸单引号写法不再出现", !script.contains("'" + key + "'"));
        check("单引号整体成对（没有悬空引号）", countChar(script, '\'') % 2 == 0);
        check("仍带安装成功标记", script.contains(KeyInstaller.MARKER));
        check("仍用 grep -x 整行精确匹配", script.contains("grep -qxF "));
        check("仍保留建目录与修权限", script.contains("mkdir -p ~/.ssh")
                && script.contains("chmod 700 ~/.ssh")
                && script.contains("chmod 600 ~/.ssh/authorized_keys"));

        // 语义断言：按 bash 规则解引用整段脚本后，公钥必须原样出现。
        // 这比逐字节数引号更硬——转义漏一处，这里还原出来的就不是原串。
        String unquoted = bashUnquote(script);
        check("整段脚本的引号都闭合", unquoted != null);
        check("解引用后公钥原样还原（$ 没被展开、引号没丢）",
                unquoted != null && count(unquoted, key) == 2);

        // 反斜杠与双引号混在公钥里也不能把脚本弄破。
        String weird = "ssh-rsa AAAA\\back\\slash O'Brien's \"quoted\"";
        String weirdScript = KeyInstaller.remoteScript(weird);
        String weirdUnquoted = bashUnquote(weirdScript);
        check("带反斜杠/双引号的公钥也能闭合", weirdUnquoted != null);
        check("带反斜杠/双引号的公钥原样还原",
                weirdUnquoted != null && count(weirdUnquoted, weird) == 2);

        // 等待上限：ssh 卡住时向导不能永远停在「正在安装」。
        check("等待上限比连接超时大", KeyInstaller.waitLimitSeconds(10) > 10);
        check("等待上限 = 连接超时 + 余量",
                KeyInstaller.waitLimitSeconds(10) == 10 + KeyInstaller.WAIT_MARGIN_S);
        check("连接超时为 0 时仍有正的等待上限", KeyInstaller.waitLimitSeconds(0) > 0);
        check("连接超时为负也不会退化成无上限等待",
                KeyInstaller.waitLimitSeconds(-5) > 0);

        System.out.println(failures == 0
                ? "PASS " + checks + " checks"
                : "FAIL: " + failures + " of " + checks + " checks failed");
        if (failures > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /**
     * 模拟 bash 对单引号引用的解析；未闭合时返回 null（正是要抓的失败形态）。
     *
     * <p>规则：单引号之间一切都是字面量；{@code '\''} 是「闭合 + 转义单引号 +
     * 重开」，最终产生一个字面单引号。这是 bash 的语义本身，不依赖具体实现。
     */
    private static String bashUnquote(String quoted) {
        StringBuilder out = new StringBuilder();
        boolean inQuote = false;
        int i = 0;
        int n = quoted.length();
        while (i < n) {
            char c = quoted.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
                i++;
                continue;
            }
            if (c == '\\' && !inQuote && i + 1 < n) {
                out.append(quoted.charAt(i + 1));
                i += 2;
                continue;
            }
            out.append(c);
            i++;
        }
        return inQuote ? null : out.toString();
    }

    private static int count(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isEmpty()) {
            return 0;
        }
        int n = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            n++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return n;
    }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; s != null && i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    private static void check(String label, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("  FAIL: " + label);
        }
    }
}

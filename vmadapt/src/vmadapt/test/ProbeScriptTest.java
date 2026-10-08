package vmadapt.test;

import vmadapt.core.SshTransport;

/**
 * 探测脚本的形状测试。
 *
 * <p>为什么专门为「一条脚本长什么样」写测试：这条脚本有一个不显眼的硬要求——
 * <b>每个字段必须独占一行</b>。它一旦被破坏，表现是某个字段静默消失：脚本不报错、
 * 退出码是 0、其它字段全都正常，只有那一个 KEY 不见了。
 *
 * <p>真实的故障：{@code printf 'HOME='; printf '%s' "$HOME"} 不带换行，于是下一行的
 * {@code BASH=/usr/bin/bash} 被拼到同一行，解析器按行读就再也看不到 BASH，
 * 界面据此报「远端没有 bash」——而远端完全正常。排查花了很多轮，因为每一层
 * 单独看都是对的，错的是它们拼在一起的方式。
 *
 * <p>本测试把这条不变量钉住：每一行的输出都必须以显式换行结尾。
 */
public final class ProbeScriptTest {

    private static int checks;
    private static int failures;

    private ProbeScriptTest() {
    }

    public static void main(String[] args) {
        System.out.println("=== ProbeScriptTest ===");
        String script = SshTransport.probeScript();

        // 1. 脚本非空。
        check("脚本非空", script != null && script.length() > 0);

        String[] lines = script.split("\n", -1);

        // 2. 每个字段一个不少，且都以行首 printf 'KEY=' 的形式出现。
        String[] keys = {"OSNAME", "KERNEL", "RELEASE", "MACHINE", "HOME",
                         "BASH", "PYTHON", "CONDA", "USER", "PWD"};
        for (int i = 0; i < keys.length; i++) {
            String lead = "printf '" + keys[i] + "='";
            int at = script.indexOf(lead);
            check("含字段 " + keys[i], at >= 0);
            // 必须出现在某一行的行首：前面要么是串首，要么紧跟换行。
            check("字段 " + keys[i] + " 在行首",
                    at == 0 || (at > 0 && script.charAt(at - 1) == '\n'));
        }

        // 3. 核心不变量：每一行都以显式换行结束输出。
        //
        //    命令自己带不带换行不受我们控制（printf '%s' 就不带），所以每行末尾
        //    必须由我们补一个 printf '\n'。少了它，下一行的 KEY 就会被吞掉。
        int body = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().isEmpty()) {
                continue;
            }
            body++;
            check("第 " + (i + 1) + " 行末尾补了换行", line.contains("printf '\\n'"));
        }
        check("脚本行数 = 9", body == keys.length);

        // 4. 具体回到那个真实故障：HOME 行必须自带换行。
        check("HOME 行不再吞掉下一个字段",
                script.contains("printf '%s' \"$HOME\"; printf '\\n'"));

        // 5. conda 不能只看 PATH。
        //
        //    非交互式 SSH 会话不会读 ~/.bashrc，所以「conda init 过」的机器在
        //    command -v conda 下依然查不到——实测就是这样：CONDA=none，而
        //    ~/miniconda3 里装得好好的。探测必须回退到标准安装位置，
        //    否则会误报「没装 conda」，让用户白白重装一遍。
        check("CONDA 回退到 ~/miniconda3",
                script.contains("\"$HOME/miniconda3/bin/conda\""));
        check("CONDA 回退到 ~/anaconda3",
                script.contains("\"$HOME/anaconda3/bin/conda\""));
        check("CONDA 仍优先用 PATH",
                script.contains("c=$(command -v conda 2>/dev/null)"));

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
            System.out.println("  FAIL: " + label);
        }
    }
}

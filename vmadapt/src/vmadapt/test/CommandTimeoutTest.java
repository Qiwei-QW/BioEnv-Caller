package vmadapt.test;

import vmadapt.ui.ConnectionPanel;

/**
 * 外部命令的等待上限测试。
 *
 * <p>回归的是一个把界面永久卡死的缺陷：以前的实现是在本线程
 * {@code readLine()} 到 EOF 之后才 {@code waitFor()}。子进程一旦不自己结束，
 * {@code readLine} 就永远不返回，超时也就永远轮不到——用户看到的是
 * 「点了生成密钥，三分钟没有任何反应」，面板永远停在「运行中」。
 *
 * <p>触发条件很常见：{@code ssh-keygen} 发现目标文件已存在时会转为交互提问
 * 「Overwrite (y/n)?」，而插件的子进程没有 stdin。
 *
 * <p>所以这里用 {@code cmd /c pause} 造一个同样「等输入、永不结束」的子进程，
 * 断言它最终会被强制结束并标记为超时。上限临时调小到 1.5 秒，否则每次跑测试
 * 都要真等一分钟。
 */
public final class CommandTimeoutTest {

    private static int checks;
    private static int failures;

    private CommandTimeoutTest() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== CommandTimeoutTest ===");

        final long saved = ConnectionPanel.OpenSsh.timeoutMs;
        try {
            ConnectionPanel.OpenSsh.timeoutMs = 1500L;

            // pause 会一直等按键，是「子进程等 stdin」最干净的复现。
            long t0 = System.currentTimeMillis();
            ConnectionPanel.OpenSsh.Result r = ConnectionPanel.OpenSsh.runWithPrefixes(
                    "failed: ", "cmd: ", "cmd.exe", "/c", "pause");
            long ms = System.currentTimeMillis() - t0;

            check("卡住的命令最终会返回（实际 " + ms + "ms）", ms < 30000);
            check("被标记为超时", r.timedOut);
            check("超时结果是非零退出码", r.exitCode != 0);

            // 正常命令不能被这条改动弄坏：输出仍要读全。
            ConnectionPanel.OpenSsh.Result ok = ConnectionPanel.OpenSsh.runWithPrefixes(
                    "failed: ", "cmd: ", "cmd.exe", "/c", "echo hello-timeout-test");
            check("正常命令仍然成功", ok.exitCode == 0);
            check("正常命令不会被标记超时", !ok.timedOut);
            check("正常命令的输出被完整捕获", ok.output.contains("hello-timeout-test"));
        } finally {
            ConnectionPanel.OpenSsh.timeoutMs = saved;
        }

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

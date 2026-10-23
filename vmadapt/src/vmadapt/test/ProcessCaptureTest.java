package vmadapt.test;

import vmadapt.core.ProcessCapture;

/**
 * 超时到底能不能到：一个卡住不结束的子进程，必须在上限附近被强制结束并返回。
 *
 * <p>回归的是一个真实存在的缺陷：「在本线程 readLine 读到 EOF，然后 waitFor(超时)」。
 * 子进程只要一直开着 stdout（等输入、等锁、等网络），readLine 就永不返回，waitFor
 * 那一行也就永远执行不到——超时形同虚设，界面永远停在「运行中」。
 *
 * <p>复现方式与 {@link CommandTimeoutTest} 同源：起一个 30 秒才会自己结束的子进程
 * （Windows 上是 {@code ping -n 30}，其它系统是 {@code sleep 30}），把上限调小到
 * 2 秒，断言它在几秒内返回、被标记超时、并且进程确实已经不在运行。用 ping 而不是
 * {@code pause}：它不依赖 stdin，更接近「工具自己卡住」的真实情形。
 *
 * <p>没有可用的子进程命令时打印 SKIP 并以 0 退出，不把「没测」说成「测过了」。
 */
public final class ProcessCaptureTest {

    private static int checks;
    private static int failures;

    private ProcessCaptureTest() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== ProcessCaptureTest ===");

        String[] slow = slowArgv();
        if (slow == null) {
            System.out.println("SKIP  no usable child command on this platform");
            return;
        }
        Process slowProcess;
        try {
            slowProcess = new ProcessBuilder(slow).start();
        } catch (Exception e) {
            System.out.println("SKIP  could not start the child command: " + e);
            return;
        }

        final StringBuilder outText = new StringBuilder();
        final StringBuilder errText = new StringBuilder();
        long t0 = System.currentTimeMillis();
        ProcessCapture.Outcome timedOut = ProcessCapture.run(slowProcess, 2,
                new ProcessCapture.LineSink() {
                    @Override
                    public void line(String text, boolean isError) {
                        outText.append(text).append('\n');
                    }
                },
                new ProcessCapture.LineSink() {
                    @Override
                    public void line(String text, boolean isError) {
                        errText.append(text).append('\n');
                    }
                });
        long ms = System.currentTimeMillis() - t0;
        System.out.println("  (timed-out child returned in " + ms + " ms)");

        // 旧实现要等子进程自己结束（约 29 秒）才会走到 waitFor，所以这个上限
        // 就是那条缺陷的分界线：2 秒的超时必须在几秒内返回。
        check("卡住的子进程在上限附近返回（实际 " + ms + " ms）", ms < 6000);
        check("被标记为超时", timedOut.timedOut);
        check("记录了强制结束", timedOut.destroyed);
        check("超时结果退出码为 -1", timedOut.exitCode == -1);
        check("子进程已经不在运行", waitGone(slowProcess, 3000));

        // 正常结束的命令不能被这次改动弄坏：退出码、两个流都要照旧。
        checkNormalCompletion();

        System.out.println(failures == 0
                ? "PASS " + checks + " checks"
                : "FAIL: " + failures + " of " + checks + " checks failed");
        if (failures > 0) {
            System.exit(1);
        }
    }

    /** 短命令：退出码 0，stdout 与 stderr 分别落到各自的回调里。 */
    private static void checkNormalCompletion() throws Exception {
        final StringBuilder out = new StringBuilder();
        final StringBuilder err = new StringBuilder();
        final Process p;
        try {
            p = new ProcessBuilder(fastArgv("pc-out", false)).start();
        } catch (Exception e) {
            System.out.println("SKIP  could not start the echo command: " + e);
            return;
        }
        long t0 = System.currentTimeMillis();
        ProcessCapture.Outcome o = ProcessCapture.run(p, 10,
                new ProcessCapture.LineSink() {
                    @Override
                    public void line(String text, boolean isError) {
                        out.append(text).append('\n');
                    }
                },
                new ProcessCapture.LineSink() {
                    @Override
                    public void line(String text, boolean isError) {
                        err.append(text).append('\n');
                    }
                });
        long ms = System.currentTimeMillis() - t0;

        check("正常命令的退出码是 0", o.exitCode == 0);
        check("正常命令没有被标记超时", !o.timedOut);
        check("正常命令没有触发强制结束", !o.destroyed);
        check("stdout 收到了内容", out.toString().contains("pc-out"));
        check("正常命令不会拖满超时（实际 " + ms + " ms）", ms < 6000);

        // stderr 单独一路：内容不能串到 stdout 上。
        final StringBuilder errOnly = new StringBuilder();
        final Process p2;
        try {
            p2 = new ProcessBuilder(fastArgv("pc-err", true)).start();
        } catch (Exception e) {
            System.out.println("SKIP  could not start the stderr command: " + e);
            return;
        }
        ProcessCapture.run(p2, 10,
                new ProcessCapture.LineSink() {
                    @Override
                    public void line(String text, boolean isError) {
                        // stdout 上不该出现 pc-err
                    }
                },
                new ProcessCapture.LineSink() {
                    @Override
                    public void line(String text, boolean isError) {
                        errOnly.append(text).append('\n');
                    }
                });
        check("stderr 被单独收到", errOnly.toString().contains("pc-err"));
    }

    /**
     * 一个 30 秒才会自己结束的子进程。
     *
     * @return 命令行数组；当前平台没有可用命令时返回 null（调用方打印 SKIP）
     */
    private static String[] slowArgv() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) {
            return new String[] {"cmd.exe", "/c", "ping -n 30 127.0.0.1"};
        }
        return new String[] {"sh", "-c", "sleep 30"};
    }

    /** 一条立刻结束并能往指定流写字的命令。 */
    private static String[] fastArgv(String word, boolean toStderr) {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String body = toStderr ? "echo " + word + " 1>&2" : "echo " + word;
        if (os.contains("win")) {
            return new String[] {"cmd.exe", "/c", body};
        }
        return new String[] {"sh", "-c", body};
    }

    /** 有界地等一个进程消失；返回它是否真的不在了。 */
    private static boolean waitGone(Process p, long maxMs) {
        long end = System.currentTimeMillis() + maxMs;
        while (System.currentTimeMillis() < end) {
            if (!p.isAlive()) {
                return true;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return !p.isAlive();
            }
        }
        return !p.isAlive();
    }

    private static void check(String label, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("  FAIL: " + label);
        }
    }
}

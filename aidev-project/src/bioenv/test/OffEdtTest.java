package bioenv.test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.swing.SwingUtilities;

import bioenv.ui.OffEdt;

/**
 * 检查 {@link OffEdt}：把可能卡住的操作挪出事件线程。
 *
 * <p>这里钉住四件事：
 *
 * <ol>
 *   <li>{@code run()} 必须<b>立刻</b>返回 —— 任务睡 1 秒，调用方 100 毫秒内就得回来，
 *       否则事件线程还是被挡住了；</li>
 *   <li>任务真的在<b>别的</b>线程上执行了（不是被悄悄丢掉）；</li>
 *   <li>任务里的异常不会传到调用方，只记一行 stderr，线程是守护线程；</li>
 *   <li>{@code isEdt()} 在事件线程里为 true、在 {@code run()} 的任务里为 false。</li>
 * </ol>
 *
 * <p>纯 main() 测试，无头可跑：
 * {@code java -Djava.awt.headless=true -cp <classes> bioenv.test.OffEdtTest}
 */
public final class OffEdtTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        line("=== 后台任务不该占住事件线程 ===");
        line("");

        line("[run() 立即返回，不等任务结束]");
        final CountDownLatch finished = new CountDownLatch(1);
        long t0 = System.nanoTime();
        OffEdt.run(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                finished.countDown();
            }
        });
        long returnMs = (System.nanoTime() - t0) / 1000000L;
        check("调用方 " + returnMs + " ms 内返回（< 100）", returnMs < 100);
        check("任务此时还没结束（说明真的没等）", finished.getCount() == 1);
        check("任务随后真的执行完了", finished.await(3, TimeUnit.SECONDS));
        line("");

        line("[任务跑在别的线程上，而且那是守护线程]");
        final CountDownLatch ran = new CountDownLatch(1);
        final String[] worker = new String[3];
        OffEdt.run(new Runnable() {
            @Override
            public void run() {
                Thread t = Thread.currentThread();
                worker[0] = t.getName();
                worker[1] = String.valueOf(OffEdt.isEdt());
                worker[2] = String.valueOf(t.isDaemon());
                ran.countDown();
            }
        });
        check("任务执行了", ran.await(3, TimeUnit.SECONDS));
        check("线程名是 bioenv-off-edt（实际 " + worker[0] + "）",
                "bioenv-off-edt".equals(worker[0]));
        check("任务里 isEdt() 为 false", "false".equals(worker[1]));
        check("线程是守护线程", "true".equals(worker[2]));
        line("");

        line("[任务里的异常被吞掉，只留一行 stderr]");
        final CountDownLatch threw = new CountDownLatch(1);
        PrintStream oldErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        boolean callerSawThrow = false;
        try {
            System.setErr(new PrintStream(captured, true, "UTF-8"));
            try {
                OffEdt.run(new Runnable() {
                    @Override
                    public void run() {
                        threw.countDown();
                        throw new IllegalStateException("boom");
                    }
                });
            } catch (Throwable t) {
                callerSawThrow = true;
            }
            check("调用方没有收到异常", !callerSawThrow);
            check("异常任务确实跑了", threw.await(3, TimeUnit.SECONDS));
            // 那一行是抛异常的线程写的，可能比 countDown 晚一点；等它落进 buffer。
            for (int i = 0; i < 100 && captured.size() == 0; i++) {
                Thread.sleep(20);
            }
        } finally {
            System.setErr(oldErr);
        }
        String err = captured.toString("UTF-8");
        check("stderr 里有一行说明（实际：" + err.trim() + "）",
                err.contains("后台任务异常") && err.contains("boom"));
        check("只记了一行", err.trim().indexOf('\n') < 0);
        line("");

        line("[isEdt() 认得事件线程]");
        final boolean[] inEdt = new boolean[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                inEdt[0] = OffEdt.isEdt();
            }
        });
        check("事件线程里 isEdt() 为 true", inEdt[0]);
        check("主线程里 isEdt() 为 false", !OffEdt.isEdt());
        line("");

        line("[传 null 不炸]");
        boolean nullOk = true;
        try {
            OffEdt.run(null);
        } catch (Throwable t) {
            nullOk = false;
        }
        check("run(null) 安全返回", nullOk);

        line("");
        line(pass + " passed, " + fail + " failed");
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            pass++;
            line("  PASS  " + label);
        } else {
            fail++;
            line("  FAIL  " + label);
        }
    }

    private static void line(String s) {
        System.out.println(s);
    }
}

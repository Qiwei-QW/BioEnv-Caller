package bioenv.test;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import bioenv.ui.MainPanel;

/**
 * 界面热路径不该在每次调用时加载新类。
 *
 * <p>回归的是用户每次装插件都会看到的未捕获异常：
 *
 * <pre>
 *   NoClassDefFoundError: bioenv/ui/MainPanel$35
 *       at bioenv.ui.MainPanel.setStatus(MainPanel.java:2784)
 *       at bioenv.ui.MainPanel$5.done(MainPanel.java:880)   // refreshSshList 的 catch 分支
 *       at biocjava.GUIexcutors.TBtools.installPlugin(...)
 * </pre>
 *
 * <p>根因：{@code setStatus} 每次都 {@code new} 一个匿名 Runnable，于是<b>每次都要
 * 按需加载一个新的匿名类</b>。TBtools 重装插件时先把 jar 换成新的、却不会丢弃旧实例；
 * 旧实例此刻第一次走到这行、要加载自己的类，就再也取不到了——「只是刷新一下
 * 状态栏」于是炸成一大段栈。
 *
 * <p>把 Runnable 预先建好之后，构造时就把类加载完了，热路径不再需要新类。
 * 本测试用 JVM 的类加载计数来钉住这一点：预热之后，重复调用不应再加载任何新类。
 *
 * <p>顺带钉住三个方法的语义没被这次重构改坏（换行行为）。
 */
public final class HotPathClassLoadTest {

    private static int checks;
    private static int failures;

    private HotPathClassLoadTest() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== HotPathClassLoadTest ===");

        final MainPanel[] p = new MainPanel[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                p[0] = new MainPanel();
            }
        });
        final MainPanel panel = p[0];
        // 构造器会异步加载一次环境并往日志里写东西，先等它结束，
        // 否则它插进日志中间会让下面的语义断言看起来像是坏的。
        Thread.sleep(3000);
        flushEdt();

        final Method setStatus = MainPanel.class.getDeclaredMethod("setStatus", String.class);
        final Method appendLog = MainPanel.class.getDeclaredMethod("appendLog", String.class);
        final Method appendLogRaw = MainPanel.class.getDeclaredMethod("appendLogRaw", String.class);
        setStatus.setAccessible(true);
        appendLog.setAccessible(true);
        appendLogRaw.setAccessible(true);

        // 1. 语义没被改坏：appendLog 之间换行，appendLogRaw 不换行。
        //    先清空日志，只断言我们这三行拼出来的样子。
        logArea(panel).setText("");
        invoke(appendLog, panel, "line1");
        invoke(appendLog, panel, "line2");
        invoke(appendLogRaw, panel, " raw");
        flushEdt();
        String text = logText(panel);
        check("appendLog 之间插入换行", text.contains("line1\nline2"));
        check("appendLogRaw 紧接其后不换行", text.contains("line2 raw"));
        check("第一条日志前面没有多余空行", text.startsWith("line1"));

        // 2. 三个 Runnable 都是字段（构造时就建好），不是每次调用现建。
        check("applyStatus 是预先建好的字段", field(panel, "applyStatus") instanceof Runnable);
        check("applyLog 是预先建好的字段", field(panel, "applyLog") instanceof Runnable);

        // 3. 核心：setStatus 预热之后重复调用不再加载任何新类。
        //
        //    只盯 setStatus，因为出问题的就是它。appendLog 反复写 JTextArea 会正常
        //    触发 Swing 内部类的首次加载，那是文档模型的正常开销，不是这个 bug。
        //    判据正是那个 bug 的判据：只要热路径还需要新类，孤儿实例就会在第一次
        //    走到那里时炸掉。
        for (int i = 0; i < 500; i++) {
            invoke(setStatus, panel, "warm " + i);
        }
        flushEdt();

        java.lang.management.ClassLoadingMXBean bean =
                ManagementFactory.getClassLoadingMXBean();
        long before = bean.getLoadedClassCount();
        for (int i = 0; i < 5000; i++) {
            invoke(setStatus, panel, "x" + i);
        }
        flushEdt();
        long after = bean.getLoadedClassCount();

        System.out.println("  setStatus 5000 次，类加载计数: " + before + " -> " + after);
        check("setStatus 热路径不再按需加载新类（" + before + " -> " + after + "）",
                after == before);

        // 顺带确认状态栏真的被更新了（没有为了「不加载类」而把功能丢掉）。
        check("状态栏内容确实被更新", "x4999".equals(statusText(panel)));

        System.out.println(failures == 0
                ? "PASS " + checks + " checks"
                : "FAIL: " + failures + " of " + checks + " checks failed");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                // 只为把队列排空
            }
        });
    }

    private static void invoke(Method m, MainPanel p, String arg) throws Exception {
        try {
            m.invoke(p, arg);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException) {
                throw (RuntimeException) c;
            }
            if (c instanceof Error) {
                throw (Error) c;
            }
            throw e;
        }
    }

    private static Object field(MainPanel p, String name) throws Exception {
        Field f = MainPanel.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(p);
    }

    private static String logText(MainPanel p) throws Exception {
        return logArea(p).getText();
    }

    private static JTextArea logArea(MainPanel p) throws Exception {
        Field f = MainPanel.class.getDeclaredField("logArea");
        f.setAccessible(true);
        return (JTextArea) f.get(p);
    }

    private static String statusText(MainPanel p) throws Exception {
        Field f = MainPanel.class.getDeclaredField("statusLabel");
        f.setAccessible(true);
        return ((javax.swing.JLabel) f.get(p)).getText();
    }

    private static void check(String label, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("  FAIL: " + label);
        }
    }
}

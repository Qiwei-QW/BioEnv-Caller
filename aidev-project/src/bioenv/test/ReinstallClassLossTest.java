package bioenv.test;

import java.lang.reflect.Method;

/**
 * 「重装插件后旧实例的界面更新」的测试。
 *
 * <p>回归的是用户每次安装插件都会看到的那个未捕获异常：
 *
 * <pre>
 *   NoClassDefFoundError: bioenv/ui/MainPanel$35
 *       at bioenv.ui.MainPanel.setStatus(MainPanel.java:2784)
 *       at bioenv.ui.MainPanel$5.done(MainPanel.java:880)
 *       ...
 *       at biocjava.GUIexcutors.TBtools.installPlugin(TBtools.java:743)
 * </pre>
 *
 * <p>原因不是打包缺类（已经逐项核对过：jar 里 {@code MainPanel$1..$37} 一个不少，
 * 且 {@code MainPanel.class} 引用的正好是这 37 个）。而是 TBtools 替换 jar 之前
 * 不丢弃上一版实例：旧 {@code MainPanel} 的 SwingWorker 回来投递结果时，
 * 要用的匿名类只能从那份<b>刚被覆盖掉的 jar</b> 里取，于是取不到。
 *
 * <p>本测试钉住新加的兜底规则：<b>只吞「本插件自己的类加载不到」</b>，
 * 别的失败一律照旧抛出——把真问题也吞掉比报错更糟。
 */
public final class ReinstallClassLossTest {

    private static int checks;
    private static int failures;
    private static int ran;

    private ReinstallClassLossTest() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== ReinstallClassLossTest ===");

        // 反射调用，不 import：FileDialogs 本身是包级私有的，为测试把它公开
        // 等于为了自检把 API 撑大，不值得。
        Class<?> fd = Class.forName("bioenv.ui.FileDialogs");
        Method guard = fd.getDeclaredMethod("guard", Runnable.class);
        guard.setAccessible(true);
        final Method onEdt = fd.getDeclaredMethod("onEdt", Runnable.class);
        onEdt.setAccessible(true);

        // 1. 正常更新照常执行。
        ran = 0;
        invoke(guard, new Runnable() {
            @Override
            public void run() {
                ran++;
            }
        });
        check("正常的界面更新照常执行", ran == 1);

        // 2. 本插件自己的类加载不到：必须被挡住，不能冒到未捕获异常对话框。
        check("自己包里的类加载不到 → 静默跳过",
                !throwsFrom(guard, new NoClassDefFoundError("bioenv/ui/MainPanel$35")));
        check("vmadapt 包同样处理",
                !throwsFrom(guard, new NoClassDefFoundError("vmadapt/ui/ConnectionPanel$12")));

        // 3. 别人的类加载不到：必须抛出。那可能是真的少了依赖，不能吞。
        check("第三方类加载不到 → 照旧抛出",
                throwsFrom(guard, new NoClassDefFoundError("org/apache/commons/lang3/StringUtils")));

        // 4. 别的异常一律不动。吞掉运行期异常会把真 bug 藏起来。
        check("RuntimeException 照旧抛出",
                throwsFrom(guard, new IllegalStateException("real bug")));
        check("Error 照旧抛出",
                throwsFrom(guard, new StackOverflowError()));

        // 5. 走真实路径再验一遍：onEdt 在事件线程上是同步执行的。
        ran = 0;
        javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                try {
                    onEdt.invoke(null, new Runnable() {
                        @Override
                        public void run() {
                            ran++;
                        }
                    });
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        check("onEdt 在事件线程上真的执行了", ran == 1);

        System.out.println(failures == 0
                ? "PASS " + checks + " checks"
                : "FAIL: " + failures + " of " + checks + " checks failed");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static void invoke(Method guard, Runnable r) throws Exception {
        try {
            guard.invoke(null, r);
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

    /** 让被 guard 包住的 Runnable 抛指定错误，返回它有没有冒出来。 */
    private static boolean throwsFrom(Method guard, final Throwable toThrow) {
        try {
            invoke(guard, new Runnable() {
                @Override
                public void run() {
                    if (toThrow instanceof RuntimeException) {
                        throw (RuntimeException) toThrow;
                    }
                    if (toThrow instanceof Error) {
                        throw (Error) toThrow;
                    }
                    throw new IllegalStateException(toThrow);
                }
            });
            return false;
        } catch (Throwable escaped) {
            return true;
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

package bioenv.ui;

/**
 * 把可能卡住的操作挪出 Swing 事件线程。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>事件线程是画界面、跑按钮监听的地方：它被任何一次调用挡住，整个窗口就不刷新、
 * 不响应点击，用户看到的就是"插件死了"。而创建进程（{@code ProcessBuilder.start()}）、
 * 往远端发 kill（{@code Transport.runDetached}）、往一个不再读数据的进程写管道，
 * 都可能因为系统、安全软件、WSL 或 ssh 的状态而长时间不返回。
 *
 * <p>这里只做一件事：把任务丢到一条短命的守护线程上，然后<b>立刻返回</b>。
 * 任务自己的界面更新由它通过 {@code SwingUtilities.invokeLater} /
 * {@link FileDialogs#onEdt} 送回去，本类不代劳——那属于各个调用点的语义。
 *
 * <h2>为什么异常只写 stderr</h2>
 *
 * <p>调用方几乎都在事件线程上，异常根本没有合理的去处：抛回去会变成未捕获异常对话框，
 * 而它描述的是"后台那件事失败了"，跟当前点击的关系用户看不懂。所以吞掉，但写一行
 * 到 stderr —— TBtools 会把它记进日志，排查时还找得到（同 {@link FileDialogs} 的判据）。
 */
public final class OffEdt {

    /** 后台线程的名字，出问题时在栈里一眼能认出是谁。 */
    private static final String THREAD_NAME = "bioenv-off-edt";

    private OffEdt() {
    }

    /**
     * 在一条新的守护线程上执行 {@code task}，立即返回。
     *
     * <p><b>绝不向调用方抛异常</b>：任务自己的失败（包括 {@link Throwable}）只写一行
     * stderr。守护线程保证它不会把 JVM 或 TBtools 的退出拖住。
     *
     * @param task 要执行的任务；{@code null} 直接忽略
     */
    public static void run(final Runnable task) {
        if (task == null) {
            return;
        }
        try {
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        task.run();
                    } catch (Throwable ex) {
                        report(ex);
                    }
                }
            }, THREAD_NAME);
            worker.setDaemon(true);
            worker.start();
        } catch (Throwable ex) {
            // 连线程都建不出来（例如内存已经耗尽）：按同样的方式记一行，不抛。
            report(ex);
        }
    }

    /** 当前线程是不是 Swing 事件线程（测试用；生产代码里判断线程归属也用它）。 */
    public static boolean isEdt() {
        return javax.swing.SwingUtilities.isEventDispatchThread();
    }

    /** 后台任务失败时唯一的动静：一行 stderr，不打断调用方。 */
    private static void report(Throwable ex) {
        System.err.println("[BioEnv Caller] 后台任务异常（已忽略）：" + ex);
    }
}

package vmadapt.core;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 一次外部进程执行的收尾：两个流各一个读取线程，带超时地等待进程结束，超时就强制结束。
 *
 * <h2>为什么必须有这个类</h2>
 *
 * <p>「在本线程 {@code readLine} 读到 EOF，然后再 {@code waitFor(超时)}」这个写法
 * 看着是对的，实际超时永远轮不到：{@code readLine} 只在子进程关闭 stdout 时才返回，
 * 而子进程一旦卡住（等输入、等锁、等网络），它就永远不会关闭 stdout。于是
 * {@code waitFor} 那一行根本执行不到，超时形同虚设，用户看到的是界面永远停在
 * 「运行中」，只能重启 TBtools。
 *
 * <p>这个坑在 {@code vmadapt.ui.ConnectionPanel.OpenSsh} 上已经被踩过一次并改掉了
 * （见那里的 {@code runWithPrefixes} 与 {@code CommandTimeoutTest}），但传输层
 * （{@link SshTransport} 与插件侧的 WSL 桥）没有跟着改。本类把这件事收成一处，
 * 两条通道共用同一份实现。
 *
 * <h2>为什么两个流都要排空</h2>
 *
 * <p>不读的那一路管道缓冲区（Windows 上约 4KB）写满之后，子进程的 {@code write}
 * 会阻塞，于是它永远不结束——即使我们等的是另一路。所以「两个流各起一个线程」
 * 与「超时到了就杀」是两件缺一不可的事。
 *
 * <h2>超时之后</h2>
 *
 * <p>{@code destroyForcibly} 杀掉的是<b>本机这个客户端进程</b>。对 WSL 来说那是
 * {@code wsl.exe}，对 SSH 来说那是 {@code ssh.exe}；远端真正干活的进程是否随之
 * 结束，不由本类决定，调用方也不得据此宣称「远端已停止」。
 *
 * <h2>为什么不 close 那两个流</h2>
 *
 * <p>本机实测：子进程被强杀之后，如果它还起了别的进程（{@code cmd.exe} 起了
 * {@code ping}），孙进程仍握着管道写端，这时从等待线程里 close 读端会阻塞到孙进程
 * 退出（本次测到 23 秒）——那正是本类要消灭的无上限等待。读取线程都是 daemon，
 * 靠读到 EOF 自己收尾，详见 {@link #run} 末尾的说明。
 */
public final class ProcessCapture {

    /** 编码统一 UTF-8：与两个传输层原来的读取方式一致。 */
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** 超时/结束后等读取线程收尾的上限（毫秒）；超时就放弃等待，线程是 daemon。 */
    private static final long PUMP_JOIN_MS = 500L;

    private ProcessCapture() {
    }

    /**
     * 逐行回调；形状与 {@code SshTransport.LineSink}、{@code WslBridge.LineSink}
     * 完全一致，所以两边都能用匿名类直接接上，不需要再包一层。
     */
    public interface LineSink {
        /**
         * @param text    一行输出（行尾换行已去掉，未做 ANSI 清理）
         * @param isError true 表示这行来自 stderr
         */
        void line(String text, boolean isError);
    }

    /** 一次捕获的结果。 */
    public static final class Outcome {

        /** 退出码；-1 表示进程没起来、因超时被杀，或等待被中断。 */
        public int exitCode = -1;

        /** 是否因为超时被强制结束。 */
        public boolean timedOut;

        /** 是否真的调用过 {@code destroyForcibly}。 */
        public boolean destroyed;

        /** 等待过程中线程被中断（调用方通常会把这个中断继续往上抛）。 */
        public boolean interrupted;
    }

    /**
     * 执行并等待一个已经启动的进程，两个流都被排空。
     *
     * @param p              已经 {@code start()} 过的进程；null 时原样返回一个失败结果
     * @param timeoutSeconds 等待上限（秒）；{@code <= 0} 表示不设上限、一直等
     * @param outSink        stdout 的逐行回调；允许为 null（此时只排空、不留内容）
     * @param errSink        stderr 的逐行回调；允许为 null
     * @return 结果快照，永不为 null
     */
    public static Outcome run(final Process p, final int timeoutSeconds,
                              final LineSink outSink, final LineSink errSink) {
        Outcome o = new Outcome();
        if (p == null) {
            return o;
        }
        // 两个读取线程必须在 waitFor 之前起好：否则另一路管道写满会让子进程阻塞，
        // 而我们正卡在这一路的读上。
        Thread outPump = pump(p.getInputStream(), outSink, false);
        Thread errPump = pump(p.getErrorStream(), errSink, true);
        try {
            boolean finished;
            if (timeoutSeconds > 0) {
                finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            } else {
                p.waitFor();
                finished = true;
            }
            if (finished) {
                o.exitCode = p.exitValue();
            } else {
                o.timedOut = true;
                o.destroyed = true;
                p.destroyForcibly();
            }
        } catch (InterruptedException ie) {
            // 调用方所在的线程被要求停下（例如 SwingWorker.cancel）：把进程一起带走，
            // 再把中断标志还回去，让上层自己决定怎么处理。
            o.interrupted = true;
            o.destroyed = true;
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        } finally {
            joinQuietly(outPump);
            joinQuietly(errPump);
            // 刻意不在这里 close() 进程的流。
            //
            // 实测（Windows 11 / JDK 11）：子进程被强杀后，如果它自己又起了别的进程
            // （{@code cmd.exe} 起了 {@code ping}），那个孙进程仍持有管道的写端，
            // 这时从等待线程里 close 读端会一直阻塞，直到孙进程自己退出——本机测到
            // 阻塞了 23 秒。那恰好是本类要消灭的「无上限等待」，所以宁可让两个 daemon
            // 读取线程继续持有流：它们等到远端关掉管道时会自己读到 EOF，并在 finally
            // 里关掉自己的 reader，不需要调用线程参与。
        }
        return o;
    }

    /** 一个流一个读取线程；daemon，进程被杀时读会抛异常并自然结束。 */
    private static Thread pump(final InputStream in, final LineSink sink,
                               final boolean isError) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                BufferedReader br = null;
                try {
                    br = new BufferedReader(new InputStreamReader(in, UTF8));
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (sink != null) {
                            sink.line(line, isError);
                        }
                    }
                } catch (Exception ignored) {
                    // 进程被强杀或流被关闭：读取线程在这里结束是预期行为。
                } finally {
                    if (br != null) {
                        try {
                            br.close();
                        } catch (Exception ignored) {
                            // 关不上也没有别的办法。
                        }
                    }
                }
            }
        }, isError ? "vmadapt-stderr-pump" : "vmadapt-stdout-pump");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** 有界地等读取线程；等不到就放弃（线程是 daemon，不会挡住 JVM 退出）。 */
    private static void joinQuietly(Thread t) {
        if (t == null) {
            return;
        }
        try {
            t.join(PUMP_JOIN_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 有上限的输出收集器。
     *
     * <h2>为什么需要上限</h2>
     *
     * <p>一个有问题的工具可以打印几个 GB（把二进制当文本输出、把整个矩阵逐行 echo）。
     * 以前每一行都被 {@code List.add} 进结果对象，界面那边也无上限地往文本区里追加，
     * 结果是 OutOfMemoryError 或者界面卡到动不了。运行结果里真正有用的是开头和结尾，
     * 记住「之后还有多少行没留下」就够了。
     *
     * <h2>为什么这个类在 vmadapt 里，而 {@code bioenv.core.OutputCap} 只是它的子类</h2>
     *
     * <p>模块依赖只有单向：插件侧（bioenv）依赖本模块，反过来不行。根目录的
     * {@code build.ps1} 先编译 vmadapt，编译时 classpath 是空的，所以
     * {@link SshTransport} 不能 import {@code bioenv.core.OutputCap}——那会让构建
     * 在第一步就失败。行为与默认值只能写在这里一份，插件侧的 OutputCap 继承本类，
     * 两边因此共用同一份实现而不是各自抄一遍。
     */
    public static class CappedOutput {

        /** 默认保留的最大行数。 */
        public static final int DEFAULT_MAX_LINES = 200000;

        /** 默认保留的最大字符数（先到哪个算哪个）。 */
        public static final int DEFAULT_MAX_CHARS = 8000000;

        /** 保留行末尾的丢弃标记；{@code %d} 是丢弃的行数。 */
        public static final String DROPPED_MARKER = "…（已丢弃 %d 行输出）";

        private final List<String> kept = new ArrayList<String>();
        private final int maxLines;
        private final int maxChars;
        private int chars;
        private int dropped;

        /** 用默认上界。 */
        public CappedOutput() {
            this(DEFAULT_MAX_LINES, DEFAULT_MAX_CHARS);
        }

        /**
         * @param maxLines 最多保留多少行；非正数用默认值
         * @param maxChars 最多保留多少字符；非正数用默认值
         */
        public CappedOutput(int maxLines, int maxChars) {
            this.maxLines = maxLines > 0 ? maxLines : DEFAULT_MAX_LINES;
            this.maxChars = maxChars > 0 ? maxChars : DEFAULT_MAX_CHARS;
        }

        /**
         * 收一行。超过上界之后只记数、不再保留，但调用方照旧拿到返回值之外的一切
         * ——本方法不抛异常、不阻塞。
         */
        public synchronized void add(String line) {
            String s = line == null ? "" : line;
            if (kept.size() >= maxLines || chars + s.length() > maxChars) {
                dropped++;
                return;
            }
            kept.add(s);
            chars += s.length();
        }

        /**
         * 保留下来的行。
         *
         * <p>丢弃过东西时最后一行是标记（例如「…（已丢弃 12345 行输出）」）：
         * 没有它，一个被截断的结果会看起来像是完整的，用户会以为工具只输出了这些。
         *
         * @return 只读快照
         */
        public synchronized List<String> lines() {
            List<String> out = new ArrayList<String>(kept.size() + 1);
            out.addAll(kept);
            if (dropped > 0) {
                out.add(marker(dropped));
            }
            return Collections.unmodifiableList(out);
        }

        /** 没有留下来的行数。 */
        public synchronized int dropped() {
            return dropped;
        }

        /** 留下来的行数（不含标记行）。 */
        public synchronized int keptCount() {
            return kept.size();
        }

        /** 留下来的字符数（不含标记行）。 */
        public synchronized int chars() {
            return chars;
        }

        /** 一行丢弃标记的文字。 */
        public static String marker(int droppedLines) {
            return String.format(DROPPED_MARKER, Integer.valueOf(droppedLines));
        }
    }
}

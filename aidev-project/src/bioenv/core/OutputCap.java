package bioenv.core;

import vmadapt.core.ProcessCapture;

/**
 * 一次命令执行里「最多保留多少输出」的上限。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>工具的输出没有上限：把二进制当文本打印、把整个矩阵逐行 echo、或者一个死循环
 * 里反复打错，都能一口气吐出几个 GB。这些行以前全部被 {@code List.add} 进结果对象，
 * 界面那边也无上限地往文本区里追加，最后不是 OutOfMemoryError 就是界面卡到不能动。
 * 结果里真正有用的是开头（版本、报错）和结尾（结论），所以保留到一个上限之后，
 * 只记「还有多少行没留下」。
 *
 * <h2>为什么继承 {@code vmadapt.core.ProcessCapture.CappedOutput}</h2>
 *
 * <p>因为那段实现是 SSH 传输层要用的，而模块依赖只有单向：插件侧（bioenv）
 * 依赖 vmadapt，反过来不行——根目录的 {@code build.ps1} 先编译 vmadapt，
 * 编译它时 classpath 是空的，所以 {@code vmadapt.core.SshTransport} 不能反过来
 * import 本类。行为与默认值只能写一份：实现放在 vmadapt 里，
 * 本类是它在插件侧的公开名字，WSL 通道用它，SSH 通道用它的父类，
 * 于是两条路径的上界、计数、丢弃标记完全一致，不会各偏一边。
 *
 * <h2>约定</h2>
 *
 * <ul>
 *   <li>两个默认上界先到哪个算哪个：{@value #DEFAULT_MAX_LINES} 行，
 *       或 {@value #DEFAULT_MAX_CHARS} 个字符。</li>
 *   <li>{@link #add(String)} 超过上界后只记数、不再保留，绝不抛异常——
 *       它跑在输出读取线程上，抛出去没人接。</li>
 *   <li>{@link #lines()} 在丢弃过东西时，最后一行是丢弃标记
 *       （例如「…（已丢弃 12345 行输出）」）：没有它，被截断的结果会看起来像是完整的。</li>
 *   <li>{@link #dropped()} 与实际丢弃行数一致，界面/日志据此可以说那句话。</li>
 * </ul>
 *
 * <p>逐行回调（{@code Transport.LineSink}）不受这里影响：界面要的是实时的每一行，
 * 它自己负责裁剪；本类只约束<b>留存下来</b>的那一份。
 */
public final class OutputCap extends ProcessCapture.CappedOutput {

    /** 用默认上界（{@value #DEFAULT_MAX_LINES} 行 / {@value #DEFAULT_MAX_CHARS} 字符）。 */
    public OutputCap() {
        super();
    }

    /**
     * @param maxLines 最多保留多少行；非正数用默认值
     * @param maxChars 最多保留多少字符；非正数用默认值
     */
    public OutputCap(int maxLines, int maxChars) {
        super(maxLines, maxChars);
    }
}

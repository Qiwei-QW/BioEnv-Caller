package bioenv.core;

import java.util.Collections;
import java.util.List;

/**
 * 把 {@link WslBridge.Result} 包装成 {@link Transport.Result}。
 *
 * <h2>为什么需要这一层</h2>
 *
 * <p>纯粹是编译规则逼出来的：{@code WslBridge.Result} 是 {@code final} 类，
 * 而 {@link Transport.Result} 必须是接口（否则 {@code SshTransport} 那一侧
 * 没法接上——它自己的 {@code Result} 同样是 final 类，两个类之间在 Java 里
 * 不会因为「长得一样」就产生子类型关系）。既然 {@code WslBridge} 要
 * {@code implements Transport}，它的 {@code runBashCapture} 返回类型就必须
 * 换成 {@code Transport.Result}；这个适配器负责把内部的
 * {@code WslBridge.Result} 转出来。
 *
 * <h2>为什么是「视图」而不是「拷贝」</h2>
 *
 * <p>本类不复制任何一行输出，只持有原对象的引用并转发读取。理由：
 * <ul>
 *   <li>一次 {@code conda env list} 之类的结果可能有几千行，而 {@code WslBridge}
 *       的结果对象在传输层内部刚刚构造好、没有任何别处持有它，
 *       再复制一遍纯属浪费。</li>
 *   <li>转发不会出错的地方就不要自己写逻辑——本类刻意不重新实现
 *       {@code ok()} / {@code stdoutText()}，而是直接调用原方法，
 *       保证「成功判据」在两侧永远只有一份定义（{@code exitCode == 0 && !timedOut}）。
 *       如果在这里重写一遍 && 表达式，将来一侧改了判据另一侧没改，
 *       就会出现同一份结果在 WSL 路径和 SSH 路径上结论不同的诡异 bug。</li>
 * </ul>
 *
 * <p>唯一没有原样转发的是两个列表访问器：见 {@link #stdout()} 的说明。
 *
 * @see Transport.Result
 */
public final class WslResultAdapter implements Transport.Result {

    private final WslBridge.Result delegate;

    /**
     * @param delegate 被包装的结果；为 null 时整体按「失败且无输出」处理，
     *                 而不是抛异常——传输层可能因为进程没起来而返回 null，
     *                 此时让上层拿到一个空结果比让它崩掉更有用
     */
    public WslResultAdapter(WslBridge.Result delegate) {
        this.delegate = delegate;
    }

    /** 取回被包装的原始结果，供仍需 {@code WslBridge} 特有行为的调用方使用。 */
    public WslBridge.Result unwrap() {
        return delegate;
    }

    @Override
    public int exitCode() {
        // delegate 为 null 时返回 -1：与「进程根本没起来」的约定一致，
        // 因此 ok() 自然为 false，上层不需要额外判空。
        return delegate == null ? -1 : delegate.exitCode;
    }

    @Override
    public boolean timedOut() {
        return delegate != null && delegate.timedOut;
    }

    /**
     * 直接转发，不重新计算。
     *
     * <p>这一行是整个适配器里最重要的设计决定：成功判据只在
     * {@link WslBridge.Result} 里定义一次，适配器不许有自己的一套。
     */
    @Override
    public boolean ok() {
        return delegate != null && delegate.ok();
    }

    @Override
    public String stdoutText() {
        return delegate == null ? "" : delegate.stdoutText();
    }

    @Override
    public String stderrText() {
        return delegate == null ? "" : delegate.stderrText();
    }

    /**
     * stdout 逐行内容，包装成只读视图。
     *
     * <p>为什么不像其它方法那样直接返回原列表：{@code WslBridge.Result.stdout}
     * 是 {@code public final List}，任何人都能 {@code add()} 或 {@code clear()}。
     * 适配器是「接缝」，它一旦把可变引用交出去，消费者 A 的误写就会改变
     * 消费者 B 看到的结果，而这种 bug 极难定位。包一层
     * {@link Collections#unmodifiableList} 之后，跨过接缝的写操作会在
     * 写入点立刻抛 {@link UnsupportedOperationException}，而不是在别处
     * 表现为数据错乱。
     *
     * <p>代价是每次调用都会新建一个包装对象。调用点普遍写成
     * {@code for (String line : r.stdout())}（每次循环只调用一次），
     * 这个开销可以忽略。
     */
    @Override
    public List<String> stdout() {
        if (delegate == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(delegate.stdout);
    }

    /** stderr 逐行内容，只读视图；理由同 {@link #stdout()} 与 {@link Transport.Result#stdout()}。 */
    @Override
    public List<String> stderr() {
        if (delegate == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(delegate.stderr);
    }

    @Override
    public String toString() {
        return "WslResult[" + (delegate == null ? "null" : delegate.toString()) + "]";
    }
}

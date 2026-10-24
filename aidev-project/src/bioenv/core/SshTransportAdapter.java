package bioenv.core;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import vmadapt.core.SshTarget;
import vmadapt.core.SshTransport;

/**
 * 把 vmadapt 模块的 {@link SshTransport} 接到 {@link Transport} 接缝上。
 *
 * <h2>为什么是适配器，而不是让 SshTransport 直接 implements Transport</h2>
 *
 * <p>因为 {@code vmadapt.core.SshTransport} 属于一个<b>已经验收、只读复用</b>的
 * 模块（{@code 虚拟机适配\src\vmadapt\}，750 项断言全绿）。让它
 * {@code implements bioenv.core.Transport} 需要改它的类声明，并且会在
 * vmadapt 模块里引入一条指向 TBtools 插件代码的编译依赖——依赖方向反了，
 * 而且会破坏「vmadapt 可独立编译测试」这个已经成立的性质。
 * 适配器把这条依赖收在一个文件里：vmadapt 保持原样，bioenv 侧拿到统一类型。
 *
 * <h2>本适配器吸收的三处真实签名差异</h2>
 *
 * <p>两边「同构」但不是同一套签名。以下三点是以磁盘源码为准实测出来的
 * （不是推测），也正是适配器存在的具体理由：
 *
 * <ol>
 *   <li><b>{@code getDistro()} 在 SSH 侧不存在。</b>
 *       {@code SshTransport} 整个类没有「发行版」概念，也没有同名方法。
 *       本类用 {@link SshTarget#toDisplay()} 的结果（{@code user@host:port}）
 *       作为等价标签，理由见 {@link #getDistro()}。</li>
 *
 *   <li><b>{@code runDetached} 参数个数不同。</b>
 *       SSH 侧是 {@code runDetached(String)}——只有脚本，因为目标主机是构造时
 *       就固定在 {@code SshTransport.target} 里的；WSL 侧是
 *       {@code runDetached(String, String)}，第二个参数是发行版名。
 *       接口统一取 2 参数版本，本类丢弃第二个参数。看似「丢信息」，
 *       但 SSH 的通道身份本来就不可在同一次会话中改变，
 *       丢掉的那个值对它没有意义。</li>
 *
 *   <li><b>受检异常口径不同。</b>
 *       SSH 侧 {@code runDetached} 不抛受检异常，失败时返回 {@code null}；
 *       WSL 侧声明 {@code throws IOException}。接口按 WSL 侧声明，
 *       本类在返回 null 时<b>主动补抛</b> {@link IOException}，
 *       把「静默 null」翻译成接缝的契约。见 {@link #runDetached(String, String)}。</li>
 * </ol>
 *
 * <h2>不转发 {@code probe()}</h2>
 *
 * <p>{@code SshTransport.probe()} 是「测试连接」按钮要实现的功能，
 * 不属于<b>执行</b>语义——它探测的是内核、bash、conda 是否存在，
 * 返回的也不是「一段脚本的输出」。把它塞进 {@link Transport} 会让
 * WSL 侧被迫实现一个自己用不上的方法，所以不转发；
 * 需要它的调用方（例如 SSH 连接设置面板）用 {@link #ssh()} 拿到原始对象即可。
 * 这是有意保留的逃生舱，不是遗漏。
 *
 * @see Transport
 * @see WslResultAdapter
 */
public final class SshTransportAdapter implements Transport {

    private final SshTransport delegate;

    /**
     * @param target 连接目标；为 null 时按「什么都还没填」处理。
     *               {@code SshTransport} 的构造器本身对 null 是安全的
     *               （它会换成一个空的 SshTarget，之后任何执行都立刻返回
     *               带中文提示的失败结果而不是抛异常），本类保持这个性质，
     *               好让界面能在用户还没填完表单时就安全地构造对象
     */
    public SshTransportAdapter(SshTarget target) {
        this.delegate = new SshTransport(target);
    }

    /**
     * 便捷构造，省去调用方自己拼 {@link SshTarget}。
     *
     * <p>参数顺序与 {@code vmadapt.core.SshTarget(String, int, String, String)}
     * 主构造严格一致，避免两处顺序不同导致的隐蔽 bug。
     *
     * @param host    主机名或 IP
     * @param port    SSH 端口，非正数回退到 22
     * @param user    登录用户名
     * @param keyPath 私钥文件路径，可为 null（表示交给 ssh.exe 的默认密钥）；
     *                注意本类没有 password 参数——vmadapt 走公钥认证，
     *                ssh.exe 以 BatchMode=yes 运行，禁止一切交互式提问
     */
    public SshTransportAdapter(String host, int port, String user, String keyPath) {
        this(new SshTarget(host, port, user, keyPath));
    }

    /**
     * 取回被包装的原始 SSH 传输对象。
     *
     * <p>给 {@link Transport} 覆盖不到的能力留出口：{@code probe()}、
     * {@code killRemote(String)}、{@code diagnose(Result)}、
     * {@code setRunTimeoutSeconds(int)} 都只存在于 SSH 侧。
     * 调用方拿到它就应该清楚自己已经离开了抽象接缝。
     */
    public SshTransport ssh() {
        return delegate;
    }

    /** 当前的连接目标（原样转发 {@link SshTransport#target()}）。 */
    public SshTarget target() {
        return delegate.target();
    }

    /**
     * 设置执行类命令的超时秒数，链式返回自身以便一行写完。
     *
     * <p>转发到 {@code SshTransport.setRunTimeoutSeconds(int)}，该方法对
     * 非正数会忽略（保持原值），本类不额外校验，也不假装自己知道合法范围。
     *
     * @param seconds 超时秒数；非正数被底层忽略
     * @return this，便于链式调用
     */
    public SshTransportAdapter withTimeoutSeconds(int seconds) {
        delegate.setRunTimeoutSeconds(seconds);
        return this;
    }

    // ------------------------------------------------------------------
    // Transport 实现
    // ------------------------------------------------------------------

    @Override
    public Result runBashCapture(String script) {
        return new SshResultAdapter(delegate.runBashCapture(script));
    }

    @Override
    public Result runBashStreaming(String script, final LineSink sink) {
        return runBashStreaming(script, sink, null);
    }

    /**
     * 三参数版本：除流式执行外，还带上「结束本次运行的远端命令」。
     *
     * <h2>为什么原样转发，而不在这里拼命令</h2>
     *
     * <p>那段脚本是按<b>本次运行</b>的标记生成的（{@code CommandBuilder.killScript(marker)}），
     * 生成它需要标记，而标记在 {@code Runner} 那一层。适配器的职责只是把三个参数
     * 搬过去，顺便把两侧形状相同的回调桥接起来；它不该知道标记、也不该拼 shell。
     *
     * <h2>为什么两参数版本转调这里</h2>
     *
     * <p>让执行路径只有一条：有没有 {@code killScript} 是数据差异，不是两条逻辑。
     * 两参数版本等价于「调用方没有结束方式」，与接口默认方法给出的语义一致。
     *
     * <p>LineSink 的形状在两侧完全一致（{@code void line(String, boolean)}），
     * 但它们是两个互不相关的接口类型，所以这里仍然需要一个桥接对象；
     * sink 允许为 null，此时 SSH 侧「只收集不回调」（它自己也做 null 检查）。
     */
    @Override
    public Result runBashStreaming(String script, final LineSink sink, String killScript) {
        return new SshResultAdapter(
                delegate.runBashStreaming(script, bridged(sink), killScript));
    }

    /** 把插件侧的回调桥成 vmadapt 侧的回调；{@code sink} 为 null 时返回 null。 */
    private static SshTransport.LineSink bridged(final LineSink sink) {
        if (sink == null) {
            return null;
        }
        return new SshTransport.LineSink() {
            @Override
            public void line(String text, boolean isError) {
                sink.line(text, isError);
            }
        };
    }

    /**
     * 通道标签，返回 {@code user@host:port} 形式（例如
     * {@code ubuntu@192.168.1.100:22}）。
     *
     * <h2>为什么用这个而不是别的</h2>
     *
     * <p>{@link Transport#getDistro()} 在接口层面的契约是「我这句话在哪台机器上跑的」，
     * 消费者只用它拼提示文字（{@code EnvScanner} 会写成
     * 「WSL is not reachable (distro: ...)」让用户知道探的是哪一台）。
     * SSH 侧没有发行版，最贴近这个语义的就是连接目标本身。
     *
     * <p>直接复用 {@link SshTarget#toDisplay()} 而不是自己拼字符串：
     * 目标填了别名（{@code label}）时它会带上别名，人在多台机器之间切换时
     * 更容易认出来；而且「未填写主机」这类边界情况它已经处理过了，
     * 自己再拼一遍就会多出一份要维护的格式逻辑。
     *
     * @return 通道标签；host 未填时返回其中文占位形式，永不为 null
     */
    @Override
    public String getDistro() {
        return delegate.target().toDisplay();
    }

    /**
     * 另起一条独立 SSH 连接执行脚本，不等它结束。
     *
     * <h2>第二个参数被有意丢弃</h2>
     *
     * <p>接口第 2 个参数（通道标签）在 SSH 侧没有对应物：
     * {@code SshTransport} 的目标主机在构造时就固定了，一次运行期间不可能
     * 变成另一台机器。签名里保留它只是为了让 WSL 侧能表达「在哪里启动就在
     * 哪里杀掉」，对 SSH 而言它是无意义的冗余。
     * 与其把它硬塞进某处，不如显式忽略并在这里写清楚——见类注释第 2 条。
     *
     * <h2>为什么把 null 转成异常</h2>
     *
     * <p>{@code SshTransport.runDetached} 启动失败时返回 {@code null}，
     * 不抛异常。但 {@link Transport#runDetached} 的契约是「启动不了就
     * {@code throws IOException}」。若本类直接返回 null，调用方会以为
     * 拿到一个进程、对它调用方法时才 NPE，栈里也看不出真正原因
     * （真实原因可能是 ssh.exe 找不到、或 target 没填完）。
     * 在这里补抛异常，能让失败在<b>发生的位置</b>暴露。
     *
     * <p>这不影响任何现有调用方：唯一的调用点
     * {@code Runner.Job.cancel()} 本来就写在
     * {@code try { ... } catch (Exception ignored)} 里——
     * 取消失败时它选择回退到只杀本地客户端进程，这个行为保持不变。
     *
     * @param script     要在远端独立执行的脚本；null 或空串由底层返回 null
     * @param distroName 接口要求的通道标签；SSH 侧忽略
     * @return 已启动的进程，调用方可以选择等待或直接丢弃
     * @throws IOException 底层无法启动该连接（返回 null）时
     */
    @Override
    public Process runDetached(String script, String distroName) throws IOException {
        Process p = delegate.runDetached(script);
        if (p == null) {
            throw new IOException("Could not start a detached process over SSH: the connection "
                    + "parameters are incomplete or ssh.exe is unavailable (target "
                    + delegate.target().toDisplay() + ").");
        }
        return p;
    }

    @Override
    public String toString() {
        return "SshTransportAdapter[" + delegate.target().toDisplay() + "]";
    }

    // ------------------------------------------------------------------
    // 结果适配
    // ------------------------------------------------------------------

    /**
     * 把 {@code SshTransport.Result} 包装成 {@link Transport.Result}。
     *
     * <p>做成 {@code private static} 嵌套类而不是顶层的
     * {@code SshResultAdapter.java}：这个类型只在 SSH 适配器的返回值里出现，
     * 调用方拿到的一律是 {@link Transport.Result}，不会有人需要 import 它。
     * 藏在这里能少一个 public 类型，也少一份「这个类能不能被外面依赖」的疑问。
     *
     * <p>与 {@link WslResultAdapter} 一样，这是<b>视图</b>不是拷贝：
     * {@code ok()} 直接转发底层方法，绝不在这里重写「成功判据」，
     * 从而保证 WSL 与 SSH 两条路径对「成功」的定义永远一致。
     */
    private static final class SshResultAdapter implements Transport.Result {

        private final SshTransport.Result delegate;

        SshResultAdapter(SshTransport.Result delegate) {
            this.delegate = delegate;
        }

        @Override
        public int exitCode() {
            // delegate 为 null 时返回 -1，与「进程根本没起来」的约定一致。
            return delegate == null ? -1 : delegate.exitCode;
        }

        @Override
        public boolean timedOut() {
            return delegate != null && delegate.timedOut;
        }

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

        /** stdout 逐行内容，只读视图；理由同 {@link WslResultAdapter#stdout()}。 */
        @Override
        public List<String> stdout() {
            if (delegate == null) {
                return Collections.emptyList();
            }
            return Collections.unmodifiableList(delegate.stdout);
        }

        /** stderr 逐行内容，只读视图。 */
        @Override
        public List<String> stderr() {
            if (delegate == null) {
                return Collections.emptyList();
            }
            return Collections.unmodifiableList(delegate.stderr);
        }

        @Override
        public String toString() {
            return "SshResult[" + (delegate == null ? "null" : delegate.toString()) + "]";
        }
    }
}

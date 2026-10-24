package bioenv.core;

import java.io.IOException;
import java.util.List;

/**
 * 「在这台机器之外跑一段 bash」这件事的抽象接缝。
 *
 * <h2>为什么需要这个接口</h2>
 *
 * <p>插件最早只会通过 WSL 执行命令，于是 {@link WslBridge} 同时承担了两件事：
 * <b>怎么把命令送到 Linux 侧</b>（wsl.exe、-d 参数、脚本走 stdin），以及
 * <b>命令跑完的结果长什么样</b>（exitCode / stdout / stderr / 是否超时）。
 * 消费者（EnvScanner、EnvRunner、Runner、EnvFingerprint、ToolRegistry）只关心后者，
 * 却被迫依赖前者的具体类型 {@code WslBridge}，连带把它当参数一路传递。
 *
 * <p>当项目要把执行通道扩展到 SSH 虚拟机时，这个耦合的代价就显现了：
 * 已经有一份写完并验收过的 SSH 实现（{@code vmadapt.core.SshTransport}），
 * 它和 WslBridge <b>同构</b>——同样的 runBashCapture / runBashStreaming、
 * 同样的逐行回调、同样的结果四元组——但两者没有任何共同父类型。
 * 此时只有两条路：把消费者按通道各抄一份（逻辑分叉，日后每个 bug 要修两遍），
 * 或者抽出一个两边都能接上的接缝。本接口就是后者。
 *
 * <h2>为什么是接口，而不是让某个 Result 继承另一个</h2>
 *
 * <p>Java 没有结构化类型，两个碰巧长得一样的 {@code final static class} 之间
 * 不会自动产生任何子类型关系。而 {@code WslBridge.Result} 与
 * {@code vmadapt.core.SshTransport.Result} 都是 {@code final} 类，
 * 既无法相互继承，也无法被第三方包装成子类。所以只有「接口 + 各自适配」
 * 这一条路：{@link Result} 用接口而不是复用 concrete 类，是编译规则决定的，
 * 不是风格偏好。
 *
 * <h2>契约（所有实现必须一致）</h2>
 * <ul>
 *   <li>本接口的方法是 <b>阻塞</b> 的：跑完才返回，调用方应在后台线程调用。
 *       唯一的例外是 {@link #runDetached}，它只负责把进程拉起来。</li>
 *   <li>{@link Result} 是 <b>只读</b> 快照：产生它的传输通道已经把输出读完并
 *       剥离过终端控制码，消费者不需要再处理流。</li>
 *   <li>除非另有说明，任何实现都不应因为「脚本执行失败」而抛异常——
 *       失败要体现在 {@link Result#exitCode()} 或 {@link Result#stderr()} 里。
 *       把失败做成返回值的理由：界面上的「测试连接」随时可能被点到，
 *       抛异常会让按钮回调直接崩掉，而返回一条可展示的错误更合适。</li>
 *   <li>超时必须被实现，且超时后 {@link Result#timedOut()} 为 true、
 *       {@link Result#exitCode()} 保持 -1（进程是被强制杀掉的，没有真实退出码）。
 *       消费者靠这一对组合区分「跑挂了」和「没跑完」。</li>
 * </ul>
 *
 * <h2>谁实现了它</h2>
 * <ul>
 *   <li>{@link WslBridge} —— 直接实现（WSL 是本机 Linux 侧的默认通道）。</li>
 *   <li>{@link SshTransportAdapter} —— 包装 {@code vmadapt.core.SshTransport}，
 *       用于远程 Linux 虚拟机。注意：被包装的那个类属于只读复用的 vmadapt 模块，
 *       我们不能改它，所以用适配器而不是让它直接 implements。</li>
 * </ul>
 *
 * @see WslResultAdapter
 * @see SshTransportAdapter
 */
public interface Transport {

    /**
     * 逐行输出回调：命令还在跑的时候，每来一行就调一次。
     *
     * <p>为什么必须有它：分析任务动辄跑几十分钟，用户需要看到进度。若等进程
     * 结束再一次性吐出，界面在某些工具（conda/mamba 之类）上会长时间一片空白，
     * 用户会以为程序卡死。
     *
     * <p>形状与 {@code WslBridge.LineSink}、{@code vmadapt.core.SshTransport.LineSink}
     * 完全一致，因此两边都能直接接上，不需要再包一层。
     */
    interface LineSink {
        /**
         * @param text    一行输出（已剥离 ANSI 控制码，不含行尾换行）
         * @param isError true 表示这行来自 stderr
         */
        void line(String text, boolean isError);
    }

    /**
     * 一次命令执行的结果快照。
     *
     * <h2>为什么是全方法、没有 public 字段</h2>
     *
     * <p>WSL 与 SSH 两侧的原始结果对象都是 public 字段（{@code r.exitCode}、
     * {@code r.stdout}）。接口无法声明字段，所以这里一律用访问器——这正是
     * 接缝能把两种表示统一起来的原因。
     *
     * <p>这也让 {@code stdout()} / {@code stderr()} 顺带解决了另一件事：
     * 原始实现把 {@code List} 直接暴露成 public final 字段，调用方可以
     * {@code add()} 或 {@code clear()} 掉别人的结果。接口方法只承诺「读」，
     * 实现可以返回不可修改视图。
     *
     * <h2>成功判据只有一个</h2>
     *
     * <p>{@link #ok()} 是唯一的成功判据：{@code exitCode == 0 && !timedOut}。
     * 不要用 {@code exitCode == 0} 单独判断——超时被杀的进程退出码可能是 0
     * 也可能是 -1，不可靠。
     */
    interface Result {

        /**
         * 进程退出码；-1 表示进程根本没起来，或因为超时被杀。
         */
        int exitCode();

        /**
         * 是否因为超时被强制终止。
         *
         * <p>命名与两侧原始实现的字段名（{@code timedOut}）保持一致，
         * 而不是 JavaBean 惯用的 {@code isTimedOut()}：消费者原来写的是
         * {@code r.timedOut}，字段转方法时 {@code r.timedOut()} 是纯机械替换，
         * 少一次「这个 is 前缀对应哪个字段」的翻译，也就少一处写错的余地。
         */
        boolean timedOut();

        /**
         * 退出码为 0 且未超时。
         */
        boolean ok();

        /** stdout 全文（各行用 {@code \n} 连接，无末尾换行）。 */
        String stdoutText();

        /** stderr 全文（各行用 {@code \n} 连接，无末尾换行）。 */
        String stderrText();

        /**
         * stdout 的逐行内容。
         *
         * <p>返回空列表而不是 null：消费者普遍直接写
         * {@code for (String line : r.stdout())}，返回 null 会把「命令没有输出」
         * 这种正常情况变成 NullPointerException。
         */
        List<String> stdout();

        /**
         * stderr 的逐行内容。
         *
         * @see #stdout()
         */
        List<String> stderr();
    }

    /**
     * 执行一段 bash 脚本，阻塞到结束，并收集全部输出。
     *
     * <p>只适合短探测类命令（{@link WslBridge} 侧的内部超时是 25 秒，
     * SSH 侧是 {@code runTimeoutSeconds}）。长时间任务请用
     * {@link #runBashStreaming}，否则用户全程看不到任何进度。
     *
     * <p>stdout 与 stderr 分开收集，因为调用方需要区分「工具的正常输出」和
     * 「工具抱怨了什么」——例如 SSHD 的连接失败写在 stderr，而 conda 的环境
     * 列表写在 stdout，两者的处理方式完全不同。
     *
     * @param script bash 脚本内容；脚本通过 stdin 送给远端 bash，因此不受
     *               Windows 命令行长度限制，也不必处理引号与中文路径
     * @return 结果快照，永不为 null
     */
    Result runBashCapture(String script);

    /**
     * 执行一段 bash 脚本，并把输出逐行实时回调给 {@code sink}。
     *
     * <p>本方法阻塞到进程结束，调用方应在后台线程调用它。
     *
     * @param script bash 脚本内容
     * @param sink   行回调；允许为 null，此时只收集不回调
     * @return 结果快照，永不为 null；即使脚本失败，输出也已经在 sink 里出现过
     */
    Result runBashStreaming(String script, LineSink sink);

    /**
     * 同 {@link #runBashStreaming(String, LineSink)}，多带一段「结束这次运行的远端命令」。
     *
     * <h2>为什么要有它</h2>
     *
     * <p>超时之后光把本机的客户端进程杀掉是不够的：WSL/SSH 那一侧真正干活的是
     * 另一台机器上的进程树，杀客户端不会碰到它。要让远端停下，得<b>再开一条通道</b>
     * 把一条远程命令送过去（与「用户点停止」用的是同一套机制）。
     *
     * <p>这条命令由调用方生成，不是传输层拼的：它必须按「本次运行」的标记来匹配
     * 进程（{@code CommandBuilder.killScript(marker)}），而标记属于调用方，
     * 传输层既不知道也不该知道它长什么样。所以这里传的是<b>脚本文本</b>，
     * 不是标记：vmadapt 模块不允许反向依赖插件模块（根目录 build.ps1 先编译
     * vmadapt，编译它时 classpath 是空的），传字符串是唯一不破坏这条依赖的方向。
     *
     * <h2>默认实现：什么都不做</h2>
     *
     * <p>默认实现忽略 {@code killScript}，原样转调两参数版本。这样所有既有实现
     * （{@link WslBridge}、测试里的假传输）一行都不用改，而「到不了远端、
     * 或者本来就不需要」的通道仍然保持原来的行为——它们只要如实说明远端可能还在跑。
     *
     * @param script     要在远端执行的 bash 脚本
     * @param sink       行回调；允许为 null
     * @param killScript 结束本次运行的远端命令；null 或空串表示调用方没有可用的
     *                   结束方式（此时实现必须按「远端可能仍在运行」措辞）
     * @return 结果快照，永不为 null
     */
    default Result runBashStreaming(String script, LineSink sink, String killScript) {
        return runBashStreaming(script, sink);
    }

    /**
     * 当前执行通道的人类可读标签，用于界面显示与错误提示。
     *
     * <h2>为什么这个方法必须存在，尽管它「不属于」传输语义</h2>
     *
     * <p>它原本是 {@link WslBridge#getDistro()}，语义是「用哪个 WSL 发行版」。
     * 但消费者其实只把它当作 <b>「我这句话是在哪台机器上跑的」</b> 来用：
     * {@code EnvScanner} 在报告里写「WSL is not reachable (distro: Ubuntu)」，
     * 让用户知道刚才探的是哪一台。换成 SSH 通道时，同一个位置需要的是
     * 「用户@主机:端口」，而不是发行版名。
     *
     * <p>所以本方法在接口层面的契约是「一个用于显示的通道标签」，
     * 而不是「发行版名」：WSL 实现返回发行版名（以便用户去改那个下拉框），
     * SSH 实现返回 {@code user@host:port}。这样 <b>调用方不必写
     * {@code if (transport instanceof WslBridge)}</b> 就能给出正确的提示文字——
     * 那才是最需要避免的分叉。
     *
     * @return 标签；允许返回 null 或空串（例如还没选发行版、还没填主机），
     *         调用方必须按「可能为空」处理
     */
    String getDistro();

    /**
     * 另起一条独立通道执行脚本，不等它结束、不读它的输出。
     *
     * <h2>为什么需要它</h2>
     *
     * <p>用途是「取消」。用户点停止时，必须在远端把正在跑的进程杀掉，
     * 而这个动作 <b>不能</b> 复用正在跑任务的那条通道：WSL 侧那个 wsl.exe
     * 正忙着跑任务，命令发不进去；SSH 侧同理，原来那条连接的 stdin 已经
     * 被脚本占用了。所以「断开」必须发生在一条新通道上。
     *
     * <p>输出直接丢弃。kill 脚本没什么有用的话要说，而调用方正在从自己的
     * wait 里返回，没地方接收。不读管道还有一个硬理由：没人读的管道写满后
     * 会让远端脚本自己阻塞，那就永远杀不掉了。
     *
     * <h2>签名为什么是这样</h2>
     *
     * <ul>
     *   <li>带 {@code distroName} 参数（即使 WSL 实现已有 {@code distro} 字段）：
     *       取消发生在一瞬间，而用户可能刚刚在界面上换了发行版。调用方在
     *       <b>启动任务时</b> 就把当时的发行版记下来传进来，才能保证「在哪里
     *       启动，就在哪里杀掉」，而不是杀到另一个发行版里去。</li>
     *   <li>声明 {@code throws IOException}：WSL 侧原始签名就有它。更关键的是
     *       取消失败的原因（找不到 wsl.exe、通道已关闭）应该能被区分出来，
     *       而不是一律吞成 null。现有的唯一调用点
     *       （{@code Runner.Job.cancel()}）本来就写在
     *       {@code try { ... } catch (Exception ignored)} 里，所以这个声明的
     *       存在不会强制任何调用方改变行为。</li>
     * </ul>
     *
     * @param script     要在远端独立执行的脚本；null 或空串返回 null
     * @param distroName 通道标签（WSL 传发行版名，SSH 传「用户@主机:端口」）；
     *                   允许为 null，表示用实现自己的默认通道
     * @return 已启动的进程，调用方可以选择等待或直接丢弃
     * @throws IOException 无法启动该通道时
     */
    Process runDetached(String script, String distroName) throws IOException;
}

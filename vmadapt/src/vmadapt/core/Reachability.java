package vmadapt.core;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * 「远端 sshd 在不在听」这个问题的答案，用一次 TCP 连接取得。
 *
 * <h2>为什么不直接跑 ssh</h2>
 *
 * <p>自动连接之前需要先知道虚拟机开没开机。用 ssh 去问，失败时要等完整握手
 * 加可能的多次重试，用户看到的就是「插件一打开就卡住十几秒」；而一次 TCP
 * 连接几毫秒就能给出答案，失败也只需等到设定的上限。
 *
 * <h2>为什么不用 ping</h2>
 *
 * <p>ICMP 经常被防火墙整段挡掉，主机明明活着却 ping 不通，会误报成「关机」。
 * 而且我们真正要问的并不是「主机在不在」，而是「<b>sshd 在不在听</b>」——
 * 后者才是插件能不能干活的前提，TCP 连接问的正好是这件事。
 *
 * <h2>为什么要分几种失败</h2>
 *
 * <p>「连不上」有好几种原因，对应的下一步动作完全不同：端口被拒绝说明主机活着
 * 但 sshd 没起来，等一会儿重试就行；超时多半是关机或 IP 变了，得去开机或改地址；
 * 名字解析不了则是地址写错了。把它们混成一句「连不上」，用户就只能瞎猜。
 */
public final class Reachability {

    /** 默认等待上限。够覆盖一次局域网连接，又不至于让人等出「卡住」的感觉。 */
    public static final int DEFAULT_TIMEOUT_MS = 5000;

    /** 探测结果。 */
    public enum State {
        /** TCP 连上了：主机在，sshd 在听。 */
        REACHABLE,
        /**
         * 连接被明确拒绝：主机是活的，但那个端口没有服务在听。
         *
         * <p>最常见的原因是虚拟机刚开机、sshd 还没启动完，等几秒重试即可。
         */
        REFUSED,
        /**
         * 等待超时，对端没有应答：关机、还没启动完、被防火墙丢弃，或 IP 变了。
         */
        TIMEOUT,
        /** 主机名解析不了，或到该地址没有路由。 */
        UNRESOLVED
    }

    private Reachability() {
    }

    /** 探测一个连接目标。 */
    public static State check(SshTarget target) {
        return check(target, DEFAULT_TIMEOUT_MS);
    }

    /** 探测一个连接目标，并指定等待上限。 */
    public static State check(SshTarget target, int timeoutMs) {
        if (target == null || target.host == null || target.host.trim().isEmpty()) {
            return State.UNRESOLVED;
        }
        int port = target.port > 0 ? target.port : SshTarget.DEFAULT_PORT;
        return check(target.host.trim(), port, timeoutMs);
    }

    /**
     * 探测一个地址。
     *
     * @param host      主机名或 IP
     * @param port      端口
     * @param timeoutMs 等待上限，毫秒
     * @return 探测结果，永不为 null
     */
    public static State check(String host, int port, int timeoutMs) {
        if (host == null || host.trim().isEmpty() || port <= 0) {
            return State.UNRESOLVED;
        }
        int t = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host.trim(), port), t);
            return State.REACHABLE;
        } catch (Exception e) {
            return classify(e);
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
                // 已经拿到答案了，关不关得上都不影响结论
            }
        }
    }

    /**
     * 把连接异常翻成结论。
     *
     * <p>独立成一个方法是为了能直接测：超时那一路需要一个「不回应」的地址才跑得出来，
     * 而那样的地址在不同网络里表现不一，测起来会飘。类型判定本身是确定的。
     */
    public static State classify(Exception e) {
        if (e instanceof SocketTimeoutException) {
            return State.TIMEOUT;
        }
        if (e instanceof UnknownHostException) {
            return State.UNRESOLVED;
        }
        if (e instanceof NoRouteToHostException) {
            return State.UNRESOLVED;
        }
        if (e instanceof ConnectException) {
            // 「Connection refused」是明确的拒绝；同一类型也可能包裹超时文案，
            // 所以再看一眼消息，免得把「没应答」说成「服务没起来」。
            String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (m.contains("timed out") || m.contains("timeout")) {
                return State.TIMEOUT;
            }
            return State.REFUSED;
        }
        // 其余一律按「没应答」处理：这是最保守的说法，不会让用户以为主机活着。
        return State.TIMEOUT;
    }

    /** 该结论是否说明可以继续连接。 */
    public static boolean canProceed(State s) {
        return s == State.REACHABLE;
    }
}

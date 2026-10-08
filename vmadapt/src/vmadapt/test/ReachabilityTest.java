package vmadapt.test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import vmadapt.core.Reachability;
import vmadapt.core.SshTarget;

/**
 * 「虚拟机开没开机」判定的测试。
 *
 * <p>回归的是自动连接的前提：插件要在打开面板时自动去连一台机器，就必须先知道
 * 它是不是活着。不先问就直接 ssh，虚拟机关机时要等完整握手加重试，用户看到的是
 * 「插件一打开就卡住十几秒」。
 *
 * <p>可达与拒绝两种结果是确定的，用真实的本地套接字测；超时那一路需要一个
 * 「不回应」的地址，而那样的地址在不同网络里表现不一，所以改成直接测类型判定
 * {@link Reachability#classify}，把不确定性挡在测试之外。
 */
public final class ReachabilityTest {

    private static int checks;
    private static int failures;

    private ReachabilityTest() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== ReachabilityTest ===");

        reachable();
        refused();
        classification();
        guardsAll();
        boundedTime();

        System.out.println(failures == 0
                ? "PASS " + checks + " checks"
                : "FAIL: " + failures + " of " + checks + " checks failed");
        if (failures > 0) {
            System.exit(1);
        }
    }

    /** 有东西在听 → 可达。 */
    private static void reachable() throws Exception {
        ServerSocket server = new ServerSocket(0);
        int port = server.getLocalPort();
        try {
            Reachability.State s = Reachability.check("127.0.0.1", port, 3000);
            check("有服务在听 → REACHABLE", s == Reachability.State.REACHABLE);
            check("REACHABLE 可以继续连接", Reachability.canProceed(s));

            // 走 SshTarget 这条重载也要一致。
            SshTarget t = new SshTarget("127.0.0.1", port, "u", null);
            check("SshTarget 重载同样判定为可达",
                    Reachability.check(t, 3000) == Reachability.State.REACHABLE);
        } finally {
            server.close();
        }
    }

    /** 端口没人听 → 拒绝（主机活着，sshd 没起来）。 */
    private static void refused() throws Exception {
        int port = freePort();
        Reachability.State s = Reachability.check("127.0.0.1", port, 3000);
        check("端口没人听 → REFUSED（实际 " + s + "）",
                s == Reachability.State.REFUSED);
        check("REFUSED 不能继续连接", !Reachability.canProceed(s));
    }

    /** 异常到结论的映射，逐条钉住。 */
    private static void classification() {
        check("SocketTimeoutException → TIMEOUT",
                Reachability.classify(new SocketTimeoutException("Read timed out"))
                        == Reachability.State.TIMEOUT);
        check("UnknownHostException → UNRESOLVED",
                Reachability.classify(new UnknownHostException("nope"))
                        == Reachability.State.UNRESOLVED);
        check("NoRouteToHostException → UNRESOLVED",
                Reachability.classify(new NoRouteToHostException("no route"))
                        == Reachability.State.UNRESOLVED);
        check("Connection refused → REFUSED",
                Reachability.classify(new ConnectException("Connection refused"))
                        == Reachability.State.REFUSED);
        // 同一类型也可能包着超时文案，不能一律说成「服务没起来」。
        check("ConnectException 包着超时文案 → TIMEOUT",
                Reachability.classify(new ConnectException("connect timed out"))
                        == Reachability.State.TIMEOUT);
        check("未知异常 → 按 TIMEOUT 处理（最保守）",
                Reachability.classify(new IOException("whatever"))
                        == Reachability.State.TIMEOUT);
    }

    /** 参数不合法时不能抛异常，也不能说成可达。 */
    private static void guardsAll() {
        check("null 目标 → UNRESOLVED",
                Reachability.check((SshTarget) null) == Reachability.State.UNRESOLVED);
        check("空主机 → UNRESOLVED",
                Reachability.check("", 22, 1000) == Reachability.State.UNRESOLVED);
        check("端口为 0 → UNRESOLVED",
                Reachability.check("127.0.0.1", 0, 1000)
                        == Reachability.State.UNRESOLVED);
        check("host 为空的 SshTarget → UNRESOLVED",
                Reachability.check(new SshTarget("", 22, "u", null))
                        == Reachability.State.UNRESOLVED);
        check("canProceed(null) 不会抛",
                !Reachability.canProceed(null));
    }

    /**
     * 上限必须真的起作用：对端不回应时也要在时限附近返回，而不是无限等下去。
     *
     * <p>用一个不可路由的地址。不同网络下它可能立刻报「无路由」也可能静默丢弃，
     * 所以只断言两件确定的事：不会说成可达，且不会远超上限。
     */
    private static void boundedTime() {
        long t0 = System.currentTimeMillis();
        Reachability.State s = Reachability.check("10.255.255.1", 22, 1000);
        long ms = System.currentTimeMillis() - t0;
        check("不回应时不会说成可达", s != Reachability.State.REACHABLE);
        check("在时限附近返回（实际 " + ms + "ms）", ms < 8000);
        System.out.println("  （不可路由地址的结论：" + s + "，耗时 " + ms + "ms）");
    }

    private static int freePort() throws IOException {
        ServerSocket s = new ServerSocket(0);
        int p = s.getLocalPort();
        s.close();
        // 端口刚释放，通常不会立刻被别人占走，这里够用。
        return p;
    }

    private static void check(String label, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("  FAIL: " + label);
        }
    }
}

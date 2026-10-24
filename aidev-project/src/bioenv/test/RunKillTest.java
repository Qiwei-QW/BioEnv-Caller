package bioenv.test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import bioenv.core.CommandBuilder;
import bioenv.core.Runner;
import bioenv.core.Tool;
import bioenv.core.Transport;

/**
 * 超时之后远端进程树能不能真的被叫停：验证「结束命令」一路传到了传输层。
 *
 * <p>回归的是 v1.0.1 做不到的那一步：一次运行的标记（{@code BIOENV_JOB}）只存在于
 * 插件模块（{@code CommandBuilder.buildScript} 把它写进脚本，
 * {@code CommandBuilder.killScript(marker)} 按它杀进程），而通道契约
 * {@code runBashStreaming(script, sink)} 从来没有携带过它，vmadapt 又不能再 import
 * 插件代码。于是 SSH 超时只能杀本机的 ssh.exe，远端那条 {@code conda run} 进程树
 * 继续跑，用户却可能以为已经停了。
 *
 * <p>修法是把<b>生成好的结束脚本文本</b>顺着通道传下去（接口默认方法保证既有实现
 * 不用改）。这里用假传输把这件事钉住：运行时必须走三参数重载、脚本必须是
 * {@code CommandBuilder.killScript(marker)} 那一份、并且一次都不该走两参数重载。
 * 第二条断言守的是「旧实现（只实现两参数的假传输/通道）仍然可以工作」——
 * 接口的默认方法必须回退到两参数实现，而不是要求所有实现都改签名。
 *
 * <p>这是一条纯逻辑测试：不需要 WSL、也不需要一台真实的 sshd。
 *
 * <p>Run: {@code java -cp &lt;classes&gt; bioenv.test.RunKillTest}
 */
public final class RunKillTest {

    private static int pass;
    private static int fail;

    /** 运行期回调：只记录，不做别的。 */
    private static final Transport.LineSink SINK = new Transport.LineSink() {
        @Override
        public void line(String text, boolean isError) {
        }
    };

    private RunKillTest() {
    }

    public static void main(String[] args) {
        body();
        line("");
        line("  passed: " + pass + "   failed: " + fail);
        if (fail > 0) {
            line("");
            line("FAILURES PRESENT");
            System.exit(1);
        }
    }

    private static void body() {
        line("=== Stopping the far side when a run times out ===");
        line("");

        Tool tool = new Tool("orthofinder", "orthofinder_env", "/envs/orthofinder");

        // --- 有 job：三参数重载必须收到这一份结束脚本 -------------------------
        line("[a run hands its kill script to the transport]");
        Fake fake = new Fake();
        Runner.Job job = new Runner.Job();
        Runner runner = new Runner(fake);
        Runner.RunResult res = runner.runTyped(tool, "echo hi", null, job, SINK);

        check("the run completed normally", res != null && res.ok());
        check("the 3-arg overload was called exactly once", fake.threeArg == 1);
        check("the 2-arg overload was not called at all", fake.twoArg == 0);
        check("the streamed line still reached the caller",
                fake.linesForwarded > 0);

        String kill = fake.killScripts.isEmpty() ? null : fake.killScripts.get(0);
        check("a kill script was passed", kill != null && !kill.isEmpty());
        check("it names this run's marker", kill != null && kill.contains(job.marker()));
        // 相等而不是"包含"：结束脚本只能由 CommandBuilder 生成一份，Runner 不许
        // 再写第二种拼法，否则两处会各自漂移。
        check("it is exactly CommandBuilder.killScript(marker)",
                kill != null && kill.equals(CommandBuilder.killScript(job.marker())));
        check("it is the marker-based shape (reads each process's environment)",
                kill != null && kill.contains("BIOENV_JOB")
                        && kill.contains("/proc/"));
        check("it asks politely first", kill != null && kill.contains("kill -TERM"));
        check("and then insists", kill != null && kill.contains("kill -KILL"));
        check("it does not fail the caller", kill != null && kill.trim().endsWith("exit 0"));
        line("");

        // --- 没有 job：仍然走三参数重载，但结束方式为 null --------------------
        line("[a run with no job passes no kill script, but still uses the 3-arg call]");
        Fake noJob = new Fake();
        Runner runnerNoJob = new Runner(noJob);
        Runner.RunResult res2 = runnerNoJob.runTyped(tool, "echo hi", null,
                (Runner.Job) null, SINK);
        check("the run completed normally", res2 != null && res2.ok());
        check("the 3-arg overload was used", noJob.threeArg == 1);
        check("the 2-arg overload was not used", noJob.twoArg == 0);
        check("it passed null, not an invented kill script",
                noJob.killScripts.size() == 1 && noJob.killScripts.get(0) == null);
        line("");

        // --- 旧实现（只实现两参数）仍然可用：靠接口的默认方法 ------------------
        line("[an implementation that only knows the 2-arg method still works]");
        TwoArgOnly old = new TwoArgOnly();
        Transport asInterface = old;
        Transport.Result r = asInterface.runBashStreaming("echo hi", SINK, "kill-script-text");
        check("the default method fell back to the 2-arg implementation", old.twoArg == 1);
        check("the script was passed through unchanged", "echo hi".equals(old.lastScript));
        check("a result came back", r != null && r.ok());
        line("");

        line("Runs carry the far-side stop command; old channels keep working unchanged.");
    }

    /**
     * 记录走了哪个重载、拿到什么脚本的假传输。
     *
     * <p>三参数版本还要把一行输出交给调用方的 sink：这条路径除了多一个参数，
     * 流式行为必须和以前一模一样。
     */
    private static final class Fake implements Transport {

        final List<String> killScripts = new ArrayList<String>();
        int twoArg;
        int threeArg;
        int linesForwarded;

        @Override
        public Result runBashCapture(String script) {
            return new Res();
        }

        @Override
        public Result runBashStreaming(String script, LineSink sink) {
            twoArg++;
            return new Res();
        }

        @Override
        public Result runBashStreaming(String script, LineSink sink, String killScript) {
            threeArg++;
            killScripts.add(killScript);
            if (sink != null) {
                sink.line("from-fake", false);
                linesForwarded++;
            }
            return new Res();
        }

        @Override
        public String getDistro() {
            return "fake-distro";
        }

        @Override
        public Process runDetached(String script, String distroName) throws IOException {
            return null;
        }
    }

    /** 只实现两参数版本的假传输：接口默认方法必须回退到它。 */
    private static final class TwoArgOnly implements Transport {

        int twoArg;
        String lastScript;

        @Override
        public Result runBashCapture(String script) {
            return new Res();
        }

        @Override
        public Result runBashStreaming(String script, LineSink sink) {
            twoArg++;
            lastScript = script;
            return new Res();
        }

        @Override
        public String getDistro() {
            return "two-arg-only";
        }

        @Override
        public Process runDetached(String script, String distroName) {
            return null;
        }
    }

    /** 只读结果快照：够 Runner 用，不模拟任何真实进程。 */
    private static final class Res implements Transport.Result {

        @Override
        public int exitCode() {
            return 0;
        }

        @Override
        public boolean timedOut() {
            return false;
        }

        @Override
        public boolean ok() {
            return true;
        }

        @Override
        public String stdoutText() {
            return "";
        }

        @Override
        public String stderrText() {
            return "";
        }

        @Override
        public List<String> stdout() {
            return new ArrayList<String>();
        }

        @Override
        public List<String> stderr() {
            return new ArrayList<String>();
        }
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            pass++;
        } else {
            fail++;
        }
        line("  " + (ok ? "PASS" : "FAIL") + "  " + label);
    }

    private static void line(String s) {
        System.out.println(s);
    }
}

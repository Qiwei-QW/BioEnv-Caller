package bioenv.test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import bioenv.core.EnvRunner;
import bioenv.core.RouteResolver;
import bioenv.core.Schema;
import bioenv.core.SchemaBuilder;
import bioenv.core.Transport;

/**
 * 帮助探测走哪条路的测试。
 *
 * <p>回归的是一个「代码写好了却没接上」的缺陷：{@link RouteResolver} 一直存在，
 * 能把探测从 {@code conda run}（约 2100 ms）切到直接执行 {@code <env>/bin/<cmd>}
 * （约 4 ms），但<b>全工程没有任何地方实例化它</b>。于是每次帮助探测都走慢路，
 * 而一个帮助信息稀疏的工具要试六种调用形式——十二秒以上，正是用户说的
 * 「某些工具帮助文档读取非常慢」。
 *
 * <p>测试用一个假的 {@link Transport} 记录真正发出去的脚本，因此能直接断言
 * 「走的是哪条路」，而不必真的装一个 conda 环境。
 */
public final class ProbeRouteTest {

    private static int checks;
    private static int failures;

    private ProbeRouteTest() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== ProbeRouteTest ===");

        // 1. 普通命令：应当走快路。
        Fake fake = new Fake();
        fake.directOutput = "Usage: samtools [options]\n  -b  BAM input\n  -o  output\n";
        Transport t = fake.build();
        EnvRunner er = new EnvRunner(t, "/home/u/miniconda3");
        SchemaBuilder sb = new SchemaBuilder(er);
        Schema s = sb.build("/home/u/miniconda3/envs/tools", "samtools");

        check("拿得到 schema", s != null);
        check("真的走了快路（脚本里有 <env>/bin/samtools）",
                fake.sawScriptContaining("/bin/samtools"));
        check("帮助探测没有走 conda run",
                !fake.sawScriptContaining("conda run"));

        // 2. 脚本类命令：快路返回「环境缺失」，必须自动退回 conda。
        Fake scripty = new Fake();
        scripty.directOutput = "Traceback (most recent call last):\n"
                + "ModuleNotFoundError: No module named 'x'\n";
        scripty.condaOutput = "Usage: scripty [options]\n  -i  input\n  -n  threads\n";
        EnvRunner er2 = new EnvRunner(scripty.build(), "/home/u/miniconda3");
        Schema s2 = new SchemaBuilder(er2).build("/home/u/miniconda3/envs/tools", "scripty");
        check("脚本类命令仍然拿得到 schema", s2 != null);
        check("脚本类命令退回了 conda run",
                scripty.sawScriptContaining("conda run"));
        check("退回后仍拿到了帮助内容",
                s2 != null && s2.rawHelp.contains("Usage: scripty"));

        // 3. 环境里根本没有这个可执行文件：没有快路可走，直接用 conda。
        Fake missing = new Fake();
        missing.hasBinary = false;
        missing.condaOutput = "Usage: ghost [options]\n  -a  alpha\n";
        EnvRunner er3 = new EnvRunner(missing.build(), "/home/u/miniconda3");
        Schema s3 = new SchemaBuilder(er3).build("/home/u/miniconda3/envs/tools", "ghost");
        check("不存在的命令直接走 conda", missing.sawScriptContaining("conda run"));
        check("不存在的命令仍能出 schema", s3 != null);

        // 4. resolver 的判定要记住：同一个命令不该被反复试探。
        Fake repeat = new Fake();
        repeat.directOutput = "Usage: one [options]\n  -a  alpha\n  -b  beta\n";
        EnvRunner er4 = new EnvRunner(repeat.build(), "/home/u/miniconda3");
        RouteResolver rr = new RouteResolver(er4);
        RouteResolver.Route first = rr.routeFor("/p", "one");
        int afterFirst = repeat.scripts.size();
        RouteResolver.Route second = rr.routeFor("/p", "one");
        check("第一次判定为快路", first == RouteResolver.Route.DIRECT);
        check("第二次直接复用判定（不再试探）",
                second == RouteResolver.Route.DIRECT
                        && repeat.scripts.size() == afterFirst);
        check("判定数统计正确", rr.decidedCount() == 1);

        // 5. 快路在运行期失败时要当场退回并改记，而不是把工具当成没参数。
        Fake flaky = new Fake();
        flaky.directOutput = "Usage: flaky [options]\n  -a  alpha\n";
        flaky.condaOutput = "FALLBACK Usage: flaky [options]\n  -b  beta\n";
        flaky.failDirectAfterFirst = true;
        EnvRunner er5 = new EnvRunner(flaky.build(), "/home/u/miniconda3");
        RouteResolver rr2 = new RouteResolver(er5);
        String out = rr2.run("/p", "flaky --help");
        check("运行期快路失败后退回 conda 并拿到输出",
                out != null && out.contains("FALLBACK"));
        check("退回后判定被改成 conda",
                rr2.knownRoute("/p", "flaky") == RouteResolver.Route.CONDA);

        System.out.println(failures == 0
                ? "PASS " + checks + " checks"
                : "FAIL: " + failures + " of " + checks + " checks failed");
        if (failures > 0) {
            System.exit(1);
        }
    }

    /**
     * 假传输：记录收到的每一段脚本，按脚本形态返回对应的假输出。
     *
     * <p>「有没有走快路」这件事只有看真正发出去的脚本才能确定，所以断言全部
     * 建立在 {@link #scripts} 上，而不是建立在实现内部的字段上。
     */
    private static final class Fake {

        final List<String> scripts = new ArrayList<String>();
        String directOutput = "";
        String condaOutput = "";
        boolean hasBinary = true;
        /** 第一次快路正常、之后失败，用来验证运行期退回。 */
        boolean failDirectAfterFirst;
        private int directCalls;

        Transport build() {
            return new Transport() {
                @Override
                public Result runBashCapture(String script) {
                    return run(script);
                }

                @Override
                public Result runBashStreaming(String script, LineSink sink) {
                    return run(script);
                }

                @Override
                public String getDistro() {
                    return "Fake-1.0";
                }

                @Override
                public Process runDetached(String script, String distroName)
                        throws IOException {
                    return null;
                }
            };
        }

        private Transport.Result run(String script) {
            scripts.add(script);
            String out;
            if (script.contains("test -x ")) {
                out = hasBinary ? "YES" : "NO";
            } else if (script.contains("conda run")) {
                out = condaOutput;
            } else {
                directCalls++;
                if (failDirectAfterFirst && directCalls > 1) {
                    out = "ModuleNotFoundError: No module named 'x'";
                } else {
                    out = directOutput;
                }
            }
            return new Res(out);
        }

        boolean sawScriptContaining(String needle) {
            for (String s : scripts) {
                if (s.contains(needle)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class Res implements Transport.Result {
        private final String text;

        Res(String text) {
            this.text = text == null ? "" : text;
        }

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
            return text;
        }

        @Override
        public String stderrText() {
            return "";
        }

        @Override
        public List<String> stdout() {
            List<String> out = new ArrayList<String>();
            for (String line : text.split("\n", -1)) {
                out.add(line);
            }
            return out;
        }

        @Override
        public List<String> stderr() {
            return new ArrayList<String>();
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

package bioenv.test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import bioenv.core.EnvScanner;
import bioenv.core.Transport;

/**
 * 扫描脚本的等待上限测试。
 *
 * <p>回归的是「扫描会没有上限地等下去」：发现过程里有两条命令可能很慢——
 * {@code find / -maxdepth 5}（会跨过 /mnt/c 和网络挂载）与 {@code conda env list}
 * （会等 conda 的包缓存锁）。它们以前既没有 {@code timeout}，传输层那个 25 秒超时
 * 也够不到（读取先跑到了 EOF），于是用户点了「扫描」之后只能等或重启。
 *
 * <p>测试用假的 {@link Transport} 记录真正发出去的脚本（与 ProbeRouteTest 同一手法），
 * 因此不需要 WSL，也不需要装 conda：断言全部建立在「发出去的脚本里有没有 timeout」
 * 上，而不是实现内部的字段上。
 *
 * <p>Run: {@code java -cp <classes> bioenv.test.ScannerTimeoutTest}
 */
public final class ScannerTimeoutTest {

    private static int checks;
    private static int failures;

    private ScannerTimeoutTest() {
    }

    public static void main(String[] args) {
        System.out.println("=== ScannerTimeoutTest ===");

        Fake fake = new Fake();
        EnvScanner scanner = new EnvScanner(fake.build());
        EnvScanner.Report rep = scanner.discover();

        // 先确认扫描本身走完了：不然下面「脚本里有 timeout」可能只是断在更早的地方。
        check("扫描报告 conda 可用", rep.condaOk);
        check("环境列表被解析出来", scanner.getEnvs().containsKey("myenv"));
        check("环境前缀正确",
                "/home/demo/miniconda3/envs/myenv".equals(
                        scanner.getEnvs().get("myenv")));

        // --- find / 回退搜索 ---
        check("find / 回退搜索带上了 timeout",
                fake.sawScriptContaining("timeout 20 find /"));
        check("find / 不再是无上限的直接调用",
                !fake.sawScriptContaining("$(find /"));

        // --- conda env list ---
        check("conda env list 带上了 timeout",
                fake.sawScriptContaining("timeout 20 conda env list"));
        // 上限加在命令前面，退出码仍然来自 conda：127 还是「命令没找到」，
        // 124 才是超时。任何把它们吞掉的写法都不允许出现在这里。
        check("conda 的退出码没有被掩盖",
                fake.sawScriptContaining("timeout 20 conda env list 2>&1")
                        && !fake.sawScriptContaining("conda env list 2>&1 ||")
                        && !fake.sawScriptContaining("conda env list 2>&1 |"));

        // --- 扫描的第一条探测是瞬时的，不该被无谓地包一层 ---
        check("瞬时探测保持原样",
                fake.sawScriptContaining("echo __WSL_OK__; uname -r"));

        System.out.println(failures == 0
                ? "PASS " + checks + " checks"
                : "FAIL: " + failures + " of " + checks + " checks failed");
        if (failures > 0) {
            System.exit(1);
        }
    }

    /**
     * 假传输：记录收到的每一段脚本，按脚本内容回放一份固定输出。
     *
     * <p>回放的输出只服务于 discovery 的三步：可达性探测、conda 定位、环境列表。
     */
    private static final class Fake {

        final List<String> scripts = new ArrayList<String>();

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
            if (script.contains("__WSL_OK__")) {
                return new Res(0, "__WSL_OK__\n5.15.0-91-generic\n", "");
            }
            if (script.contains("find / -maxdepth 5")) {
                return new Res(0, "FOUND:/home/demo/miniconda3\n", "");
            }
            if (script.contains("conda env list")) {
                return new Res(0,
                        "# conda environments:\n"
                        + "base                  /home/demo/miniconda3\n"
                        + "myenv                 /home/demo/miniconda3/envs/myenv\n",
                        "");
            }
            return new Res(0, "", "");
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

    /** 只读结果快照：够 discovery 用，不模拟任何真实进程。 */
    private static final class Res implements Transport.Result {

        private final int exit;
        private final String text;
        private final String err;

        Res(int exit, String text, String err) {
            this.exit = exit;
            this.text = text == null ? "" : text;
            this.err = err == null ? "" : err;
        }

        @Override
        public int exitCode() {
            return exit;
        }

        @Override
        public boolean timedOut() {
            return false;
        }

        @Override
        public boolean ok() {
            return exit == 0;
        }

        @Override
        public String stdoutText() {
            return text;
        }

        @Override
        public String stderrText() {
            return err;
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
            List<String> out = new ArrayList<String>();
            for (String line : err.split("\n", -1)) {
                out.add(line);
            }
            return out;
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

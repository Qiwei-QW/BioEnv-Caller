package bioenv.test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import bioenv.core.ConfigWarnings;
import bioenv.core.EnvRunner;
import bioenv.core.Transport;

/**
 * 环境前缀进入 Python 脚本时的引用测试。
 *
 * <p>回归的是一个静默失败：{@code EnvRunner.dependedOnPackages} 把环境前缀直接拼进
 * Python 源码字面量（{@code glob.glob('<前缀>/conda-meta/*.json')}），并且整条命令
 * 以 {@code 2>/dev/null} 结尾。前缀里只要有一个单引号
 * （{@code /home/o'brien/miniconda3/envs/tools}），Python 就报语法错误；错误被丢掉、
 * 结果为空的依赖集合被当真，于是每个依赖包都被当成用户要的工具列出来——界面上
 * 完全看不出哪里错了。
 *
 * <p>所以这里做两件事：
 * <ol>
 *   <li>用一个假的 {@link Transport} 抓真正发出去的脚本，断言 Python 载荷是
 *       <b>合法</b>的（前缀被转义成 Python 字面量、bash 引号闭合）；</li>
 *   <li>断言 stderr 不再被丢弃：读失败时要有中文提示点名环境，而不是装作
 *       「这个环境没有依赖」。</li>
 * </ol>
 *
 * <p>本机有 python3 时，顺带把生成的载荷交给它执行一遍，让 Python 自己判定语法
 * 是否合法；没有就明确打印「未实际执行」，不假装验证过。
 */
public final class EnvRunnerQuoteTest {

    /** 同时带单引号和反斜杠的前缀：两个转义路径都要过一遍。 */
    private static final String TRICKY_PREFIX = "/home/o'brien/miniconda3\\envs\\tools";

    /** 上面前缀在 Python 源码里应该长成的字面量。 */
    private static final String PY_LITERAL =
            "'/home/o\\'brien/miniconda3\\\\envs\\\\tools'";

    private static int checks;
    private static int failures;

    private EnvRunnerQuoteTest() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== EnvRunnerQuoteTest ===");
        try {
            escapingChecks();
            warningChecks();
            plainPrefixChecks();
        } finally {
            ConfigWarnings.resetForTest();
        }

        System.out.println(failures == 0
                ? "PASS " + checks + " checks"
                : "FAIL: " + failures + " of " + checks + " checks failed");
        if (failures > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------
    // 1. 前缀里的单引号 / 反斜杠必须被转义，Python 载荷必须合法
    // ------------------------------------------------------------------

    private static void escapingChecks() throws Exception {
        ConfigWarnings.resetForTest();
        final List<String> warned = new ArrayList<String>();
        ConfigWarnings.setSink(new ConfigWarnings.Sink() {
            @Override
            public void warn(String message) {
                warned.add(message);
            }
        });

        Fake fake = new Fake();
        fake.stdout = "python\nlibgcc-ng\n";
        EnvRunner er = new EnvRunner(fake, "/home/u/miniconda3");
        Set<String> deps = er.dependedOnPackages(TRICKY_PREFIX);

        check("只发了一次命令", fake.scripts.size() == 1);
        String script = fake.scripts.isEmpty() ? "" : fake.scripts.get(0);

        check("命令里调用了 python3 -c", script.contains("python3 -c "));
        // 旧实现的结尾是 " 2>/dev/null"：那里同时丢掉了语法错误和 python3 缺失。
        check("不再丢弃 stderr（没有 2>/dev/null）", !script.contains("2>/dev/null"));

        String py = pythonPayload(script);
        check("bash 能完整解析载荷（引号都闭合）", py != null);
        if (py == null) {
            return;
        }

        check("前缀被转义成 Python 字面量",
                py.contains("glob.glob(" + PY_LITERAL + " + '/conda-meta/*.json')"));
        check("单引号前面有反斜杠（不再是裸引号）", py.contains("\\'"));
        check("反斜杠被成对转义", py.contains("\\\\envs"));
        check("不再有被提前截断的旧写法",
                !script.contains("glob.glob('" + TRICKY_PREFIX));
        check("正常输出时不该报警", warned.isEmpty());
        check("正常输出仍然解析出依赖", deps.contains("python")
                && deps.contains("libgcc-ng"));

        // 语法合法性由 Python 自己判定。没有解释器时按本仓库的门禁约定打印 SKIP，
        // 让构建日志明确写出「这一步没有验证」，而不是读起来像全都验过了。
        String python = findPython();
        if (python == null) {
            System.out.println("  SKIP  本机找不到可用的 python3/python，未实际执行语法检查");
            return;
        }
        int code = runPython(python, py);
        check("本机 " + python + " 判定生成的载荷语法合法（exit=" + code + "）",
                code == 0);
    }

    // ------------------------------------------------------------------
    // 2. 读失败必须说出来，而不是伪装成「没有依赖」
    // ------------------------------------------------------------------

    private static void warningChecks() {
        ConfigWarnings.resetForTest();
        final List<String> warned = new ArrayList<String>();
        ConfigWarnings.setSink(new ConfigWarnings.Sink() {
            @Override
            public void warn(String message) {
                warned.add(message);
            }
        });

        Fake broken = new Fake();
        broken.stdout = "";
        broken.stderr = "  File \"<string>\", line 3\nSyntaxError: invalid syntax";
        Set<String> none = new EnvRunner(broken, "/home/u/miniconda3")
                .dependedOnPackages(TRICKY_PREFIX);

        check("读失败时不返回假数据", none.isEmpty());
        check("读失败时给出提示", warned.size() == 1);
        String msg = warned.isEmpty() ? "" : warned.get(0);
        check("提示是中文并点名环境", msg.contains("依赖") && msg.contains(TRICKY_PREFIX));
        check("提示带上原始错误", msg.contains("SyntaxError"));
        check("多行报错留下最后一行（结论）", msg.contains("File") && msg.contains("line 3"));

        // 同一个环境再失败一次：同一个问题只说一次，不能刷屏。
        new EnvRunner(broken, "/home/u/miniconda3").dependedOnPackages(TRICKY_PREFIX);
        check("同一个问题只报一次", warned.size() == 1);

        // 既没有输出也没有错误：这是「真的没有依赖」，不该报警。
        ConfigWarnings.resetForTest();
        final List<String> quietWarnings = new ArrayList<String>();
        ConfigWarnings.setSink(new ConfigWarnings.Sink() {
            @Override
            public void warn(String message) {
                quietWarnings.add(message);
            }
        });
        Fake quiet = new Fake();
        quiet.stdout = "";
        quiet.stderr = "";
        Set<String> empty = new EnvRunner(quiet, "/home/u/miniconda3")
                .dependedOnPackages("/home/u/miniconda3/envs/tools");
        check("没输出也没错误时返回空集", empty.isEmpty());
        check("没输出也没错误时不报警", quietWarnings.isEmpty());
    }

    // ------------------------------------------------------------------
    // 3. 普通前缀不能因为这次改动而变样
    // ------------------------------------------------------------------

    private static void plainPrefixChecks() {
        ConfigWarnings.resetForTest();
        Fake fake = new Fake();
        fake.stdout = "python\n";
        Set<String> deps = new EnvRunner(fake, "/home/u/miniconda3")
                .dependedOnPackages("/home/u/miniconda3/envs/tools");

        String script = fake.scripts.isEmpty() ? "" : fake.scripts.get(0);
        String py = pythonPayload(script);
        check("普通前缀仍被引成字面量", py != null && py.contains(
                "glob.glob('/home/u/miniconda3/envs/tools' + '/conda-meta/*.json')"));
        check("普通前缀也不再丢弃 stderr", !script.contains("2>/dev/null"));
        check("普通前缀仍能读出依赖", deps.contains("python"));
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 从捕获的 bash 脚本里取出 {@code python3 -c} 的载荷，并按 bash 规则解引用。 */
    private static String pythonPayload(String script) {
        String marker = "python3 -c ";
        int at = script.indexOf(marker);
        if (at < 0) {
            return null;
        }
        try {
            return bashUnquote(script.substring(at + marker.length()));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 模拟 bash 对单引号引用的解析。
     *
     * <p>引号未闭合时会抛异常——正是要抓的那种「前缀里的单引号提前关掉了字面量」。
     */
    private static String bashUnquote(String quoted) {
        StringBuilder out = new StringBuilder();
        boolean inQuote = false;
        int i = 0;
        int n = quoted.length();
        while (i < n) {
            char c = quoted.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
                i++;
                continue;
            }
            if (c == '\\' && !inQuote && i + 1 < n) {
                out.append(quoted.charAt(i + 1));
                i += 2;
                continue;
            }
            out.append(c);
            i++;
        }
        if (inQuote) {
            throw new IllegalStateException("引用未闭合：" + quoted);
        }
        return out.toString();
    }

    /** 找一个能跑的 python；没有返回 null（此时明确不验证语法）。 */
    private static String findPython() {
        String[] candidates = {"python3", "python", "py"};
        for (int i = 0; i < candidates.length; i++) {
            try {
                ProcessBuilder pb = new ProcessBuilder(candidates[i], "--version");
                pb.redirectErrorStream(true);
                // 版本号不需要看，直接丢掉，免得污染测试输出。
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                Process p = pb.start();
                boolean done = p.waitFor(10, TimeUnit.SECONDS);
                if (done && p.exitValue() == 0) {
                    return candidates[i];
                }
                p.destroyForcibly();
            } catch (Exception ignored) {
                // 换下一个候选
            }
        }
        return null;
    }

    /** 让本机 python 执行生成的载荷；语法错误会以非零退出码返回。 */
    private static int runPython(String exe, String code) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(exe, "-c", code);
        pb.redirectErrorStream(true);
        // 输出直接继承：成功时只有空行，失败时语法错误会原样出现在构建日志里。
        // 不用管道，也就不会踩到「读不到 EOF、waitFor 轮不到」那种坑。
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        Process p = pb.start();
        boolean done = p.waitFor(30, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            return -1;
        }
        return p.exitValue();
    }

    /** 假传输：记录收到的每一段脚本，返回配置好的 stdout / stderr。 */
    private static final class Fake implements Transport {

        final List<String> scripts = new ArrayList<String>();
        String stdout = "";
        String stderr = "";

        @Override
        public Result runBashCapture(String script) {
            scripts.add(script);
            return new Res(stdout, stderr);
        }

        @Override
        public Result runBashStreaming(String script, LineSink sink) {
            return runBashCapture(script);
        }

        @Override
        public String getDistro() {
            return "Fake-1.0";
        }

        @Override
        public Process runDetached(String script, String distroName) throws IOException {
            return null;
        }
    }

    private static final class Res implements Transport.Result {
        private final String out;
        private final String err;

        Res(String out, String err) {
            this.out = out == null ? "" : out;
            this.err = err == null ? "" : err;
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
            return out;
        }

        @Override
        public String stderrText() {
            return err;
        }

        @Override
        public List<String> stdout() {
            List<String> lines = new ArrayList<String>();
            for (String line : out.split("\n", -1)) {
                lines.add(line);
            }
            return lines;
        }

        @Override
        public List<String> stderr() {
            List<String> lines = new ArrayList<String>();
            for (String line : err.split("\n", -1)) {
                lines.add(line);
            }
            return lines;
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

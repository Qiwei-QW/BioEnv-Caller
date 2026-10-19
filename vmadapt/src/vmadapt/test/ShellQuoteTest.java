package vmadapt.test;

import vmadapt.core.Shell;

/**
 * Shell 引用与转义的边界测试。
 *
 * <p>为什么这是最重要的一组测试：{@code Shell.quote} 是本项目**唯一**的命令注入
 * 防线。远端的 bash 脚本由一个个被引用的参数拼成，只要引用规则错一处，用户
 * 输入的路径（可能来自文件名，甚至来自下载下来的数据文件名）就能逃出字面量、
 * 变成远端执行的命令。所以这里对单引号的转义做逐字节的断言，而不是"看起来对就行"。
 *
 * <p>不联网、不启动进程，纯字符串。
 */
public final class ShellQuoteTest {

    private static int checks = 0;

    public static void main(String[] args) {
        System.out.println("=== ShellQuoteTest ===");

        // ---------------------------------------------------------------
        // 1. 单引号转义：命令注入的唯一防线
        // ---------------------------------------------------------------
        // 实现：Shell.quote(arg) = "'" + arg.replace("'", "'\\''") + "'"
        // 也就是先把内容里每个单引号替换成「关引号 + 转义单引号 + 重开引号」，
        // 再整体套一层单引号。
        eq("纯字母原样包裹", "'abc'", Shell.quote("abc"));
        eq("空串包裹", "''", Shell.quote(""));
        eq("null 变成空引用", "''", Shell.quote(null));

        // 逐个明确断言字面形态。期望值不是肉眼数出来的，而是由实现跑一遍后
        // 转成 Java 字面量填进来的，并额外用 length() 交叉校验——
        // 混用转义写法时肉眼极易数错字符，而这条断言是命令注入防线的守门人，
        // 必须让"期望值本身是错的"这种失败模式一眼可见。
        //
        // 规则（POSIX 标准写法）：
        //   quote(s) = "'" + s.replace("'", "'\\''") + "'"
        // 即内容里的每个 ' 都替换成  关引号 + 转义单引号 + 重开引号。
        String qSingle = Shell.quote("'");
        eq("单个单引号长度", "6", String.valueOf(qSingle.length()));
        eq("单个单引号", "''\\'''", qSingle);

        String qTwo = Shell.quote("''");
        eq("两个单引号长度", "10", String.valueOf(qTwo.length()));
        eq("两个单引号", "''\\'''\\'''", qTwo);

        String qInner = Shell.quote("it's");
        eq("内嵌单引号长度", "9", String.valueOf(qInner.length()));
        eq("内嵌单引号", "'it'\\''s'", qInner);

        String qEdges = Shell.quote("'x'");
        eq("首尾单引号长度", "11", String.valueOf(qEdges.length()));
        eq("首尾单引号", "''\\''x'\\'''", qEdges);

        // 语义断言作为交叉校验：解引用后必须原样还原
        eq("内嵌单引号语义等价", "it's", bashUnquote(Shell.quote("it's")));
        eq("首尾单引号语义等价", "'x'", bashUnquote(Shell.quote("'x'")));
        eq("两个单引号语义等价", "''", bashUnquote(Shell.quote("''")));
        eq("中文单引号混合可被解析", "测序 数据's.fa",
                bashUnquote(Shell.quote("测序 数据's.fa")));

        // 这是注入尝试的经典形态：关掉引号后跟一条命令。
        // 正确结果必须让整串留在字面量内部，一个字符都不外泄。
        String inject = "'; rm -rf / ;'";
        String quoted = Shell.quote(inject);
        eq("注入尝试长度", "22", String.valueOf(quoted.length()));
        eq("注入尝试被中和", "''\\''; rm -rf / ;'\\'''", quoted);
        // 语义断言：解引用后必须**原样**还原成输入，即没有任何内容逃逸出去。
        eq("注入尝试语义等价（无逃逸）", inject, bashUnquote(quoted));
        // 结构断言：整体必须被单引号包裹
        check("注入结果首字符是单引号", quoted.charAt(0) == '\'');
        check("注入结果末字符是单引号", quoted.charAt(quoted.length() - 1) == '\'');
        // 且所有 ' 都成对出现，没有悬空的引号
        check("注入结果里单引号数量为偶数", countChar(quoted, '\'') % 2 == 0);

        // ---------------------------------------------------------------
        // 2. 各类特殊字符一律不得裸奔
        // ---------------------------------------------------------------
        eq("含空格", "'my data.fa'", Shell.quote("my data.fa"));
        eq("含换行", "'a\nb'", Shell.quote("a\nb"));
        eq("含制表符", "'a\tb'", Shell.quote("a\tb"));
        eq("含 $", "'$HOME'", Shell.quote("$HOME"));
        eq("含反引号", "'`whoami`'", Shell.quote("`whoami`"));
        eq("含反斜杠", "'D:\\data\\x.fa'", Shell.quote("D:\\data\\x.fa"));
        eq("含分号", "'a;b'", Shell.quote("a;b"));
        eq("含管道", "'a|b'", Shell.quote("a|b"));
        eq("含通配符", "'*.fa'", Shell.quote("*.fa"));
        eq("含重定向", "'a>b'", Shell.quote("a>b"));
        eq("含变量展开", "'${HOME}'", Shell.quote("${HOME}"));
        eq("含中文", "'基因数据.fa'", Shell.quote("基因数据.fa"));
        eq("含双引号", "'a\"b'", Shell.quote("a\"b"));

        // 中文路径 + 单引号 + 空格，生物信息场景里真实会出现的组合
        eq("中文空格单引号混合", "'测序 数据'\\''s.fa'", Shell.quote("测序 数据's.fa"));

        // ---------------------------------------------------------------
        // 2b. 结构性不变量：对一大批输入统一验证，不靠逐条肉眼写期望值
        //     —— 肉眼看转义极易数错字符，这里改成机器可判定的性质：
        //     (1) 结果必须首尾都是单引号（整体被包裹）
        //     (2) 解引用后必须原样还原（内容没丢、没多、没逃逸）
        //     (3) 结果里不能出现引号外的裸空白/元字符
        // ---------------------------------------------------------------
        String[] corpus = new String[]{
                "", " ", "  ", "a", "abc", "a b", "a\tb", "a\nb", "a\r\nb",
                "'", "''", "'''", "it's", "'x'", "a'b'c", "''''",
                "$HOME", "${HOME}", "$(whoami)", "`whoami`", "a;b", "a&&b",
                "a|b", "a>b", "a<b", "*.fa", "?.fa", "[a-z].fa", "~", "~/x",
                "D:\\data\\x.fa", "D:/data/x.fa", "\\\\server\\share",
                "C:\\Users\\demo\\.ssh\\id_rsa",
                "我的 数据.fa", "基因's数据.fa", "测序 数据'x'.fa",
                "a\"b", "'a\"b'", "!x", "#x", "a\\b", "\\", "$", "&", "|",
                ";", "*", "?", "(", ")", "{", "}", "<", ">", "%", "@", "+", "=",
                "-", "--flag", "a=b", "path/to/file.fa", "2025-01-01_12-00-00",
                "tab\there", "mix 'and' $VAR `cmd` ; rm -rf /",
        };
        for (int i = 0; i < corpus.length; i++) {
            String in = corpus[i];
            String q = Shell.quote(in);
            String label = "corpus[" + i + "] 输入长度 " + in.length();

            // (1) 整体被单引号包裹
            check("不变量(1) 首字符是单引号：" + label, q.charAt(0) == '\'');
            check("不变量(1) 末字符是单引号：" + label,
                    q.charAt(q.length() - 1) == '\'');
            // (2) 语义还原：这是最关键的一条——任何漏转义都会在这里暴露
            eq("不变量(2) 语义还原：" + label, in, bashUnquote(q));
            // (3) 长度关系：结果至少比输入多两个引号
            check("不变量(3) 长度不小于输入：" + label,
                    q.length() >= in.length() + 2);
        }

        // 逐个断言：quote 的输出长度满足 len(in) + 2 + 3*count(' in in)
        //
        // 推导：quote(s) = "'" + s.replace("'", "'\\''") + "'"
        //   - 外层两个引号：+2
        //   - 输入里每个 ' 被替换成 '\'' （4 个字符），原本占 1 个位置，
        //     所以每个 ' 净增 3 个字符，不是 2
        //
        // 用 countChar(in) 验算：in="'" 时 1 + 2 + 3*1 = 6，
        // 与实际 ' ' \ ' ' ' = 6 字符一致。
        for (int i = 0; i < corpus.length; i++) {
            String in = corpus[i];
            int expectedLen = in.length() + 2 + 3 * countChar(in, '\'');
            eq("不变量(4) 长度公式 corpus[" + i + "]",
                    String.valueOf(expectedLen),
                    String.valueOf(Shell.quote(in).length()));
        }

        // ---------------------------------------------------------------
        // 3. quoteIfNeeded：该省则省，该包则包
        // ---------------------------------------------------------------
        eq("安全值不加引号", "msa", Shell.quoteIfNeeded("msa"));
        eq("数字不加引号", "12", Shell.quoteIfNeeded("12"));
        eq("路径不加引号", "/home/u/a.fa", Shell.quoteIfNeeded("/home/u/a.fa"));
        eq("null 变空引用", "''", Shell.quoteIfNeeded(null));
        eq("空串变空引用", "''", Shell.quoteIfNeeded(""));

        eq("空格必须引用", "'my data.fa'", Shell.quoteIfNeeded("my data.fa"));
        eq("$ 必须引用", "'$HOME'", Shell.quoteIfNeeded("$HOME"));
        eq("单引号必须引用且语义正确", "it's", bashUnquote(Shell.quoteIfNeeded("it's")));
        eq("中文必须引用", "'基因.fa'", Shell.quoteIfNeeded("基因.fa"));
        eq("反斜杠必须引用", "'D:\\x.fa'", Shell.quoteIfNeeded("D:\\x.fa"));

        // ---------------------------------------------------------------
        // 4. needsQuoting 判定
        // ---------------------------------------------------------------
        check("null 需要引用", Shell.needsQuoting(null));
        check("空串需要引用", Shell.needsQuoting(""));
        check("字母数字安全", !Shell.needsQuoting("abcXYZ019"));
        check("安全标点集合", !Shell.needsQuoting("._-+/:=,@%"));
        check("空格需要引用", Shell.needsQuoting("a b"));
        check("$ 需要引用", Shell.needsQuoting("$x"));
        check("* 需要引用", Shell.needsQuoting("*"));
        check("? 需要引用", Shell.needsQuoting("?"));
        check("~ 需要引用", Shell.needsQuoting("~"));
        check("! 需要引用", Shell.needsQuoting("!"));
        check("& 需要引用", Shell.needsQuoting("&"));
        check("| 需要引用", Shell.needsQuoting("|"));
        check("; 需要引用", Shell.needsQuoting(";"));
        check("< 需要引用", Shell.needsQuoting("<"));
        check("> 需要引用", Shell.needsQuoting(">"));
        check("( 需要引用", Shell.needsQuoting("("));
        check(") 需要引用", Shell.needsQuoting(")"));
        check("{ 需要引用", Shell.needsQuoting("{"));
        check("} 需要引用", Shell.needsQuoting("}"));
        check("反斜杠需要引用", Shell.needsQuoting("\\"));
        check("单引号需要引用", Shell.needsQuoting("'"));
        check("双引号需要引用", Shell.needsQuoting("\""));
        check("中文需要引用", Shell.needsQuoting("基因"));
        check("单个安全字符", !Shell.needsQuoting("a"));

        // ---------------------------------------------------------------
        // 5. stripAnsi：远端颜色码不该进日志
        // ---------------------------------------------------------------
        eq("剥掉 CSI 颜色码", "hello", Shell.stripAnsi("\u001B[31mhello\u001B[0m"));
        eq("剥掉多段颜色", "ab", Shell.stripAnsi("\u001B[1m\u001B[31ma\u001B[0mb"));
        eq("无转义原样返回", "plain", Shell.stripAnsi("plain"));
        eq("null 返回 null", null, Shell.stripAnsi(null));
        eq("空串返回空串", "", Shell.stripAnsi(""));
        eq("剥掉 OSC 序列", "title", Shell.stripAnsi("\u001B]0;x\u0007title"));
        eq("孤立 ESC 被丢弃", "ab", Shell.stripAnsi("a\u001Bb"));
        check("CSI 内容被清空", Shell.stripAnsi("\u001B[2K").isEmpty());

        // ---------------------------------------------------------------
        // 6. splitLines：Windows 换行与 ANSI 一起处理
        // ---------------------------------------------------------------
        java.util.List<String> lines = Shell.splitLines("a\r\nb\nc");
        eq("拆行数量", "3", String.valueOf(lines.size()));
        eq("行 0", "a", lines.get(0));
        eq("行 1", "b", lines.get(1));
        eq("行 2", "c", lines.get(2));
        check("null 返回空列表", Shell.splitLines(null).isEmpty());
        check("空串返回空列表", Shell.splitLines("").isEmpty());
        // 行内 ANSI 也要被剥掉
        java.util.List<String> ansi = Shell.splitLines("\u001B[32mok\u001B[0m\nx");
        eq("拆行时剥 ANSI", "ok", ansi.get(0));

        System.out.println("PASS " + checks + " checks");
    }

    // -----------------------------------------------------------------
    // 断言工具
    // -----------------------------------------------------------------

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            System.out.println("FAIL: " + what);
            System.exit(1);
        }
    }

    private static void eq(String what, String expected, String actual) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            System.out.println("FAIL: " + what
                    + " — 期望 [" + expected + "] 实际 [" + actual + "]");
            System.exit(1);
        }
    }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    /**
     * 模拟 bash 对单引号引用的解析，用来断言 {@link Shell#quote} 的**语义**正确，
     * 而不是断言它恰好写出了某一种字面形态。
     *
     * <p>规则：单引号之间的一切都是字面量；{@code '\''} 是"闭合 + 转义单引号 +
     * 重新开启"，最终产生一个字面单引号。空引用 {@code ''} 产生空串。
     * 这是 bash 的语义本身，不依赖具体实现，所以用它来验收引用函数是对的。
     *
     * <p>若实现真的漏转义（例如把 {@code '} 原样留在引号中间），这里就会解析出
     * 与原串不同的结果，从而失败——正是我们要守的那条防线。
     */
    private static String bashUnquote(String quoted) {
        if (quoted == null) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = quoted.length();
        boolean inQuote = false;
        while (i < n) {
            char c = quoted.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
                i++;
                continue;
            }
            if (c == '\\' && !inQuote && i + 1 < n) {
                // 引号外的反斜杠转义下一个字符（例如 \'）
                out.append(quoted.charAt(i + 1));
                i += 2;
                continue;
            }
            out.append(c);
            i++;
        }
        // 引号未闭合说明引用坏了
        if (inQuote) {
            throw new IllegalStateException("引用未闭合：" + quoted);
        }
        return out.toString();
    }
}

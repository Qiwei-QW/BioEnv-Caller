package vmadapt.core;

/**
 * 生成远端 bash 命令行时用到的静态工具。
 *
 * <p>为什么单独一个类：这些规则必须和 BioEnvCaller 的 WslBridge 完全一致。
 * 两个插件最终会把命令交给同一批远端工具，如果这边把 {@code msa} 引起来、
 * 那边不引，用户看到的命令就会不一致，出问题时也无法互相印证。行为对齐后，
 * 本类可以直接从 WslBridge 搬测试用例。
 *
 * <p>规则来源：WslBridge.quote / quoteIfNeeded / needsQuoting（625-679 行）。
 * 那里只读参考，未做任何修改。
 */
public final class Shell {

    private Shell() {
    }

    /**
     * 无条件为 bash 引用一个参数。
     *
     * <p>用于无法判断安全性的值——尤其是环境变量前缀，以及由探测结果拼出来的
     * 命令片段。单引号内除单引号本身外一切字符都是字面量，所以把内部的
     * {@code '} 换成 {@code '\''} 就能安全闭合再打开。
     *
     * @param arg 任意字符串，可为 null
     * @return 可直接放入命令行的引用结果；null 变成 {@code ''}
     */
    public static String quote(String arg) {
        if (arg == null) {
            return "''";
        }
        return "'" + arg.replace("'", "'\\''") + "'";
    }

    /**
     * 只在必要时引用。
     *
     * <p>命令行是给人看、给人复制、给人改成脚本的，所以应该长得像人写的。
     * 无脑全部引用会产出 {@code -M 'msa'}、{@code -t '12'} 这种噪音，既难读
     * 也难改（对齐 DESIGN.md P13）。
     *
     * <p>只由 bash 当字面量处理的字符组成的值原样输出；含空白、引号、通配符
     * 或展开字符的值一律引用，因此带空格的路径和带 shell 元字符的值仍然安全。
     *
     * @param arg 待处理的参数，可为 null 或空串
     * @return 可直接放入命令行的结果；null 或空串变成 {@code ''}
     */
    public static String quoteIfNeeded(String arg) {
        if (arg == null || arg.isEmpty()) {
            return "''";
        }
        if (needsQuoting(arg)) {
            return quote(arg);
        }
        return arg;
    }

    /**
     * 判断一个值能否不加引号直接输出。
     *
     * <p>安全集合是字母、数字，以及路径/数字/格式/选项值里常见的标点：
     * {@code . _ - + / : = , @ %}。其余一切——空格、引号，
     * {@code $ * ? [ ] ~ ! & | ; < > ( ) { } \ }——都判定为需要引用。
     *
     * @param arg 待判断的值
     * @return true 表示必须引用（null 和空串也算）
     */
    public static boolean needsQuoting(String arg) {
        if (arg == null || arg.isEmpty()) {
            return true;
        }
        for (int i = 0; i < arg.length(); i++) {
            char c = arg.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-' || c == '+'
                    || c == '/' || c == ':' || c == '=' || c == ',' || c == '@' || c == '%';
            if (!safe) {
                return true;
            }
        }
        return false;
    }

    /**
     * 去掉字符串里的 ANSI 终端控制序列。
     *
     * <p>为什么在传输层就剥掉：远端工具（conda、mamba、snakemake）在非交互
     * 场景下仍会输出颜色码和光标控制符。这些东西进了日志就变成乱码，
     * 进了界面会让用户困惑，而且下游每一个解析器都得自己处理一遍。
     * 在唯一的边界处清理一次，之后全项目都只面对纯文本。
     *
     * <p>处理四类：CSI 序列（{@code ESC [ ... 终止符}）、OSC 序列
     * （{@code ESC ] ... BEL 或 ESC \}）、两字符的 Fe 转义序列，以及
     * 单独出现的裸 ESC（以及残留的未配对控制符）。
     *
     * @param s 原始文本，可为 null
     * @return 清理后的文本；null 返回 null
     */
    public static String stripAnsi(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c != '\u001B') {
                out.append(c);
                i++;
                continue;
            }
            // 走到这里说明遇到了 ESC：尝试把整个控制序列吞掉。
            i++;
            if (i >= n) {
                break; // 结尾一个孤立的 ESC，丢弃
            }
            char next = s.charAt(i);
            if (next == '[') {
                // CSI：参数与中间字节，直到 0x40-0x7E 的终止字节
                i++;
                while (i < n) {
                    char t = s.charAt(i);
                    i++;
                    if (t >= 0x40 && t <= 0x7E) {
                        break;
                    }
                }
            } else if (next == ']') {
                // OSC：直到 BEL 或 ST (ESC \) 为止
                i++;
                while (i < n) {
                    char t = s.charAt(i);
                    if (t == '\u0007') {
                        i++;
                        break;
                    }
                    if (t == '\u001B' && i + 1 < n && s.charAt(i + 1) == '\\') {
                        i += 2;
                        break;
                    }
                    i++;
                }
            } else if (next >= 0x40 && next <= 0x5F) {
                // Fe 两字符序列（例如 ESC ( B）
                i++;
            }
            // 其它情况：不是已知序列，只丢弃这个 ESC，继续扫描
        }
        return out.toString();
    }

    /**
     * 把多行文本按行拆开，并逐行去掉 ANSI 和行尾的 {@code \r}。
     *
     * <p>ssh.exe 在 Windows 上转发远端换行时可能留下 {@code \r}，不去掉会让
     * 用户看到「覆盖式」拼接的日志。
     *
     * @param text 原始多行文本，可为 null
     * @return 行列表；null 或空串返回空列表
     */
    public static java.util.List<String> splitLines(String text) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        String[] parts = text.split("\n", -1);
        for (int i = 0; i < parts.length; i++) {
            String line = parts[i];
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            out.add(stripAnsi(line));
        }
        return out;
    }
}

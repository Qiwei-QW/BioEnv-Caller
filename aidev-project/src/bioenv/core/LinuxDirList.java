package bioenv.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 在 Linux 侧列目录：生成脚本 + 解析输出。
 *
 * <h2>为什么是纯静态方法</h2>
 *
 * <p>真正的难点在"脚本写对没有"和"输出怎么解读"，而不在窗口怎么画。把这两件事拆成
 * 不碰界面的纯函数，无头环境就能直接断言它们——否则要验证一个引号问题，得先开一个
 * 窗口、连一台机器。
 *
 * <h2>为什么两种通道用同一套</h2>
 *
 * <p>WSL 和 SSH 都实现了 {@code Transport.runBashCapture(script)}，返回逐行的 stdout。
 * 所以这里不需要两套实现：一段可移植的 bash 在两边都能跑。这是刻意选的——分头写两套
 * 迟早会各偏一边。
 */
public final class LinuxDirList {

    private LinuxDirList() {
    }

    /**
     * 列出一个目录下的<b>子目录</b>（不列文件）。
     *
     * <h2>输出约定</h2>
     *
     * <p>第一行是解析后的绝对路径（{@code cd ... && pwd -P}），后面每行一个子目录名。
     * 解析见 {@link #parse(String)}。
     *
     * <h2>几个必须照顾到的点</h2>
     *
     * <ul>
     *   <li><b>路径要过引号</b>。用户可以在框里手打路径，里面可能有空格；
     *       而 {@code ~} 落进单引号就不会展开（项目里为此吃过一次亏，见
     *       {@link vmadapt.core.RemoteFiles#expandHomeTemplate}）。</li>
     *   <li><b>{@code pwd -P}</b> 而不是 {@code pwd}：解析软链接，回显一个真实可用的
     *       绝对路径，免得用户拿到一串 {@code /home/a/link/../x}。</li>
     *   <li><b>只列目录</b>：{@code ls -1p} 给目录名加 {@code /} 后缀，用它过滤。
     *       这里不列文件，是因为这个对话框是用来选"工作目录"的。</li>
     *   <li><b>出错要能说清楚</b>：进不去时写一句人话，而不是丢一堆 stderr 给用户。</li>
     * </ul>
     *
     * @param dir 要列的目录；允许 {@code ~} 或 {@code ~/x} 形式，也允许为空（当主目录）
     */
    public static String script(String dir) {
        String target = (dir == null || dir.trim().isEmpty()) ? "~" : dir.trim();
        String quoted = vmadapt.core.RemoteFiles.expandHomeTemplate(target);
        // set -o pipefail 不必要：下面的 grep 找不到目录时会返回 1，那不是错误。
        // 所以不把 grep 的退出码当失败判据，改用"有没有回显"来判断。
        return "d=" + quoted + "\n"
                + "if ! cd \"$d\" 2>/dev/null; then\n"
                + "  echo '__TBVM_NO_SUCH_DIR__' >&2\n"
                + "  exit 3\n"
                + "fi\n"
                + "pwd -P\n"
                + "ls -1pA 2>/dev/null | grep '/$'\n"
                + "exit 0\n";
    }

    /** 脚本进入目录失败时写在 stderr 的标记。 */
    public static final String NO_SUCH_DIR = "__TBVM_NO_SUCH_DIR__";

    /**
     * 一份列目录结果。
     *
     * <p>失败时 {@link #problem} 是一句能直接给用户看的话，{@link #path} 与
     * {@link #dirs} 为空。
     */
    public static final class Listing {
        /** 解析后的绝对路径（软链接已解开）。 */
        public String path = "";
        /** 子目录名，已排序。 */
        public final List<String> dirs = new ArrayList<String>();
        /** 文件名，已排序；只有 {@link #parseFiles} 会填。 */
        public final List<String> files = new ArrayList<String>();
        /** 详细信息视图的行；只有 {@link #parseDetails} 会填。 */
        public final List<Entry> entries = new ArrayList<Entry>();
        /** 失败原因；成功时为空串。 */
        public String problem = "";

        public boolean ok() {
            return problem.isEmpty();
        }
    }

    /**
     * 解析 {@link #script} 的输出。
     *
     * @param stdout 标准输出的各行
     * @param stderr 标准错误的各行
     * @param exit   退出码
     */
    public static Listing parse(List<String> stdout, List<String> stderr, int exit) {
        Listing out = new Listing();
        List<String> err = stderr == null ? Collections.<String>emptyList() : stderr;
        for (String line : err) {
            if (line != null && line.contains(NO_SUCH_DIR)) {
                out.problem = "目录不存在，或者没有权限进入。";
                return out;
            }
        }
        if (stdout == null || stdout.isEmpty()) {
            out.problem = exit == 0
                    ? "没有拿到回显（目录可能不可读）。"
                    : "列目录失败（退出码 " + exit + "）。";
            return out;
        }
        out.path = stdout.get(0) == null ? "" : stdout.get(0).trim();
        if (out.path.isEmpty() || !out.path.startsWith("/")) {
            out.problem = "回显的路径不像绝对路径：" + out.path;
            return out;
        }
        for (int i = 1; i < stdout.size(); i++) {
            String line = stdout.get(i);
            if (line == null) {
                continue;
            }
            String name = line.trim();
            // ls -1p 给目录加了 "/" 后缀，去掉它才是名字。
            if (name.endsWith("/")) {
                name = name.substring(0, name.length() - 1);
            }
            if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
                continue;
            }
            out.dirs.add(name);
        }
        Collections.sort(out.dirs, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /**
     * 向上一级。已经在根上时返回自身。
     *
     * <p>走字符串而不是 {@code File.getParent()}：这是<b>远端</b> Linux 的路径，本机
     * 的 {@code File} 会给它套上 Windows 的规则（{@code /home} 会被当成"当前盘符下的
     * home"），得到的父目录就错了。
     */
    public static String parentOf(String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return "/";
        }
        String p = path;
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        int slash = p.lastIndexOf('/');
        if (slash <= 0) {
            return "/";
        }
        return p.substring(0, slash);
    }

    /** 拼一个子路径。 */
    public static String childOf(String dir, String name) {
        if (dir == null || dir.isEmpty()) {
            return "/" + name;
        }
        String d = dir.endsWith("/") ? dir.substring(0, dir.length() - 1) : dir;
        return d.isEmpty() ? "/" + name : d + "/" + name;
    }

    /** 新建目录；已存在也算成功（{@code -p}）。 */
    public static String mkdirScript(String dir) {
        if (dir == null || dir.trim().isEmpty()) {
            return "exit 1\n";
        }
        String quoted = vmadapt.core.RemoteFiles.expandHomeTemplate(dir.trim());
        return "d=" + quoted + "\n"
                + "mkdir -p \"$d\" 2>/dev/null || exit 4\n"
                + "cd \"$d\" 2>/dev/null || exit 3\n"
                + "pwd -P\n";
    }

    /**
     * 列目录，并且每个目录带一项"详情"（用于详细信息视图）。
     *
     * <p>详情取的是权限 + 修改时间，格式固定成 {@code drwxr-xr-x 2026-10-11 14:03}，
     * 由 {@code ls -l} 的列拼出来。不直接用整行 {@code ls -l}：那一行的列数会随
     * 文件系统（有无 ACL 标记、有无硬链接计数）变化，界面里就会忽长忽短。
     *
     * <p>只保留目录（{@code ls -1p} 那套在这里不适用，因为要拿权限位），判据是
     * {@code ls -l} 首字符为 {@code d}。
     */
    public static String detailsScript(String dir) {
        String target = (dir == null || dir.trim().isEmpty()) ? "~" : dir.trim();
        String quoted = vmadapt.core.RemoteFiles.expandHomeTemplate(target);
        // %M 权限 %y 修改时间（ISO 形式，取前 16 字符到分钟）
        // 用 tab 分隔，避免目录名里的空格把列冲散。
        return "d=" + quoted + "\n"
                + "if ! cd \"$d\" 2>/dev/null; then\n"
                + "  echo '__TBVM_NO_SUCH_DIR__' >&2\n"
                + "  exit 3\n"
                + "fi\n"
                + "pwd -P\n"
                + "for e in */; do\n"
                + "  [ -d \"$e\" ] || continue\n"
                + "  n=${e%/}\n"
                + "  printf '%s\\t%s\\t%s\\n' \"$n\" \"$(stat -c %A \"$e\" 2>/dev/null)\""
                + " \"$(stat -c %y \"$e\" 2>/dev/null | cut -c1-16)\"\n"
                + "done\n"
                + "exit 0\n";
    }

    /** 详细信息视图的一行。 */
    public static final class Entry {
        public String name = "";
        public String perms = "";
        public String modified = "";
    }

    /** 解析 {@link #detailsScript} 的输出。 */
    public static Listing parseDetails(List<String> stdout, List<String> stderr, int exit) {
        Listing out = parse(stdout, stderr, exit);
        if (!out.ok() || stdout == null) {
            return out;
        }
        out.entries.clear();
        out.dirs.clear();
        for (int i = 1; i < stdout.size(); i++) {
            String line = stdout.get(i);
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            String[] parts = line.split("\t", -1);
            Entry e = new Entry();
            e.name = parts.length > 0 ? parts[0].trim() : "";
            e.perms = parts.length > 1 ? parts[1].trim() : "";
            e.modified = parts.length > 2 ? parts[2].trim() : "";
            if (e.name.isEmpty()) {
                continue;
            }
            out.entries.add(e);
            out.dirs.add(e.name);
        }
        Collections.sort(out.dirs, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    // ------------------------------------------------------------------
    // 选文件（而不只是选目录）
    // ------------------------------------------------------------------

    /**
     * 列出一个目录下的<b>目录和文件</b>，供选文件时用。
     *
     * <p>和 {@link #script} 的区别：那个只列目录（挑工作目录用），这里两者都要——目录是
     * 用来走进去的，文件才是能选的东西。{@code ls -1p} 给目录加了 {@code /} 后缀，正好
     * 把两者分开，不需要额外问远端。
     *
     * <p>{@code nameContains} 非空时按名字做<b>子串</b>过滤，在<b>远端</b>过滤。大目录
     * （一个测序数据目录动辄上千个文件）不该整份传回来再筛——一次往返能省几秒。
     * 过滤只作用于文件，目录一律保留，否则筛一下就进不去子目录了。
     */
    public static String filesScript(String dir, String nameContains) {
        String target = (dir == null || dir.trim().isEmpty()) ? "~" : dir.trim();
        String quoted = vmadapt.core.RemoteFiles.expandHomeTemplate(target);
        // 过滤值经 sed 的 s 命令要转义，否则用户输入里的 / 或 & 会把表达式弄坏。
        String filter = nameContains == null ? "" : nameContains.trim();
        String filterLine = "";
        if (!filter.isEmpty()) {
            StringBuilder esc = new StringBuilder();
            for (int i = 0; i < filter.length(); i++) {
                char ch = filter.charAt(i);
                if (ch == '/' || ch == '&' || ch == '\\' || ch == '\n') {
                    esc.append('\\');
                }
                esc.append(ch);
            }
            // -F 固定字符串匹配，避免用户输入里的 . * [ 被当成正则。
            filterLine = "  case \"$e\" in */) ;; *) "
                    + "case \"$e\" in *" + esc + "*) ;; *) continue ;; esac ;; esac\n";
        }
        return "d=" + quoted + "\n"
                + "if ! cd \"$d\" 2>/dev/null; then\n"
                + "  echo '__TBVM_NO_SUCH_DIR__' >&2\n"
                + "  exit 3\n"
                + "fi\n"
                + "pwd -P\n"
                + "for e in * .[!.]*; do\n"
                + "  [ -e \"$e\" ] || continue\n"
                + filterLine
                // 目录要带 "/" 后缀，和 ls -1p 一致。少了它，调用方分不出哪是目录，
                // 目录会被当成文件塞进文件列表、配上文件图标——用户看到的正是那样。
                + "  if [ -d \"$e\" ]; then printf '%s/\\n' \"$e\";"
                + " else printf '%s\\n' \"$e\"; fi\n"
                + "done\n"
                + "exit 0\n";
    }

    /**
     * 列目录+文件，每条带权限与修改时间（详细信息视图用）。
     *
     * <p>名字后面带一个 {@code /} 表示是目录——{@code ls -1p} 那个约定继续用，因为
     * 调用方要靠它分图标、分行为。名字里可能有 tab 吗？可能没有：Unix 文件名允许 tab，
     * 但真出现时坏的是这一行的可读性，不是数据（解析按第一个 tab 切）。
     */
    public static String filesDetailsScript(String dir, String nameContains) {
        String target = (dir == null || dir.trim().isEmpty()) ? "~" : dir.trim();
        String quoted = vmadapt.core.RemoteFiles.expandHomeTemplate(target);
        String filter = nameContains == null ? "" : nameContains.trim();
        String filterLine = "";
        if (!filter.isEmpty()) {
            StringBuilder esc = new StringBuilder();
            for (int i = 0; i < filter.length(); i++) {
                char ch = filter.charAt(i);
                if (ch == '/' || ch == '&' || ch == '\\' || ch == '\n') {
                    esc.append('\\');
                }
                esc.append(ch);
            }
            filterLine = "  case \"$e\" in */) ;; *) "
                    + "case \"$e\" in *" + esc + "*) ;; *) continue ;; esac ;; esac\n";
        }
        return "d=" + quoted + "\n"
                + "if ! cd \"$d\" 2>/dev/null; then\n"
                + "  echo '__TBVM_NO_SUCH_DIR__' >&2\n"
                + "  exit 3\n"
                + "fi\n"
                + "pwd -P\n"
                + "for e in * .[!.]*; do\n"
                + "  [ -e \"$e\" ] || continue\n"
                + filterLine
                + "  if [ -d \"$e\" ]; then n=\"$e/\"; else n=\"$e\"; fi\n"
                + "  printf '%s\\t%s\\t%s\\n' \"$n\""
                + " \"$(stat -c %A \"$e\" 2>/dev/null)\""
                + " \"$(stat -c %y \"$e\" 2>/dev/null | cut -c1-16)\"\n"
                + "done\n"
                + "exit 0\n";
    }

    /**
     * 解析 {@link #filesDetailsScript} 的输出。
     *
     * <p>仍走 {@link #parseFiles}：它认名字末尾的 {@code /}，所以目录进 {@code dirs}、
     * 文件进 {@code files}；随后把逐条的权限/时间填进 {@code entries}，顺序与 dirs+files
     * 合并后的顺序一致（目录在前，各自按名字排）。
     */
    public static Listing parseFilesDetails(List<String> stdout, List<String> stderr,
                                            int exit) {
        // 这里<b>不能</b>委托给 parseFiles。
        //
        // 详情输出是 tab 分隔的（名字 \t 权限 \t 时间），而 parseFiles 是拿<b>整行</b>去
        // 看末尾有没有 "/" 的——"sub/\tdrwxr-xr-x\t..." 这一整行末尾不是斜杠，于是目录
        // 被判成了文件。测试第一次跑就抓到了。
        //
        // 教训记一次：解析器是按某一种输出格式写的，换一种格式就得换一个解析器。复用
        // 看着省事，实际是把两种格式揉进同一个判断里，而那个判断只对其中一种成立。
        Listing out = new Listing();
        List<String> err = stderr == null ? Collections.<String>emptyList() : stderr;
        for (String line : err) {
            if (line != null && line.contains(NO_SUCH_DIR)) {
                out.problem = "目录不存在，或者没有权限进入。";
                return out;
            }
        }
        if (stdout == null || stdout.isEmpty()) {
            out.problem = exit == 0
                    ? "没有拿到回显（目录可能不可读）。"
                    : "列目录失败（退出码 " + exit + "）。";
            return out;
        }
        out.path = stdout.get(0) == null ? "" : stdout.get(0).trim();
        if (out.path.isEmpty() || !out.path.startsWith("/")) {
            out.problem = "回显的路径不像绝对路径：" + out.path;
            return out;
        }
        for (int i = 1; i < stdout.size(); i++) {
            String line = stdout.get(i);
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            // 先按 tab 切，再看名字部分的末尾——顺序不能反。
            String[] parts = line.split("\t", -1);
            String raw = parts[0].trim();
            boolean isDir = raw.endsWith("/");
            String name = isDir ? raw.substring(0, raw.length() - 1) : raw;
            if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
                continue;
            }
            Entry e = new Entry();
            e.name = name;
            e.perms = parts.length > 1 ? parts[1].trim() : "";
            e.modified = parts.length > 2 ? parts[2].trim() : "";
            out.entries.add(e);
            if (isDir) {
                out.dirs.add(name);
            } else {
                out.files.add(name);
            }
        }
        Collections.sort(out.dirs, String.CASE_INSENSITIVE_ORDER);
        Collections.sort(out.files, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /**
     * 解析 {@link #filesScript} 的输出。
     *
     * <p>沿用 {@link Listing}：{@code dirs} 放<b>子目录</b>名，{@code files} 放<b>文件</b>名。
     * 两者分开是因为它们在界面上的行为完全不同——目录是点进去的，文件是选中带走的。
     */
    public static Listing parseFiles(List<String> stdout, List<String> stderr, int exit) {
        // 这里<b>不能</b>复用 parse() 的分类。
        //
        // parse() 是给"只列目录"的脚本写的：它拿第一行当路径，之后每一行都当成目录名
        // （因为那个脚本在远端已经用 grep 筛过了）。把它套到文件输出上，文件会全部
        // 落进 dirs、files 一个都没有——这正是这条测试第一次跑就抓到的。
        //
        // parseDetails() 依赖 parse() 那个宽松行为（详情输出的名字不带斜杠），所以也
        // 不能反过来把 parse() 改成"必须有斜杠才算目录"。两类输出各拆各的。
        Listing out = new Listing();
        List<String> err = stderr == null ? Collections.<String>emptyList() : stderr;
        for (String line : err) {
            if (line != null && line.contains(NO_SUCH_DIR)) {
                out.problem = "目录不存在，或者没有权限进入。";
                return out;
            }
        }
        if (stdout == null || stdout.isEmpty()) {
            out.problem = exit == 0
                    ? "没有拿到回显（目录可能不可读）。"
                    : "列目录失败（退出码 " + exit + "）。";
            return out;
        }
        out.path = stdout.get(0) == null ? "" : stdout.get(0).trim();
        if (out.path.isEmpty() || !out.path.startsWith("/")) {
            out.problem = "回显的路径不像绝对路径：" + out.path;
            return out;
        }
        for (int i = 1; i < stdout.size(); i++) {
            String line = stdout.get(i);
            if (line == null) {
                continue;
            }
            String name = line.trim();
            if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
                continue;
            }
            if (name.endsWith("/")) {
                // ls -1p 给目录加了 "/" 后缀，去掉它才是名字。
                out.dirs.add(name.substring(0, name.length() - 1));
            } else {
                out.files.add(name);
            }
        }
        Collections.sort(out.dirs, String.CASE_INSENSITIVE_ORDER);
        Collections.sort(out.files, String.CASE_INSENSITIVE_ORDER);
        return out;
    }
}

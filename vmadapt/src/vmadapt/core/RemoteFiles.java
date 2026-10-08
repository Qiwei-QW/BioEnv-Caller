package vmadapt.core;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 远端路径与工作目录管理。
 *
 * <p>职责有三块：把远端目录规范成 scp/sftp 认识的目标串；在远端创建带时间戳的
 * 独立工作目录，避免多次运行互相覆盖中间文件；比较本地与远端文件大小，用来决定
 * 是否需要重传。</p>
 *
 * <p>为什么不直接在 Java 里拼 "user@host:path" 就完事：远端路径会经过远端用户的
 * shell 解析，空格和中文不加引号就会被拆成两个参数；同时本地路径（{@code D:\data\x.fa}）
 * 和 POSIX 路径（{@code /home/u/x.fa}）长得完全不同，必须在送出去之前分清楚，否则
 * 用户把 Windows 路径粘进"远端路径"框时会得到一个完全看不懂的错误。</p>
 */
public class RemoteFiles {

    /** 工作根目录在 HOME 下的目录名。真正拼命令时用这个，配合 {@code $HOME}。 */
    public static final String WORK_ROOT_NAME = "vmadapt_work";

    /**
     * 远端工作目录的根，位于用户 HOME 下，避免污染公共目录。
     *
     * <p><b>这是给人看的模板形式，不要直接丢给 shell。</b>
     * {@code ~} 只在词首、且没有被引号包住时才会展开。过去这里被当成 shell
     * 参数交给 {@code mkdir -p}，而引号是统一加上的，于是创建出来的是一个
     * 名字就叫 {@code ~} 的目录；接着 {@code pwd -P} 又如实把它报回来，
     * 一路写进了用户看到的路径和工具参数，最后工具报「目录不存在」。
     *
     * <p>要拼命令请用 {@link #workDirScript(String)}，它用 {@code "$HOME/..."}
     * 取家目录，不依赖 {@code ~} 的展开。
     */
    public static final String WORK_ROOT = "~/" + WORK_ROOT_NAME;

    /** 时间戳格式，精确到秒，保证同一天多次运行不重名。 */
    private static final String STAMP = "yyyyMMdd_HHmmss";

    private final SshTarget target;

    /** 允许为 null：上传前的尺寸比较需要它，纯路径拼接不需要。 */
    private final SshTransport transport;

    /** 上一次创建工作目录失败的原因，见 {@link #lastFailure()}。 */
    private String lastFailure = "";

    public RemoteFiles(SshTarget target, SshTransport transport) {
        if (target == null) {
            throw new IllegalArgumentException("SshTarget must not be null");
        }
        this.target = target;
        this.transport = transport;
    }

    public SshTarget target() {
        return target;
    }

    // ------------------------------------------------------------------
    // 工作目录
    // ------------------------------------------------------------------

    /**
     * 在远端 {@code ~/vmadapt_work/} 下创建一个带时间戳的工作目录并返回其绝对路径。
     *
     * <p>为什么返回绝对路径而不是相对路径：后续的 scp 命令、后台 nohup 任务、
     * 结果回传都各自是独立进程，工作目录（cwd）不会继承，只有绝对路径才可靠。
     * 而 {@code ~} 只有登录 shell 会展开，scp 的远端参数虽然会过 shell，但把
     * {@code ~} 换成 {@code $HOME} 的绝对结果更稳妥，所以这里用 {@code cd ... && pwd}
     * 回显真实路径。</p>
     *
     * @param label 人类可读的标签，用于区分不同任务；非法字符会被替换成下划线
     * @return 远端绝对路径；创建失败返回 null
     */
    public String createWorkDir(String label) {
        if (transport == null) {
            lastFailure = "no transport"; 
            return null;
        }
        String sub = sanitizeLabel(label) + "_" + stamp();
        return absoluteFrom(transport.runBashCapture(workDirScript(sub)));
    }

    /**
     * 上一次失败的原因；成功时为空串。
     *
     * <p>存在的理由：这个方法失败时界面只能说「无法创建工作目录」，而失败有四种
     * 完全不同的原因——连不上、命令退出码非零、没回显、回显的路径不能用。
     * 不写清是哪一种，用户（和排查的人）只能靠猜。
     */
    public String lastFailure() {
        return lastFailure;
    }

    /**
     * 在指定远端基目录下创建带时间戳的工作目录（用于把中间文件放到数据盘等场景）。
     *
     * @return 远端绝对路径；失败返回 null
     */
    public String createWorkDirUnder(String baseDir, String label) {
        if (transport == null || baseDir == null || baseDir.trim().isEmpty()) {
            return null;
        }
        String sub = sanitizeLabel(label) + "_" + stamp();
        String rel = joinRemote(baseDir.trim(), sub);
        // 基目录可能写成 ~/data 这种模板形式，所以走同一套展开逻辑。
        String script = "d=" + expandHomeTemplate(rel) + "\n"
                + "mkdir -p \"$d\" && cd \"$d\" && pwd -P\n";
        return absoluteFrom(transport.runBashCapture(script));
    }

    /** 时间戳，精确到秒。 */
    private static String stamp() {
        return new SimpleDateFormat(STAMP, Locale.ROOT).format(new Date());
    }

    /**
     * 拼出「创建工作目录并回显其绝对路径」的脚本。
     *
     * <p>{@code $HOME} 必须放在<b>双引号</b>里：双引号会展开它，单引号不会。
     * 这正是原来那个 bug 的根源——写法是 {@code mkdir -p '~/vmadapt_work/x'}，
     * 单引号让 {@code ~} 永远得不到展开，于是建出一个名字叫 {@code ~} 的目录。
     *
     * @param sub 工作根目录下的子目录名
     */
    public static String workDirScript(String sub) {
        String rel = WORK_ROOT_NAME + "/" + escapeInDoubleQuotes(sub);
        return "d=\"$HOME/" + rel + "\"\n"
                + "mkdir -p \"$d\" && cd \"$d\" && pwd -P\n";
    }

    /**
     * 把可能以 {@code ~/} 开头的路径模板改成 shell 真能展开的写法。
     *
     * <p>{@code ~} 只在词首、且没被引号包住时才展开；一旦落进单引号（而
     * {@link Shell#quote} 是无条件加引号的），它就是个普通字符。所以凡是要交给
     * shell 的路径都不能原样保留 {@code ~}。
     */
    public static String expandHomeTemplate(String path) {
        if (path == null) {
            return "''";
        }
        String p = path.trim();
        if (p.equals("~")) {
            return "\"$HOME\"";
        }
        if (p.startsWith("~/")) {
            return "\"$HOME/" + escapeInDoubleQuotes(p.substring(2)) + "\"";
        }
        return Shell.quote(p);
    }

    /** 转义放进双引号里会失去字面意义的字符。 */
    private static String escapeInDoubleQuotes(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\' || c == '$' || c == '`') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 从创建工作目录的回显里取出可用的绝对路径；取不到返回 null。
     *
     * <p><b>不再退回 {@code ~/...} 形式。</b>那种形式的 {@code ~} 在后续被引号
     * 包住时同样不会展开，等于把一个必然失败的值悄悄传下去——还不如当场说
     * 「建不出来」，让界面去提示。
     */
    private String absoluteFrom(SshTransport.Result r) {
        if (r == null || !r.ok()) {
            lastFailure = describeRun(r);
            return null;
        }
        String path = lastMeaningfulLine(r.stdoutText());
        if (!isUsableRemoteWorkdir(path)) {
            lastFailure = "命令回显的不是可用的绝对路径：[" + path + "]";
            return null;
        }
        lastFailure = "";
        return path;
    }

    /** 把一次失败的远端执行说清楚。 */
    private static String describeRun(SshTransport.Result r) {
        if (r == null) {
            return "远端没有返回结果";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("远端命令未成功：exit=").append(r.exitCode);
        if (r.timedOut) {
            sb.append("（超时被杀）");
        }
        String err = r.stderrText();
        if (err != null && !err.trim().isEmpty()) {
            String first = err.trim().split("\\r?\\n")[0];
            sb.append("；stderr: ").append(first);
        }
        String out = r.stdoutText();
        if (out != null && !out.trim().isEmpty()) {
            String first = out.trim().split("\\r?\\n")[0];
            sb.append("；stdout: ").append(first);
        }
        return sb.toString();
    }

    /**
     * 回显出来的路径能不能当「远端工作目录的绝对路径」用。
     *
     * <p>光看「以 {@code /} 开头」是不够的：那个 bug 产出的
     * {@code /home/qw/~/vmadapt_work/x} 语法上完全合法，但它指的是一个名字叫
     * {@code ~} 的目录，不是用户的 HOME。所以这里要求路径里<b>不含任何
     * {@code ~}</b>——展开过的绝对路径不该再有它，还留着就说明展开没发生。
     */
    public static boolean isUsableRemoteWorkdir(String path) {
        if (path == null) {
            return false;
        }
        String p = path.trim();
        if (p.isEmpty() || !p.startsWith("/")) {
            return false;
        }
        return p.indexOf('~') < 0;
    }

    /**
     * 核对若干远端路径是否真的存在，返回同样长度的列表，缺失的位置为 null。
     *
     * <p>为什么需要它：<b>上传成功不等于东西落在我们以为的地方。</b>scp 的落点
     * 由远端决定，而调用方过去只是把「目标目录 + 本地文件名」拼出来当作既成
     * 事实写进参数，从不核对。一旦两者不一致，用户会同时看到「已复制成功」和
     * 工具报「目录不存在」，而且没有任何线索指向真正的分歧点。
     *
     * <p>一次调用问完所有路径，不为每个文件各起一次连接。
     */
    public List<String> verifyPresent(List<String> remotePaths) {
        if (remotePaths == null || remotePaths.isEmpty()) {
            return new ArrayList<String>();
        }
        if (transport == null) {
            return presentFrom(remotePaths, null);
        }
        StringBuilder sb = new StringBuilder();
        for (String p : remotePaths) {
            sb.append("[ -e ").append(Shell.quote(p)).append(" ] && echo YES || echo NO\n");
        }
        SshTransport.Result r = transport.runBashCapture(sb.toString());
        return presentFrom(remotePaths, r == null ? null : r.stdout);
    }

    /** 把 YES/NO 回显翻成「路径或 null」；抽出来便于直接测。 */
    public static List<String> presentFrom(List<String> remotePaths,
                                           List<String> output) {
        List<String> out = new ArrayList<String>();
        if (remotePaths == null) {
            return out;
        }
        for (int i = 0; i < remotePaths.size(); i++) {
            boolean ok = output != null && i < output.size()
                    && "YES".equals(output.get(i).trim());
            out.add(ok ? remotePaths.get(i) : null);
        }
        return out;
    }

    /**
     * 确保远端目录存在（{@code mkdir -p}）。
     *
     * <h2>为什么上传之前必须调它</h2>
     *
     * <p>{@code scp} <b>不会创建目标目录</b>，而且在目标不存在时的行为具有误导性：
     * {@code scp -r pep host:/work/inputs} 在 {@code /work/inputs} 不存在时，
     * 会把 <b>{@code inputs} 本身</b>建成 {@code pep} 的副本——于是调用方以为
     * 「文件在 {@code /work/inputs/pep}」，实际那里什么都没有，工具随后报
     * 「目录不存在」，而真正的原因（目标目录从来没建过）一个字都没出现。
     *
     * <p>WSL 侧没这个问题，因为那边的复制脚本里本来就带 {@code mkdir -p}；
     * SSH 侧走 scp，得自己补上。两条通道的前置条件必须一致。
     *
     * @param remoteDir 远端目录
     * @return 目录可用返回 true
     */
    public boolean ensureDir(String remoteDir) {
        if (transport == null || remoteDir == null || remoteDir.trim().isEmpty()) {
            return false;
        }
        SshTransport.Result r =
                transport.runBashCapture(ensureDirScript(remoteDir.trim()));
        return r != null && r.ok();
    }

    /** {@code mkdir -p} 一段脚本；抽出来便于直接测引号是否正确。 */
    public static String ensureDirScript(String remoteDir) {
        return "mkdir -p " + Shell.quote(remoteDir) + "\n";
    }

    /** 清理工作目录，失败返回 false。 */
    public boolean removeWorkDir(String remoteDir) {        if (transport == null || remoteDir == null || remoteDir.trim().isEmpty()) {
            return false;
        }
        SshTransport.Result r =
                transport.runBashCapture("rm -rf " + Shell.quote(remoteDir.trim()));
        return r != null && r.ok();
    }

    // ------------------------------------------------------------------
    // scp / sftp 目标串
    // ------------------------------------------------------------------

    /**
     * 把远端路径拼成 scp/sftp 目标 {@code "user@host:path"}。
     *
     * <p>路径含空格、中文或 shell 元字符时用单引号包裹。这里包的是"远端路径"这一段，
     * 用户名和主机名必须留在引号外面，否则 scp 会把整串当成主机名。</p>
     *
     * <p>含单引号的路径用 shell 标准的 {@code '\''} 转义，避免路径被截断。</p>
     */
    public String sftpTarget(String remotePath) {
        String p = (remotePath == null) ? "" : remotePath.trim();
        return userHost() + ":" + quoteRemotePath(p);
    }

    /** 只要 {@code "user@host"} 部分，供 UI 显示。 */
    public String userHost() {
        String user = (target.user == null) ? "" : target.user.trim();
        String host = (target.host == null) ? "" : target.host.trim();
        return user.isEmpty() ? host : (user + "@" + host);
    }

    /**
     * 按需给远端路径加单引号。已经带引号的路径不再重复包裹，否则会变成
     * 字面量 {@code "'/path'"}。
     */
    public static String quoteRemotePath(String path) {
        if (path == null || path.isEmpty()) {
            return path == null ? "" : path;
        }
        if (!needsQuoting(path)) {
            return path;
        }
        StringBuilder sb = new StringBuilder("'");
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '\'') {
                sb.append("'\\''");
            } else {
                sb.append(c);
            }
        }
        return sb.append('\'').toString();
    }

    private static boolean needsQuoting(String s) {
        if (s.length() >= 2 && s.charAt(0) == '\'' && s.charAt(s.length() - 1) == '\'') {
            return false; // 已经引号包裹
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\'' || c == '"' || c == '\\' || c == ';'
                    || c == '&' || c == '|' || c == '<' || c == '>' || c == '(' || c == ')'
                    || c == '$' || c == '`' || c == '*' || c == '?' || c == '[' || c == ']'
                    || c == '{' || c == '}' || c == '~' || c == '#' || c == '!' || c == '\n') {
                return true;
            }
            // 中文（非 ASCII）路径在部分 ssh 配置下需要引号保护，统一加上更安全。
            if (c > 127) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 路径形态判断
    // ------------------------------------------------------------------

    /**
     * 判断是否是 Windows 本地路径。
     *
     * <p>支持三种形态：盘符绝对路径 {@code D:\data\x.fa}、盘符相对路径
     * {@code C:data}、UNC 网络路径 {@code \\server\share\x}。同时识别正斜杠写法
     * {@code D:/data/x.fa}，因为 Java 和资源管理器都接受它。</p>
     */
    public static boolean looksLikeWindowsPath(String p) {
        if (p == null) {
            return false;
        }
        String s = p.trim();
        if (s.length() < 2) {
            return false;
        }
        if (s.startsWith("\\\\")) {
            return true; // UNC
        }
        char c0 = s.charAt(0);
        boolean drive = ((c0 >= 'A' && c0 <= 'Z') || (c0 >= 'a' && c0 <= 'z'))
                && (s.charAt(1) == ':' || s.charAt(1) == '：');
        if (!drive) {
            return false;
        }
        if (s.length() == 2) {
            return true; // "D:"
        }
        char c2 = s.charAt(2);
        return c2 == '\\' || c2 == '/';
    }

    /**
     * 判断是否是 POSIX 绝对路径或明显的远端写法。
     *
     * <p>{@code /home/u/x.fa}、{@code ~/vmadapt_work/x}、{@code $HOME/x} 都算；
     * 纯相对路径如 {@code data/x.fa} 不算，因为它无法判断是本地还是远端。</p>
     */
    public static boolean looksLikePosixPath(String p) {
        if (p == null) {
            return false;
        }
        String s = p.trim();
        if (s.isEmpty()) {
            return false;
        }
        if (s.startsWith("/")) {
            return true;
        }
        if (s.equals("~") || s.startsWith("~/")) {
            return true;
        }
        return s.startsWith("$HOME/") || s.equals("$HOME");
    }

    /**
     * 校验一个"远端路径"输入是否合理，合理返回 null，否则返回英文原因。
     * 用于在启动传输前就拦住用户误填的 {@code D:\data\x.fa}。
     */
    public static String validateRemotePath(String p) {
        if (p == null || p.trim().isEmpty()) {
            return "The remote path must not be empty";
        }
        String s = p.trim();
        if (s.indexOf('\0') >= 0) {
            return "The remote path contains an illegal character";
        }
        if (looksLikeWindowsPath(s)) {
            return "The remote path looks like a Windows path (" + s + "); on the remote Linux host use a form such as /home/... or ~/...";
        }
        if (!looksLikePosixPath(s)) {
            return "Use an absolute path for the remote path (/home/... or ~/...); the current value is: " + s;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 远端信息查询
    // ------------------------------------------------------------------

    /**
     * 读取远端文件大小，单位字节。
     *
     * <p>为什么不用 {@code stat -c %s}：GNU coreutils 与 BSD/macOS 的 stat 参数不兼容，
     * Linux 上 {@code -c}，macOS 上要写 {@code -f%z}。这里用 {@code wc -c} 读文件，
     * 只依赖 POSIX 基本工具，跨发行版都成立。目录返回 -1。</p>
     *
     * @return 文件大小；不存在、是目录或查询失败返回 -1
     */
    public long remoteSize(String remotePath) {
        if (transport == null || remotePath == null || remotePath.trim().isEmpty()) {
            return -1;
        }
        String q = Shell.quote(remotePath.trim());
        // 先排除目录，再取字节数；两个判断都在远端完成，只往返一次。
        String script = "if [ -f " + q + " ]; then wc -c < " + q + " | tr -d ' \\r'; else echo -1; fi";
        SshTransport.Result r = transport.runBashCapture(script);
        if (r == null || !r.ok()) {
            return -1;
        }
        return parseFirstLong(r.stdoutText());
    }

    /** 远端路径是否存在（文件或目录都算）。 */
    public boolean remoteExists(String remotePath) {
        if (transport == null || remotePath == null || remotePath.trim().isEmpty()) {
            return false;
        }
        String q = Shell.quote(remotePath.trim());
        SshTransport.Result r = transport.runBashCapture("test -e " + q + " && echo yes || echo no");
        if (r == null || !r.ok()) {
            return false;
        }
        return "yes".equals(lastMeaningfulLine(r.stdoutText()));
    }

    /** 远端路径是否是目录。 */
    public boolean remoteIsDir(String remotePath) {
        if (transport == null || remotePath == null || remotePath.trim().isEmpty()) {
            return false;
        }
        String q = Shell.quote(remotePath.trim());
        SshTransport.Result r = transport.runBashCapture("test -d " + q + " && echo yes || echo no");
        if (r == null || !r.ok()) {
            return false;
        }
        return "yes".equals(lastMeaningfulLine(r.stdoutText()));
    }

    /**
     * 判断本地文件是否需要上传。
     *
     * <p>只比大小是刻意的取舍：远端算 md5 对大基因组文件可能比重新传一遍还慢，
     * 而"大小一致"在生物信息场景里已经能挡掉绝大多数重复传输。大小不确定
     * （远端不存在或查询失败）时一律返回 true，宁可多传一次也不能漏传。</p>
     *
     * @return 需要上传返回 true；远端已存在且大小一致返回 false
     */
    public boolean needsUpload(String localPath, String remotePath) {
        if (localPath == null || localPath.trim().isEmpty()) {
            return true;
        }
        java.io.File f = new java.io.File(localPath.trim());
        if (!f.isFile()) {
            return true; // 目录或不存在：交给 scp 处理
        }
        long local = f.length();
        long remote = remoteSize(remotePath);
        if (remote < 0) {
            return true;
        }
        return local != remote;
    }

    /**
     * 在远端目录下为某个本地文件算出目标绝对路径。
     * 用于上传前的尺寸比较与结果回传。
     *
     * <p>不能简单地 {@code stripTrailingSlash(dir) + "/" + name}：远端基目录是根
     * {@code "/"} 时，剥斜杠的结果仍是 {@code "/"}（否则根会变成空串），再拼一次
     * 就得到 {@code "//x.fa"}。多数 shell 会把 {@code //} 当作网络路径前缀
     * （POSIX 允许实现赋予它特殊含义），拼进 scp 目标后行为不可预期。</p>
     */
    public String remoteChild(String remoteDir, String localFileName) {
        return joinRemote(remoteDir, localFileName);
    }

    /**
     * 稳健地把子项拼到远端目录后面：结果里恰好一个分隔斜杠。
     *
     * <p>三种输入都要正确：{@code "/home/u/work"}、{@code "/home/u/work/"}、
     * {@code "/home/u/work///"} 一律拼成 {@code /home/u/work/x.fa}；而根目录
     * {@code "/"} 要拼成 {@code /x.fa} 而不是 {@code //x.fa}——后者在 POSIX 里
     * 允许被实现解释成网络路径前缀，送进 scp 目标会得到不可预期的行为。</p>
     *
     * @param dir  远端目录，可为 {@code "/"} 或以任意多个斜杠结尾
     * @param name 子项名；传入 {@code null} 或空串时返回规范化后的目录本身
     */
    public static String joinRemote(String dir, String name) {
        if (dir == null) {
            return null;
        }
        String base = collapseTrailingSlashes(dir.trim());
        if (name == null || name.isEmpty()) {
            return base;
        }
        if (base.isEmpty()) {
            return "/" + name;
        }
        if (base.endsWith("/")) {
            // 只有根目录会走到这里，base 已经是 "/"，正好当分隔用
            return base + name;
        }
        return base + "/" + name;
    }

    /**
     * 去掉字符串末尾所有斜杠，但保留根目录本身。
     * {@code "/"} 返回 {@code "/"}，{@code "/a///"} 返回 {@code "/a"}，{@code ""} 返回 {@code ""}。
     */
    static String collapseTrailingSlashes(String s) {
        if (s == null || s.isEmpty()) {
            return s == null ? null : "";
        }
        int end = s.length();
        while (end > 1 && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(0, end);
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 去掉标签里的路径分隔符与 shell 元字符，防止拼出越界目录。 */
    static String sanitizeLabel(String label) {
        if (label == null) {
            return "work";
        }
        String s = label.trim();
        if (s.isEmpty()) {
            return "work";
        }
        s = s.replace('\\', '_').replace('/', '_').replace(' ', '_');
        StringBuilder sb = new StringBuilder();
        Set<Character> bad = new HashSet<Character>();
        for (char c : new char[]{':', '*', '?', '"', '<', '>', '|', '\'', '`', '$', '&', ';', '\n', '\r', '\t'}) {
            bad.add(Character.valueOf(c));
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(bad.contains(Character.valueOf(c)) ? '_' : c);
        }
        String out = sb.toString();
        // 过长的标签会让路径超限，截断到 40 个字符足够区分。
        return out.length() > 40 ? out.substring(0, 40) : out;
    }

    /**
     * 去掉末尾斜杠但保留根目录。
     * 保留这个入口是为了兼容已有调用方；实现统一委托给
     * {@link #collapseTrailingSlashes}，避免两处规则漂移。
     */
    static String stripTrailingSlash(String s) {
        return collapseTrailingSlashes(s);
    }

    /** 取输出里最后一个非空行：远端会把 login banner 之类的东西混进 stdout。 */
    static String lastMeaningfulLine(String text) {
        if (text == null) {
            return null;
        }
        String[] lines = text.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String s = lines[i].trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        return null;
    }

    /** 从输出里解析第一个整数，失败返回 -1。 */
    static long parseFirstLong(String text) {
        if (text == null) {
            return -1;
        }
        Matcher m = Pattern.compile("(\\d+)").matcher(text);
        if (!m.find()) {
            return -1;
        }
        try {
            return Long.parseLong(m.group(1));
        } catch (NumberFormatException nfe) {
            return -1;
        }
    }

    /** 把一份路径列表按"本地/远端"分类，供 UI 预览。 */
    public static List<String> classify(List<String> paths) {
        List<String> out = new java.util.ArrayList<String>();
        if (paths == null) {
            return out;
        }
        for (String p : paths) {
            if (p == null) {
                continue;
            }
            String kind;
            if (looksLikeWindowsPath(p)) {
                kind = "Local";
            } else if (looksLikePosixPath(p)) {
                kind = "Remote";
            } else {
                kind = "Unknown";
            }
            out.add(kind + "\t" + p);
        }
        return out;
    }
}

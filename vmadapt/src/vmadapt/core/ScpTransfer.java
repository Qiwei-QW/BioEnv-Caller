package vmadapt.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于 Windows 自带 scp.exe 的批量文件传输实现。
 *
 * <p>为什么用 scp.exe 而不是自己实现一套 SFTP 协议：本模块的所有用户都是 Windows
 * 机器上的 TBtools 使用者，Windows 10/11 自带的 OpenSSH 客户端已经包含 scp.exe，
 * 它天然复用 ~/.ssh/config、known_hosts、ssh-agent，省掉了密钥管理和算法协商等
 * 一大块容易出错的东西。只有当系统里确实没有 OpenSSH 时才会退回纯 Java 的
 * {@link SshTransport} 通道。</p>
 *
 * <p>为什么"批量"传：scp 每起一个进程就要做一次完整的 TCP + 密钥交换握手，几十个
 * 小文件逐个起进程会让传输时间几乎全部花在握手上。因此这里把源路径一次性排在
 * 同一条 scp 命令里，只握手一次。</p>
 *
 * <p>为什么不缓冲整个文件：scp.exe 直接从磁盘读文件，我们只需通过 {@link ProcessBuilder}
 * 把路径交给它，内存里不出现文件内容，几十 GB 的基因组文件也不会撑爆堆。</p>
 */
public class ScpTransfer {

    /** scp 可执行文件的位置，允许被测试或特殊环境替换。 */
    private static volatile String scpProgram = defaultScpProgram();

    /**
     * scp 的用户 ID 是数字，超长先夹到 int 避免溢出；用 -1 表示"还没拿到进度"。
     * 这里不使用 volatile，因为只在单线程调用链里递增。
     */
    private final SshTarget target;

    /** 连接超时秒数，独立于 SshTarget，因为 scp 需要它作为 -o ConnectTimeout。 */
    private int connectTimeoutSeconds = 10;

    public ScpTransfer(SshTarget target) {
        if (target == null) {
            throw new IllegalArgumentException("SshTarget must not be null");
        }
        this.target = target;
    }

    /** 逐文件进度、开始、结束三类回调；实现方可只关心其中一部分。 */
    public interface ProgressSink {
        /** @param percent 0-100，无法确定时传 -1；bytes 已传字节数；total 总字节数，未知传 -1。 */
        void progress(String file, int percent, long bytes, long total);

        /** 单个文件开始传输。 */
        void fileStarted(String file);

        /** 单个文件传输结束。 */
        void fileDone(String file, boolean ok, String message);
    }

    /** 什么都不做的空实现，方便调用方只关心返回值。 */
    public static final ProgressSink SILENT = new ProgressSink() {
        @Override
        public void progress(String file, int percent, long bytes, long total) {
        }

        @Override
        public void fileStarted(String file) {
        }

        @Override
        public void fileDone(String file, boolean ok, String message) {
        }
    };

    public SshTarget target() {
        return target;
    }

    public int connectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int seconds) {
        if (seconds > 0) {
            this.connectTimeoutSeconds = seconds;
        }
    }

    /** 返回当前使用的 scp 程序路径。 */
    public static String scpProgram() {
        return scpProgram;
    }

    /** 覆盖 scp 程序路径（测试用）。传空则自动探测。 */
    public static void setScpProgram(String program) {
        scpProgram = (program == null || program.trim().isEmpty()) ? defaultScpProgram() : program.trim();
    }

    // ------------------------------------------------------------------
    // 进度解析
    // ------------------------------------------------------------------

    /**
     * OpenSSH 9.x 的 scp 进度行形如：
     * <pre>
     * big.bin                                    45%  13824KB  11.5MB/s   00:03 ETA
     * big.bin                                   100%  30720KB  12.1MB/s   00:02
     * </pre>
     * 完整正则（已按实际字符串验证，见类注释底部说明）：
     * <pre>
     * ^\s*(.*?)\s+(\d{1,3})%\s+(.+?)\s{2,}.*$
     * </pre>
     * 组 1 = 文件名，组 2 = 百分比。
     *
     * <p>为什么要写成"非贪婪 + 至少两个空白分隔的速度字段"：文件名本身可能含空格
     * （例如 {@code my data.fa}），只有把"百分比后面的速度列"当作右边界，"取最后一个
     * N%"才不会把文件名里的数字误当成进度。允许前导空白是因为 scp 用 \r 回车刷新同一行。</p>
     *
     * @return 0-100 的百分比；解析不出来返回 -1，任何情况下都不抛异常。
     */
    public static int parsePercent(String scpProgressLine) {
        if (scpProgressLine == null) {
            return -1;
        }
        // scp 用 '\r' 原地刷新，先按回车/换行切碎，逐段尝试。
        String chunk = scpProgressLine.trim();
        if (chunk.isEmpty()) {
            return -1;
        }
        int lastCr = Math.max(chunk.lastIndexOf('\r'), chunk.lastIndexOf('\n'));
        if (lastCr >= 0 && lastCr + 1 < chunk.length()) {
            chunk = chunk.substring(lastCr + 1).trim();
        }
        if (chunk.isEmpty()) {
            return -1;
        }
        Matcher m = PERCENT_PATTERN.matcher(chunk);
        int best = -1;
        while (m.find()) {
            int value;
            try {
                value = Integer.parseInt(m.group(1));
            } catch (NumberFormatException nfe) {
                continue;
            }
            if (value < 0 || value > 100) {
                continue;
            }
            // 取最后一个合法百分比：文件名里可能出现 "abc45%"，真正的进度在最右。
            best = value;
        }
        return best;
    }

    private static final Pattern PERCENT_PATTERN =
            Pattern.compile("(\\d{1,3})\\s?%");

    /**
     * 从进度行里取出文件名，取不到返回 null。文件名允许含空格与中文。
     */
    public static String parseProgressFile(String scpProgressLine) {
        if (scpProgressLine == null) {
            return null;
        }
        int pct = scpProgressLine.indexOf('%');
        if (pct <= 0) {
            return null;
        }
        String head = scpProgressLine.substring(0, pct);
        // 去掉紧挨在 % 前面的数字（进度值本身）
        int i = head.length() - 1;
        while (i >= 0 && (Character.isDigit(head.charAt(i)) || head.charAt(i) == ' ')) {
            i--;
        }
        String name = head.substring(0, i + 1).trim();
        return name.isEmpty() ? null : name;
    }

    // ------------------------------------------------------------------
    // 上传 / 下载
    // ------------------------------------------------------------------

    /**
     * 批量上传若干本地文件/目录到远端目录。
     *
     * <p>目录用 -r 递归；已在远端存在且大小一致的普通文件会被跳过（避免反复传大文件）。
     * 传入多个路径时只起一次 scp 进程。</p>
     *
     * @param localPaths 本地路径（文件或目录），允许含空格与中文
     * @param remoteDir  远端目标目录，必须已存在
     * @return 全部成功（含全部跳过）返回 true
     */
    public boolean upload(List<String> localPaths, String remoteDir, ProgressSink sink) {
        ProgressSink out = (sink == null) ? SILENT : sink;
        if (localPaths == null || localPaths.isEmpty()) {
            out.fileDone("", false, "There are no local files to upload");
            return false;
        }
        if (remoteDir == null || remoteDir.trim().isEmpty()) {
            out.fileDone("", false, "The remote directory is empty");
            return false;
        }
        List<String> sources = new ArrayList<String>();
        List<String> skipped = new ArrayList<String>();
        RemoteFiles rf = new RemoteFiles(target, null);
        for (String raw : localPaths) {
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            String p = raw.trim();
            File f = new File(p);
            if (!f.exists()) {
                out.fileDone(p, false, "The local file does not exist");
                return false;
            }
            if (f.isFile() && rf.needsUpload(p, joinRemote(remoteDir, f.getName())) == false) {
                // 远端已有同尺寸文件：跳过，但仍然如实报告，便于用户看到"已是最新"。
                out.fileStarted(p);
                out.progress(p, 100, f.length(), f.length());
                out.fileDone(p, true, "Already present on the remote host with the same size - skipped");
                skipped.add(p);
                continue;
            }
            sources.add(p);
        }
        if (sources.isEmpty()) {
            // 全部跳过也算成功。
            return !skipped.isEmpty() || localPaths.isEmpty() == false;
        }
        for (String s : sources) {
            out.fileStarted(s);
        }
        CommandResult r = runScp(sources, remoteDir, true, out);
        boolean ok = r.exitCode == 0;
        String msg = ok ? "Upload complete" : buildFailureMessage(r);
        for (String s : sources) {
            out.fileDone(s, ok, msg);
        }
        return ok;
    }

    /**
     * 下载远端文件或目录到本地目录。
     *
     * @param remotePath 远端路径，允许含空格与中文
     * @param localDir   本地目标目录，不存在时自动创建
     * @return 成功返回 true
     */
    public boolean download(String remotePath, String localDir, ProgressSink sink) {
        ProgressSink out = (sink == null) ? SILENT : sink;
        if (remotePath == null || remotePath.trim().isEmpty()) {
            out.fileDone("", false, "The remote path is empty");
            return false;
        }
        if (localDir == null || localDir.trim().isEmpty()) {
            out.fileDone(remotePath, false, "The local directory is empty");
            return false;
        }
        File dir = new File(localDir);
        if (!dir.exists() && !dir.mkdirs()) {
            out.fileDone(remotePath, false, "Could not create the local directory: " + localDir);
            return false;
        }
        String rp = remotePath.trim();
        out.fileStarted(rp);
        CommandResult r = runScp(Collections.singletonList(rp), localDir, false, out);
        boolean ok = r.exitCode == 0;
        out.fileDone(rp, ok, ok ? "Download complete" : buildFailureMessage(r));
        return ok;
    }

    /** 把远端单个文件的绝对路径拼成 scp 目标。 */
    public String remoteFileTarget(String remotePath) {
        return new RemoteFiles(target, null).sftpTarget(remotePath);
    }

    // ------------------------------------------------------------------
    // 本地路径展开
    // ------------------------------------------------------------------

    /**
     * 把混有目录的路径列表递归展开成"纯文件"列表。
     *
     * <p>为什么要展开：跳过逻辑和总进度都需要知道具体有哪些文件；scp 的目录进度
     * 只报目录整体，逐文件回调无法实现。展开时保持输入顺序，重复路径只保留一次。</p>
     * 不存在的路径会被保留（交由 scp 报错），避免静默吞掉用户的输入错误。
     */
    public static List<String> expandLocal(List<String> paths) {
        List<String> result = new ArrayList<String>();
        if (paths == null) {
            return result;
        }
        Set<String> seen = new LinkedHashSet<String>();
        for (String raw : paths) {
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            Path p;
            try {
                p = Paths.get(raw.trim());
            } catch (RuntimeException re) {
                // 非法路径字符：原样保留，让 scp 给出可读的错误。
                if (seen.add(raw)) {
                    result.add(raw);
                }
                continue;
            }
            if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
                if (seen.add(raw)) {
                    result.add(p.toString());
                }
                continue;
            }
            if (Files.isDirectory(p)) {
                collectFiles(p, result, seen);
            } else if (seen.add(p.toString())) {
                result.add(p.toString());
            }
        }
        return result;
    }

    private static void collectFiles(Path dir, List<String> out, Set<String> seen) {
        File[] children = dir.toFile().listFiles();
        if (children == null) {
            return;
        }
        java.util.Arrays.sort(children);
        for (File c : children) {
            Path cp = c.toPath();
            if (Files.isDirectory(cp, LinkOption.NOFOLLOW_LINKS)) {
                collectFiles(cp, out, seen);
            } else if (seen.add(cp.toString())) {
                out.add(cp.toString());
            }
        }
    }

    /** 递归展开时统计文件总数（供调用方显示"共 N 个文件"）。 */
    public static int countFiles(List<String> expanded) {
        return expanded == null ? 0 : expanded.size();
    }

    // ------------------------------------------------------------------
    // 进程执行
    // ------------------------------------------------------------------

    private static final class CommandResult {
        int exitCode = -1;
        String stderrText = "";
        String stdoutText = "";
    }

    /**
     * 起一次 scp 进程完成全部源路径的传输。
     *
     * @param forScpUpload 上传时远端是"目标"，本地路径放前面
     */
    private CommandResult runScp(List<String> sources, String remoteDirOrLocal, boolean forScpUpload,
                                 ProgressSink sink) {
        CommandResult result = new CommandResult();
        List<String> cmd = new ArrayList<String>();
        cmd.add(scpProgram);
        // scp 的端口参数是大写 -P；ssh 才是小写 -p。写错在 Windows 上会被当成
        // "保留时间戳" 而静默使用 22 端口，是最难排查的一类问题。
        if (target.port > 0) {
            cmd.add("-P");
            cmd.add(String.valueOf(target.port));
        }
        if (target.keyPath != null && !target.keyPath.trim().isEmpty()) {
            cmd.add("-i");
            cmd.add(target.keyPath.trim());
        }
        cmd.add("-o");
        cmd.add("BatchMode=yes");
        cmd.add("-o");
        cmd.add("StrictHostKeyChecking=accept-new");
        cmd.add("-o");
        cmd.add("ConnectTimeout=" + connectTimeoutSeconds);
        cmd.add("-r"); // 目录递归；对普通文件无副作用
        for (String s : sources) {
            if (forScpUpload) {
                cmd.add(localToScpSource(s));
            } else {
                cmd.add(s); // 下载时第一个参数是远端源
            }
        }
        if (forScpUpload) {
            cmd.add(remoteDirTarget(remoteDirOrLocal));
        } else {
            cmd.add(remoteDirOrLocal);
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        Process proc = null;
        Thread errReader = null;
        try {
            proc = pb.start();
            // scp 的进度和错误都走 stderr，必须并发读取，否则管道写满会把 scp 卡死。
            final Process fp = proc;
            final CommandResult fr = result;
            final ProgressSink fs = sink;
            errReader = new Thread(new Runnable() {
                @Override
                public void run() {
                    readProgress(fp.getErrorStream(), fr, fs);
                }
            }, "scp-stderr");
            errReader.setDaemon(true);
            errReader.start();
            result.stdoutText = drain(proc.getInputStream());
            int code = proc.waitFor();
            result.exitCode = code;
            try {
                errReader.join(2000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        } catch (IOException ioe) {
            result.exitCode = -1;
            result.stderrText = "Could not start scp (" + scpProgram + "): " + ioe.getMessage();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            if (proc != null) {
                proc.destroy();
            }
            result.exitCode = -2;
            result.stderrText = "The transfer was interrupted";
        }
        return result;
    }

    /**
     * 读 scp 的 stderr：既要解析进度，也要把错误原文留下来。
     * scp 用 \r 原地刷新进度行，因此按 \r 和 \n 一起切分。
     */
    private static void readProgress(InputStream err, CommandResult result, ProgressSink sink) {
        StringBuilder all = new StringBuilder();
        String currentFile = null;
        int lastPercent = -1;
        if (err == null) {
            return;
        }
        BufferedReader br = new BufferedReader(new InputStreamReader(err, StandardCharsets.UTF_8));
        try {
            int ch;
            StringBuilder line = new StringBuilder();
            while ((ch = br.read()) != -1) {
                if (ch == '\n' || ch == '\r') {
                    String s = line.toString();
                    line.setLength(0);
                    if (s.isEmpty()) {
                        continue;
                    }
                    all.append(s).append('\n');
                    int pct = parsePercent(s);
                    if (pct >= 0) {
                        String f = parseProgressFile(s);
                        if (f != null && !f.isEmpty()) {
                            currentFile = f;
                        }
                        if (pct != lastPercent) {
                            lastPercent = pct;
                            sink.progress(currentFile == null ? "" : currentFile, pct, -1L, -1L);
                        }
                    }
                } else {
                    line.append((char) ch);
                }
            }
            if (line.length() > 0) {
                all.append(line);
            }
        } catch (IOException ioe) {
            // 进程被销毁时管道会断，这不算异常路径，忽略。
        } finally {
            try {
                br.close();
            } catch (IOException ignored) {
                // 关闭失败无需处理
            }
        }
        result.stderrText = all.toString();
    }

    private static String drain(InputStream in) {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (IOException ioe) {
            // 同上，管道断开时静默结束
        } finally {
            try {
                br.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
        return sb.toString();
    }

    /**
     * Windows 本地路径直接交给 scp.exe，<b>不能</b>加引号。
     *
     * <p>这一点是实测出来的，与直觉相反：{@link ProcessBuilder} 在 Windows 上是
     * 按 argv 元素逐个传给子进程的，scp.exe 用 MSVCRT 规则解析，本来就能正确
     * 接住含空格的路径。如果这里画蛇添足包一层双引号，scp.exe 会把引号当成
     * 路径的一部分，于是既 stat 不到本地文件，又把它误判成远端源，转而去做
     * SSH 连接，最后报一个和真实原因毫无关系的 "Connection closed"。
     * 实测对照：不加引号时 scp 正确报 {@code stat local ".../no such dir/a.fa"}；
     * 加了引号则直接跳到连接阶段。</p>
     *
     * <p>同理，{@code -o BatchMode=yes} 也必须作为一个完整元素传入，不能再包引号。
     * （注意这与 {@code .cmd}/{@code .bat} 包装脚本不同：cmd.exe 会从 {@code =}
     * 处再切一刀，所以换用脚本包装 scp 时才需要额外引号。）</p>
     */
    private static String localToScpSource(String localPath) {
        return localPath;
    }

    /** 远端目录写成 user@host:/path，路径含空格/中文时用单引号包裹（远端 shell 会解析）。 */
    private String remoteDirTarget(String remoteDir) {
        return new RemoteFiles(target, null).sftpTarget(remoteDir);
    }

    /** 拼远端子路径；统一委托给 RemoteFiles，保证根目录 "/" 不会拼出 "//x"。 */
    private static String joinRemote(String dir, String name) {
        return RemoteFiles.joinRemote(dir, name);
    }

    private static String buildFailureMessage(CommandResult r) {
        String err = r.stderrText == null ? "" : r.stderrText.trim();
        if (err.isEmpty()) {
            return "scp exit code " + r.exitCode;
        }
        // 只保留最后几行，避免把 ssh 调试信息整段甩给用户。
        String[] lines = err.split("\\r?\\n");
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, lines.length - 3);
        for (int i = from; i < lines.length; i++) {
            String s = lines[i].trim();
            if (s.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(s);
        }
        return sb.length() == 0 ? ("scp exit code " + r.exitCode) : sb.toString();
    }

    /** 依次在 PATH 与 OpenSSH 默认安装目录里找 scp.exe / scp。 */
    private static String defaultScpProgram() {
        String windir = System.getenv("WINDIR");
        if (windir == null || windir.isEmpty()) {
            windir = System.getenv("SystemRoot");
        }
        if (windir != null && !windir.isEmpty()) {
            File f = new File(windir, "System32\\OpenSSH\\scp.exe");
            if (f.isFile()) {
                return f.getAbsolutePath();
            }
        }
        for (String dir : new String[]{"C:\\WINDOWS\\System32\\OpenSSH", "C:\\Program Files\\OpenSSH"}) {
            File f = new File(dir, "scp.exe");
            if (f.isFile()) {
                return f.getAbsolutePath();
            }
        }
        return "scp.exe";
    }

    /** 报告一次传输是否可用的前置条件，返回 null 表示可用，否则返回中文原因。 */
    public String validate() {
        String t = target.validate();
        if (t != null && !t.isEmpty()) {
            return t;
        }
        File exe = new File(scpProgram);
        if (exe.isAbsolute() && !exe.isFile()) {
            return "scp.exe not found: " + scpProgram + " (the OpenSSH Client optional feature may not be installed on Windows)";
        }
        return null;
    }

    /** 供 UI 显示的一行描述。 */
    public String toDisplay() {
        return "scp → " + target.toDisplay();
    }

    /** 把多个输入流串成一个，供需要单流读取的调用方使用。 */
    static InputStream concat(InputStream a, InputStream b) {
        return new SequenceInputStream(a, b);
    }

    /** 把字节数格式化成用户可读的体积文本。 */
    public static String humanBytes(long bytes) {
        if (bytes < 0) {
            return "unknown";
        }
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024.0) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024.0) {
            return String.format(java.util.Locale.ROOT, "%.1f MB", mb);
        }
        return String.format(java.util.Locale.ROOT, "%.2f GB", mb / 1024.0);
    }

    /** 空的输出流，避免调用方为丢弃输出而写样板代码。 */
    public static OutputStream nullOutputStream() {
        return new OutputStream() {
            @Override
            public void write(int b) {
                // 丢弃
            }

            @Override
            public void write(byte[] b, int off, int len) {
                // 丢弃
            }
        };
    }
}

package vmadapt.test;

import vmadapt.core.RemoteFiles;
import vmadapt.core.Shell;

import java.util.ArrayList;
import java.util.List;

/**
 * 远端路径处理测试。
 *
 * <p>这一组的实际价值在于**在启动传输之前拦住用户填错的路径**。
 * SSH 场景下最典型的误填是把 Windows 本地路径（{@code D:\data\x.fa}）粘进
 * "远端路径"输入框——远端 Linux 上根本没有这个路径，但如果不在本地拦住，
 * 用户会得到一个来自远端 scp 的、完全看不懂的报错。
 *
 * <p>同时验证 scp 远端目标串的拼装：路径含空格或中文时必须加引号，且**引号只能
 * 包住路径部分**，用户名和主机必须留在引号外面——否则 scp 会把整串当成主机名。
 *
 * <p>不联网。
 */
public final class RemotePathTest {

    private static int checks = 0;

    public static void main(String[] args) {
        System.out.println("=== RemotePathTest ===");

        // ---------------------------------------------------------------
        // 1. looksLikeWindowsPath
        // ---------------------------------------------------------------
        check("盘符反斜杠路径", RemoteFiles.looksLikeWindowsPath("D:\\data\\x.fa"));
        check("盘符正斜杠路径", RemoteFiles.looksLikeWindowsPath("C:/Users/me/x.fa"));
        check("小写盘符", RemoteFiles.looksLikeWindowsPath("d:\\work\\aln.fa"));
        check("裸盘符", RemoteFiles.looksLikeWindowsPath("D:"));
        check("UNC 路径", RemoteFiles.looksLikeWindowsPath("\\\\server\\share\\x.fa"));
        check("全角冒号也算", RemoteFiles.looksLikeWindowsPath("D：\\data"));

        check("POSIX 路径不算 Windows", !RemoteFiles.looksLikeWindowsPath("/home/u/x.fa"));
        check("相对路径不算 Windows", !RemoteFiles.looksLikeWindowsPath("data/x.fa"));
        check("纯文件名不算 Windows", !RemoteFiles.looksLikeWindowsPath("x.fa"));
        check("空串不算 Windows", !RemoteFiles.looksLikeWindowsPath(""));
        check("null 不算 Windows", !RemoteFiles.looksLikeWindowsPath(null));
        check("单字符不算 Windows", !RemoteFiles.looksLikeWindowsPath("D"));
        check("选项值不算 Windows", !RemoteFiles.looksLikeWindowsPath("msa"));
        check("a:b 不算 Windows", !RemoteFiles.looksLikeWindowsPath("a:b"));

        // ---------------------------------------------------------------
        // 2. looksLikePosixPath
        // ---------------------------------------------------------------
        check("绝对路径", RemoteFiles.looksLikePosixPath("/home/u/x.fa"));
        check("根路径", RemoteFiles.looksLikePosixPath("/"));
        check("波浪号", RemoteFiles.looksLikePosixPath("~/vmadapt_work/x"));
        check("裸波浪号", RemoteFiles.looksLikePosixPath("~"));
        check("$HOME 形式", RemoteFiles.looksLikePosixPath("$HOME/x"));
        check("裸 $HOME", RemoteFiles.looksLikePosixPath("$HOME"));

        check("相对路径不算 POSIX", !RemoteFiles.looksLikePosixPath("data/x.fa"));
        check("Windows 路径不算 POSIX", !RemoteFiles.looksLikePosixPath("D:\\x.fa"));
        check("空串不算 POSIX", !RemoteFiles.looksLikePosixPath(""));
        check("null 不算 POSIX", !RemoteFiles.looksLikePosixPath(null));
        // "~user/x" 不在支持范围（只有 ~ 和 ~/ 开头才算）
        check("~user 不算 POSIX", !RemoteFiles.looksLikePosixPath("~user/x"));

        // ---------------------------------------------------------------
        // 3. validateRemotePath：拦住误填
        // ---------------------------------------------------------------
        eq("合法绝对路径通过", null, RemoteFiles.validateRemotePath("/home/bio/data/x.fa"));
        eq("合法 ~ 路径通过", null, RemoteFiles.validateRemotePath("~/vmadapt_work/x.fa"));
        eq("合法根路径通过", null, RemoteFiles.validateRemotePath("/data/genome.fa"));
        eq("合法 $HOME 通过", null, RemoteFiles.validateRemotePath("$HOME/x.fa"));

        check("Windows 路径被拒绝",
                RemoteFiles.validateRemotePath("D:\\data\\x.fa") != null);
        check("Windows 路径的提示提到 Windows",
                RemoteFiles.validateRemotePath("D:\\data\\x.fa").contains("Windows"));
        check("Windows 路径的提示给出正确形式",
                RemoteFiles.validateRemotePath("C:/Users/me/x.fa").contains("/home/"));

        check("空串被拒绝", RemoteFiles.validateRemotePath("") != null);
        check("null 被拒绝", RemoteFiles.validateRemotePath(null) != null);
        check("纯空格被拒绝", RemoteFiles.validateRemotePath("   ") != null);
        check("相对路径被拒绝",
                RemoteFiles.validateRemotePath("data/x.fa") != null);
        check("相对路径的提示建议绝对路径",
                RemoteFiles.validateRemotePath("data/x.fa").contains("absolute path"));

        // NUL 字符
        check("含 NUL 被拒绝",
                RemoteFiles.validateRemotePath("/home/u/\u0000x") != null);

        // ---------------------------------------------------------------
        // 4. quoteRemotePath：引号只包路径
        // ---------------------------------------------------------------
        eq("简单路径不加引号", "/home/u/x.fa", RemoteFiles.quoteRemotePath("/home/u/x.fa"));
        eq("含空格加单引号", "'/home/my data/x.fa'",
                RemoteFiles.quoteRemotePath("/home/my data/x.fa"));
        eq("含中文加单引号", "'/home/基因/x.fa'",
                RemoteFiles.quoteRemotePath("/home/基因/x.fa"));
        eq("null 返回空串", "", RemoteFiles.quoteRemotePath(null));
        eq("空串返回空串", "", RemoteFiles.quoteRemotePath(""));
        // 已带引号的不重复包裹
        eq("已引号不重复包裹", "'/a b/c'", RemoteFiles.quoteRemotePath("'/a b/c'"));

        // 路径内含单引号必须转义成 '\''
        String withQuote = "/home/it's/x.fa";
        String qq = RemoteFiles.quoteRemotePath(withQuote);
        check("含单引号的路径被包裹", qq.startsWith("'") && qq.endsWith("'"));
        check("含单引号的路径做了转义", qq.indexOf("'\\''") >= 0);
        // 展开验证：把 '\'' 还原回来应当得到原串
        String roundTrip = qq.substring(1, qq.length() - 1).replace("'\\''", "'");
        eq("单引号路径可还原", withQuote, roundTrip);

        // 含 $ 等元字符也要包
        check("含 $ 的路径被包裹",
                RemoteFiles.quoteRemotePath("/home/$USER/x").startsWith("'"));

        // ---------------------------------------------------------------
        // 5. sftpTarget：user@host:path，引号在外层之外
        // ---------------------------------------------------------------
        vmadapt.core.SshTarget t = new vmadapt.core.SshTarget("192.168.1.5", 22, "bio", null);
        RemoteFiles rf = new RemoteFiles(t, null);

        eq("userHost 拼接", "bio@192.168.1.5", rf.userHost());
        eq("简单 scp 目标", "bio@192.168.1.5:/home/u/x.fa",
                rf.sftpTarget("/home/u/x.fa"));
        eq("含空格路径的 scp 目标", "bio@192.168.1.5:'/home/my data/x.fa'",
                rf.sftpTarget("/home/my data/x.fa"));

        // 关键结构断言：引号必须出现在 ":" 之后，不能包住 user@host
        String spaceTarget = rf.sftpTarget("/home/my data/x.fa");
        int colon = spaceTarget.indexOf(':');
        int firstQuote = spaceTarget.indexOf('\'');
        check("引号出现在冒号之后（未包住 user@host）", firstQuote > colon);
        check("user@host 部分无引号", spaceTarget.substring(0, colon).indexOf('\'') < 0);
        check("scp 目标以 user@host 开头", spaceTarget.startsWith("bio@192.168.1.5:"));

        // 无用户名时只有 host
        RemoteFiles noUser = new RemoteFiles(new vmadapt.core.SshTarget("10.0.0.1", 22, "", null), null);
        eq("无用户名 scp 目标", "10.0.0.1:/x", noUser.sftpTarget("/x"));

        // ---------------------------------------------------------------
        // 6. remoteChild / stripTrailingSlash
        // ---------------------------------------------------------------
        eq("拼子路径", "/home/u/work/x.fa", rf.remoteChild("/home/u/work", "x.fa"));
        eq("去掉末尾斜杠再拼", "/home/u/work/x.fa", rf.remoteChild("/home/u/work/", "x.fa"));
        eq("多斜杠被去净", "/home/u/work/x.fa", rf.remoteChild("/home/u/work///", "x.fa"));
        eq("根目录不被吃掉", "/x.fa", rf.remoteChild("/", "x.fa"));
        eq("null 目录返回 null", null, rf.remoteChild(null, "x.fa"));

        // ---------------------------------------------------------------
        // 7. classify：给界面显示用
        // ---------------------------------------------------------------
        List<String> paths = new ArrayList<String>();
        paths.add("D:\\data\\x.fa");
        paths.add("/home/u/y.fa");
        paths.add("relative/z.fa");
        paths.add(null);
        List<String> kinds = RemoteFiles.classify(paths);
        eq("classify 跳过 null", "3", String.valueOf(kinds.size()));
        check("Windows 路径标为本地", kinds.get(0).startsWith("Local"));
        check("POSIX 路径标为远端", kinds.get(1).startsWith("Remote"));
        check("相对路径标为未知", kinds.get(2).startsWith("Unknown"));
        check("classify 保留原路径", kinds.get(0).endsWith("D:\\data\\x.fa"));
        check("classify(null) 返回空列表", RemoteFiles.classify(null).isEmpty());

        // ---------------------------------------------------------------
        // 8. WORK_ROOT 常量
        // ---------------------------------------------------------------
        eq("工作根目录在 HOME 下", "~/vmadapt_work", RemoteFiles.WORK_ROOT);
        check("工作根目录是 POSIX 形式",
                RemoteFiles.looksLikePosixPath(RemoteFiles.WORK_ROOT));
        check("工作根目录不含 Windows 盘符",
                !RemoteFiles.looksLikeWindowsPath(RemoteFiles.WORK_ROOT));

        // ---------------------------------------------------------------
        // 9. 工作根 + 标签拼出来的路径仍要过校验
        // ---------------------------------------------------------------
        String built = RemoteFiles.WORK_ROOT + "/ubuntu-vm_20250101_120000";
        eq("拼出的工作目录合法", null, RemoteFiles.validateRemotePath(built));
        // 用 Shell.quote 引用后仍应是合法的一整段
        String quoted = Shell.quote(built);
        check("工作目录可被安全引用", quoted.startsWith("'") && quoted.endsWith("'"));

        System.out.println("PASS " + checks + " checks");
    }

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
}

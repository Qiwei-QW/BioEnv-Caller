package bioenv.ui;

import java.io.File;
import java.nio.charset.StandardCharsets;

import bioenv.core.AtomicFiles;
import bioenv.core.ConfigWarnings;

/**
 * 「这台机器上是否成功连接过 Linux 虚拟机」的持久记录。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>连接配置对话框的自动弹出规则是：<b>从没连成功过就主动弹出来引导用户；
 * 连成功过就不再打扰</b>。这个判断必须跨会话成立——用户重启 TBtools 之后，
 * 上次已经连通过的事实不该丢失，否则每次启动都会再弹一次，而那正是"打扰"。
 *
 * <h2>为什么单独一个文件而不是塞进 language.txt</h2>
 *
 * <p>{@code language.txt} 的格式被刻意定成"一行裸语言代码"，用户可以手工编辑它。
 * 往里加第二个字段会让那个文件既不像语言文件、也不能再随手改。两个用途各自的
 * 生命周期也不同（语言是偏好，是否连过是事实），分开存互不牵连。
 *
 * <h2>为什么是"写入即文件存在"，而不是写 {@code true}/{@code false}</h2>
 *
 * <p>标记只有两种状态，而"文件在不在"本身就是这两种状态，内容没有信息量。
 * 用存在性表示还有两个好处：
 * <ul>
 *   <li>用户可以`删除该文件`来重置引导流程——不需要知道里面该写什么；</li>
 *   <li>文件损坏（内容被改乱）不会导致误判，因为根本不读内容。</li>
 * </ul>
 * 这也符合用户"清掉标记就再弹"的预期。
 *
 * <h2>失败一律静默</h2>
 *
 * <p>读失败按"没连过"处理（宁可多引导一次，也不要让用户面对一个永远不再弹出的
 * 死界面）；写失败只影响下次启动的引导，不影响本次会话。为此弹框是本末倒置。
 *
 * <p>路径与 {@link bioenv.i18n.I18n#defaultFile()} 同目录，理由见那个方法的注释：
 * 排查插件状态时用户只该翻一个 {@code .TBtools\BioEnvCaller} 目录。
 */
final class LinkState {

    /** 标记文件名。 */
    private static final String FILE_NAME = "linked.txt";

    /** 标记文件路径，可被自检覆盖（见 {@link #setFile(File)}）。 */
    private static volatile File file = defaultFile();

    private LinkState() {
        // 纯静态工具类。
    }

    /**
     * 默认标记位置：{@code %USERPROFILE%\.TBtools\BioEnvCaller\linked.txt}。
     *
     * <p>与语言文件同目录（不是同文件），见类注释。
     */
    static File defaultFile() {
        String home = System.getProperty("user.home");
        if (home == null || home.trim().isEmpty()) {
            home = System.getenv("USERPROFILE");
        }
        if (home == null || home.trim().isEmpty()) {
            home = ".";
        }
        return new File(home.trim(),
                ".TBtools" + File.separator + "BioEnvCaller" + File.separator + FILE_NAME);
    }

    /**
     * 覆盖标记位置。
     *
     * <p>存在的理由只有一个：自检类不能把用户真实的"已连过"状态读进来或改掉。
     * 生产代码不应调用。
     */
    static void setFile(File f) {
        file = (f == null) ? defaultFile() : f;
    }

    /** 当前标记位置。 */
    static File getFile() {
        return file;
    }

    /**
     * 是否曾经连接成功过。
     *
     * <p>判断依据仍然是"标记文件在不在"（见类注释：内容没有信息量）。但
     * "文件在却读不出来"是另一回事：按"没连过"处理，同时报一次并留一份
     * {@code .bak}，而不是静默把用户送回引导流程。
     *
     * @return true 表示连成功过（标记文件存在且可读）；任何读取异常都返回 false
     */
    static boolean hasEverConnected() {
        try {
            File f = file;
            if (f == null || !f.isFile()) {
                return false;
            }
            if (AtomicFiles.read(f, StandardCharsets.UTF_8) != null) {
                return true;
            }
            ConfigWarnings.readFailed(f, AtomicFiles.backup(f));
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 记录"连接成功过"。
     *
     * <p>幂等：重复调用只是重复写同一个标记文件。写失败不影响本次会话——
     * 原子替换与失败提示由 {@link AtomicFiles} 负责，不会再无声无息。
     */
    static void markConnected() {
        try {
            File f = file;
            if (f == null) {
                return;
            }
            // 内容带一行换行：文件是给人看的，空文件在某些编辑器里会被
            // 当成"没能写入"。用 UTF-8 显式写出，避免平台默认编码参与。
            AtomicFiles.write(f, "linked\n", StandardCharsets.UTF_8);
        } catch (RuntimeException ignored) {
            // 见类注释：写不进去只影响下次启动的引导。
        }
    }

    /**
     * 清除记录，让引导流程重新生效。
     *
     * <p>自检用；也方便将来加一个"重新引导"的入口。
     */
    static void clear() {
        try {
            File f = file;
            if (f != null && f.isFile()) {
                f.delete();
            }
        } catch (RuntimeException ignored) {
            // 删不掉等于没清；不弹框。
        }
    }
}

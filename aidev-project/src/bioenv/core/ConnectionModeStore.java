package bioenv.core;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * 记住上次用的是哪个连接方式。
 *
 * <p>为什么需要它：面板每次构造都把「连接方式」重置成 WSL。一个只在 Linux VM
 * 上干活的人，每次打开插件都要手动切一次方式——而且切换本身会丢掉原来的列表，
 * 于是还得再扫一遍。
 *
 * <p>存成一个只有一行的小文件，和 {@code language.txt}、{@code linked.txt}
 * 放在同一个目录里，理由与它们相同：用户要备份、清理或排查插件状态时，
 * 只需要翻一个 {@code .TBtools\BioEnvCaller} 目录。
 *
 * <p>认不出来的一律当作「没记录」，回到默认的 WSL。这里刻意不做任何猜测：
 * 一个读不懂的文件不该把用户带到别的模式去。
 */
public final class ConnectionModeStore {

    /** WSL 方式。 */
    public static final String MODE_WSL = "wsl";
    /** Linux VM 方式。 */
    public static final String MODE_VM = "vm";

    private static final String FILE_NAME = "connection-mode.txt";

    private final File file;

    public ConnectionModeStore() {
        this(defaultFile());
    }

    public ConnectionModeStore(File file) {
        this.file = file;
    }

    /** 默认位置：{@code %USERPROFILE%\.TBtools\BioEnvCaller\connection-mode.txt}。 */
    public static File defaultFile() {
        String home = System.getProperty("user.home");
        File dir = new File(home, ".TBtools" + File.separator + "BioEnvCaller");
        return new File(dir, FILE_NAME);
    }

    public File getFile() {
        return file;
    }

    /**
     * 读取上次使用的连接方式。
     *
     * <p>和以前一样：认不出来就回默认 WSL，不猜。区别只在于"文件坏了"这件事
     * 不再静默——原文件会先备份成 {@code .bak}，界面上会有一行提示。
     *
     * @return {@link #MODE_VM} 或 {@link #MODE_WSL}；文件缺失、损坏或内容无法
     *         识别时返回 {@link #MODE_WSL}
     */
    public String load() {
        if (file == null || !file.isFile()) {
            return MODE_WSL;
        }
        String text = AtomicFiles.read(file, StandardCharsets.UTF_8);
        if (text == null) {
            reportCorrupt();
            return MODE_WSL;
        }
        int nl = text.indexOf('\n');
        String line = nl < 0 ? text : text.substring(0, nl);
        String v = line.trim().toLowerCase();
        if (MODE_VM.equals(v) || MODE_WSL.equals(v)) {
            return v;
        }
        // 内容认不出来就是"这个文件坏了"（旧写法可能留下半行或空文件）。
        // 行为不变（回 WSL，一个读不懂的文件不该把用户带到别的模式去），
        // 但原文件先留一份 .bak，并报一次。
        reportCorrupt();
        return MODE_WSL;
    }

    /** 文件在、但读不出来（或内容不是 wsl/vm）：留 .bak + 报一次。 */
    private void reportCorrupt() {
        ConfigWarnings.readFailed(file, AtomicFiles.backup(file));
    }

    /**
     * 记住这次使用的方式。
     *
     * <p>写失败不抛异常：记不住只是下次要多切一次，不该因此让界面报错——但会
     * 由 {@link AtomicFiles} 报一次，不再完全无声。
     *
     * @param mode {@link #MODE_VM} 或 {@link #MODE_WSL}
     */
    public void save(String mode) {
        if (file == null) {
            return;
        }
        AtomicFiles.write(file,
                (MODE_VM.equals(mode) ? MODE_VM : MODE_WSL) + "\n",
                StandardCharsets.UTF_8);
    }
}

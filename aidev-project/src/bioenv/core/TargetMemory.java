package bioenv.core;

import java.io.File;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * 记住上次用的是哪一个目标 —— 哪个 WSL 发行版、哪一台虚拟机配置。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>{@link ConnectionModeStore} 只记住「WSL 还是 Linux VM」。但一个通道下面可能
 * 有多个目标：本机装了两个 WSL 发行版，或者存了两台虚拟机的配置。只记通道的话，
 * 打开插件时还得再挑一次具体是哪一个，而绝大多数人每次用的都是同一台。
 *
 * <h2>为什么按名字记，而不是按地址</h2>
 *
 * <p>虚拟机配置有自己的名字，按名字回查才能对上用户在下拉框里看到的那一条；
 * 用 {@code user@host:port} 去反推，一旦地址改了或者两台机器恰好写成同一个地址，
 * 就会指错。WSL 发行版本来就只有名字。
 *
 * <h2>认不出来时怎么办</h2>
 *
 * <p>记着的目标已经不存在了（配置被删、发行版被卸载），就当作没记住——
 * 回到「让用户自己选」的状态，而不是硬按一个名字去挑。
 */
public final class TargetMemory {

    private static final String FILE_NAME = "last-target.properties";
    private static final String KEY_WSL = "wslDistro";
    private static final String KEY_VM = "vmProfile";

    private final File file;

    public TargetMemory() {
        this(defaultFile());
    }

    public TargetMemory(File file) {
        this.file = file;
    }

    /** 默认位置：{@code %USERPROFILE%\.TBtools\BioEnvCaller\last-target.properties}。 */
    public static File defaultFile() {
        String home = System.getProperty("user.home");
        File dir = new File(home, ".TBtools" + File.separator + "BioEnvCaller");
        return new File(dir, FILE_NAME);
    }

    public File getFile() {
        return file;
    }

    /** 上次用的 WSL 发行版；没有或读不出来则返回空串。 */
    public String wslDistro() {
        Properties p = read();
        if (p == null) {
            reportUnreadable();
            return "";
        }
        return p.getProperty(KEY_WSL, "").trim();
    }

    /** 上次用的虚拟机配置名；没有或读不出来则返回空串。 */
    public String vmProfile() {
        Properties p = read();
        if (p == null) {
            reportUnreadable();
            return "";
        }
        return p.getProperty(KEY_VM, "").trim();
    }

    /** 记住这次用的 WSL 发行版。 */
    public void rememberWslDistro(String name) {
        put(KEY_WSL, name);
    }

    /** 记住这次用的虚拟机配置名。 */
    public void rememberVmProfile(String name) {
        put(KEY_VM, name);
    }

    /**
     * 读全部记录。
     *
     * <p>这里把"文件不存在"和"文件在但读不出来"分开：前者是全新环境（空记录），
     * 后者必须让调用方知道，否则一次读失败之后的那次写入会把另一个键一起抹掉。
     *
     * @return 记录；文件不存在时是空 Properties，文件在但读不出来时是 null
     */
    private Properties read() {
        if (file == null || !file.isFile()) {
            return new Properties();
        }
        String text = AtomicFiles.read(file, StandardCharsets.UTF_8);
        if (text == null) {
            return null;
        }
        Properties p = new Properties();
        try {
            p.load(new StringReader(text));
        } catch (Exception e) {
            return null;
        }
        return p;
    }

    /** 文件在、但读不出来：留一份 .bak 并报一次，读的一方按"没记住"继续。 */
    private void reportUnreadable() {
        ConfigWarnings.readFailed(file, AtomicFiles.backup(file));
    }

    /**
     * 写入一个键，另一个键原样保留 —— 换 WSL 发行版不该把虚拟机的记录抹掉。
     *
     * <p>读不出来时<b>不写</b>：拿一份空的 Properties 去写，等于把另一个键一起抹掉，
     * 而"另一个键"很可能正是用户上次选的虚拟机。改成留 .bak、报一次、放弃这次记忆。
     */
    private void put(String key, String value) {
        if (file == null) {
            return;
        }
        Properties p = read();
        if (p == null) {
            reportUnreadable();
            ConfigWarnings.reportOnce("配置读不出来，这次选中的目标没有记住（未覆盖原文件）："
                    + file.getAbsolutePath());
            return;
        }
        p.setProperty(key, value == null ? "" : value.trim());
        StringWriter sw = new StringWriter();
        try {
            p.store(sw, "BioEnv Caller last used target");
        } catch (Exception e) {
            ConfigWarnings.writeFailed(file, e);
            return;
        }
        // 建目录、临时文件、原子替换都在 AtomicFiles 里；失败时它已经报过。
        AtomicFiles.write(file, sw.toString(), StandardCharsets.UTF_8);
    }
}

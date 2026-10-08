package bioenv.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
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

    /** 上次用的 WSL 发行版；没有则返回空串。 */
    public String wslDistro() {
        return read().getProperty(KEY_WSL, "").trim();
    }

    /** 上次用的虚拟机配置名；没有则返回空串。 */
    public String vmProfile() {
        return read().getProperty(KEY_VM, "").trim();
    }

    /** 记住这次用的 WSL 发行版。 */
    public void rememberWslDistro(String name) {
        put(KEY_WSL, name);
    }

    /** 记住这次用的虚拟机配置名。 */
    public void rememberVmProfile(String name) {
        put(KEY_VM, name);
    }

    private Properties read() {
        Properties p = new Properties();
        if (file == null || !file.isFile()) {
            return p;
        }
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            p.load(in);
        } catch (Exception e) {
            // 读不出来就当没记住：多问用户一次，好过按一个读坏的记录乱挑。
            return new Properties();
        } finally {
            close(in);
        }
        return p;
    }

    /**
     * 写入一个键，另一个键原样保留 —— 换 WSL 发行版不该把虚拟机的记录抹掉。
     *
     * <p>写失败静默放弃：记不住只是下次多问一次，不该让界面报错。
     */
    private void put(String key, String value) {
        if (file == null) {
            return;
        }
        Properties p = read();
        p.setProperty(key, value == null ? "" : value.trim());

        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            return;
        }
        OutputStream out = null;
        try {
            out = new FileOutputStream(file);
            p.store(out, "BioEnv Caller last used target");
        } catch (Exception e) {
            // 见方法注释
        } finally {
            close(out);
        }
    }

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
                // 没别的可做
            }
        }
    }
}

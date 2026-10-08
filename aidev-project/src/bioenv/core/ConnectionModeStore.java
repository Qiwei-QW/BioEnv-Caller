package bioenv.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;

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
     * @return {@link #MODE_VM} 或 {@link #MODE_WSL}；文件缺失、损坏或内容无法
     *         识别时返回 {@link #MODE_WSL}
     */
    public String load() {
        if (file == null || !file.isFile()) {
            return MODE_WSL;
        }
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), Charset.forName("UTF-8")));
            String line = br.readLine();
            if (line == null) {
                return MODE_WSL;
            }
            String v = line.trim().toLowerCase();
            return MODE_VM.equals(v) ? MODE_VM : MODE_WSL;
        } catch (Exception e) {
            // 读不出来就当没记录过：一次错误的模式恢复比多切一次方式更烦人。
            return MODE_WSL;
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Exception ignored) {
                    // 读已经结束，关不掉也无妨
                }
            }
        }
    }

    /**
     * 记住这次使用的方式。
     *
     * <p>写失败不抛异常：记不住只是下次要多切一次，不该因此让界面报错。
     *
     * @param mode {@link #MODE_VM} 或 {@link #MODE_WSL}
     */
    public void save(String mode) {
        if (file == null) {
            return;
        }
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            return;
        }
        Writer w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(file),
                    Charset.forName("UTF-8"));
            w.write(MODE_VM.equals(mode) ? MODE_VM : MODE_WSL);
            w.write("\n");
        } catch (Exception e) {
            // 见方法注释：写不进去就静默放弃
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Exception ignored) {
                    // 同上
                }
            }
        }
    }
}

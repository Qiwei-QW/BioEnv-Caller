package bioenv.test;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;

import bioenv.core.TargetMemory;

/**
 * 「记住上次用的具体目标」的测试。
 *
 * <p>回归的是一处摩擦：通道记住了（WSL 还是 Linux VM），但通道下面具体是哪一台
 * 没记住。本机装两个 WSL 发行版、或者存了两台虚拟机配置时，每次打开插件都得
 * 再挑一次具体是哪一个——而绝大多数人每次用的都是同一台。
 *
 * <p>另外钉住两条容易写错的地方：<b>两个通道的记录互不覆盖</b>
 * （换 WSL 发行版不该把虚拟机的记录抹掉），以及<b>读不出来时当作没记住</b>
 * 而不是按一个读坏的记录乱挑。
 */
public final class TargetMemoryTest {

    private static int checks;
    private static int failures;

    private TargetMemoryTest() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== TargetMemoryTest ===");
        File dir = Files.createTempDirectory("bioenv-target").toFile();
        File f = new File(dir, "last-target.properties");

        // 1. 什么都没有时是空串，而不是 null 或异常。
        TargetMemory m = new TargetMemory(f);
        check("初始 wslDistro 为空", m.wslDistro().isEmpty());
        check("初始 vmProfile 为空", m.vmProfile().isEmpty());

        // 2. 记住虚拟机，再记住 WSL 发行版：两个都还在。
        m.rememberVmProfile("ceshi");
        check("记住了虚拟机配置名", "ceshi".equals(m.vmProfile()));
        check("记虚拟机不影响 WSL 那项", m.wslDistro().isEmpty());

        m.rememberWslDistro("Ubuntu-22.04");
        check("记住了 WSL 发行版", "Ubuntu-22.04".equals(m.wslDistro()));
        check("两个通道的记录互不覆盖", "ceshi".equals(m.vmProfile()));

        // 3. 换一个发行版：虚拟机那条仍然保留。
        m.rememberWslDistro("Debian");
        check("换发行版后旧值被替换", "Debian".equals(m.wslDistro()));
        check("换发行版不影响虚拟机那条", "ceshi".equals(m.vmProfile()));

        // 4. 另一个实例读同一个文件，说明真的落盘了。
        TargetMemory reread = new TargetMemory(f);
        check("换实例后仍读得到发行版", "Debian".equals(reread.wslDistro()));
        check("换实例后仍读得到配置名", "ceshi".equals(reread.vmProfile()));
        check("文件确实写出来了", f.isFile());

        // 5. 值前后的空格要去掉，否则和下拉框里的名字对不上。
        m.rememberVmProfile("  spaced  ");
        check("值被 trim", "spaced".equals(new TargetMemory(f).vmProfile()));

        // 6. 空值等于「忘掉」，当作没记住处理，且不影响另一项。
        m.rememberVmProfile("");
        TargetMemory afterClear = new TargetMemory(f);
        check("写空值后当作没记住", afterClear.vmProfile().isEmpty());
        check("写空值不影响另一项", "Debian".equals(afterClear.wslDistro()));

        // 7. 文件损坏时退回「没记住」，而不是抛异常或乱猜。
        File bad = new File(dir, "bad.properties");
        FileWriter w = new FileWriter(bad);
        try {
            w.write("\u0000\u0001 not a properties file \\u00zz\n");
        } finally {
            w.close();
        }
        TargetMemory broken = new TargetMemory(bad);
        check("损坏文件不抛异常，返回空",
                broken.wslDistro().isEmpty() && broken.vmProfile().isEmpty());

        // 8. 文件不存在时同样安全。
        TargetMemory missing = new TargetMemory(new File(dir, "nope.properties"));
        check("文件不存在时返回空",
                missing.wslDistro().isEmpty() && missing.vmProfile().isEmpty());

        deleteRecursively(dir);

        System.out.println(failures == 0
                ? "PASS " + checks + " checks"
                : "FAIL: " + failures + " of " + checks + " checks failed");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] kids = f.listFiles();
        if (kids != null) {
            for (int i = 0; i < kids.length; i++) {
                deleteRecursively(kids[i]);
            }
        }
        f.delete();
    }

    private static void check(String label, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("  FAIL: " + label);
        }
    }
}

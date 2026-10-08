package bioenv.test;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;

import bioenv.core.DiscoveryCache;
import bioenv.core.Schema;

/**
 * 缓存按通道分开存的测试。
 *
 * <p>回归的是一个实打实的体验缺陷：缓存文件以前只存<b>一份</b>数据加一个通道标签。
 * 于是 WSL 和 Linux 虚拟机互相驱逐——
 *
 * <pre>
 *   在 WSL 里扫完    → 缓存写 WSL
 *   切到虚拟机扫完   → 缓存被覆盖成虚拟机，WSL 那份没了
 *   再切回 WSL       → 标签对不上 → 又全量重扫
 * </pre>
 *
 * <p>用户看到的就是「切换一次等一次」，而且两边都得反复等。两个通道本来就是
 * 两台各自存在的机器，它们的列表没必要互相覆盖。
 *
 * <p>顺带钉住 v2 单条目格式的兼容：升级不能把用户已经等过一次的列表扔掉。
 */
public final class MultiChannelCacheTest {

    private static final String WSL = "Ubuntu-22.04";
    private static final String VM = "qw@172.16.145.128:22";

    private static int checks;
    private static int failures;

    private MultiChannelCacheTest() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== MultiChannelCacheTest ===");
        File dir = Files.createTempDirectory("bioenv-cache-multi").toFile();
        File f = new File(dir, "cache.properties");

        // 1. 两个通道各写一份，带各自的 schema。
        new DiscoveryCache(f).save(entry(WSL, "fp-wsl", "samtools"));
        new DiscoveryCache(f).save(entry(VM, "fp-vm", "seqkit"));

        // 2. 核心断言：后写的没有挤掉先写的。
        DiscoveryCache.Data wsl = new DiscoveryCache(f).loadFor(WSL);
        check("WSL 的条目还在", wsl != null);
        check("WSL 条目里是自己那件工具",
                wsl != null && wsl.tools.size() == 1
                        && "samtools".equals(wsl.tools.get(0).command));
        check("WSL 的指纹没被覆盖", wsl != null && "fp-wsl".equals(wsl.fingerprint));
        check("WSL 的 schema 也还在",
                wsl != null && wsl.tools.get(0).schema != null
                        && wsl.tools.get(0).schema.options.size() == 1);

        DiscoveryCache.Data vm = new DiscoveryCache(f).loadFor(VM);
        check("虚拟机的条目还在", vm != null);
        check("虚拟机条目里是自己那件工具",
                vm != null && vm.tools.size() == 1
                        && "seqkit".equals(vm.tools.get(0).command));
        check("虚拟机的指纹没被覆盖", vm != null && "fp-vm".equals(vm.fingerprint));

        // 3. 没记录过的通道返回 null，而不是随便拿一份糊弄。
        check("未知通道返回 null", new DiscoveryCache(f).loadFor("other-host:22") == null);

        // 4. load() 取「最近写的那一份」，供「上次用的是哪个通道」这类问题使用。
        DiscoveryCache.Data last = new DiscoveryCache(f).load();
        check("load() 返回最近写入的通道",
                last != null && VM.equals(last.distro));

        // 5. 重复写同一个通道是更新，不是新增。
        DiscoveryCache.Data again = entry(VM, "fp-vm-2", "bwa");
        new DiscoveryCache(f).save(again);
        check("重复写同一通道是更新",
                new DiscoveryCache(f).loadFor(VM).tools.get(0).command.equals("bwa"));
        check("更新后 WSL 那份仍然完好",
                new DiscoveryCache(f).loadFor(WSL).tools.get(0).command.equals("samtools"));

        // 6. v2 单条目旧文件要能读出来，并在下次保存时升级成 v3。
        File legacy = new File(dir, "legacy.properties");
        writeV2(legacy, "Ubuntu-20.04", "oldtool");
        DiscoveryCache.Data v2 = new DiscoveryCache(legacy).load();
        check("v2 旧格式仍可读", v2 != null && "oldtool".equals(v2.tools.get(0).command));
        check("v2 的通道标签读对了", v2 != null && "Ubuntu-20.04".equals(v2.distro));
        check("v2 能按通道取到",
                new DiscoveryCache(legacy).loadFor("Ubuntu-20.04") != null);

        // 升级：再存一个新通道，旧条目应当被保留下来一起写进 v3。
        new DiscoveryCache(legacy).save(entry(VM, "fp-vm", "seqkit"));
        check("升级到 v3 后旧通道仍在",
                new DiscoveryCache(legacy).loadFor("Ubuntu-20.04") != null);
        check("升级到 v3 后新通道也在",
                new DiscoveryCache(legacy).loadFor(VM) != null);

        // 7. clear() 清掉全部通道。
        new DiscoveryCache(f).clear();
        check("clear 后所有通道都没了", new DiscoveryCache(f).load() == null);

        deleteRecursively(dir);

        System.out.println(failures == 0
                ? "PASS " + checks + " checks"
                : "FAIL: " + failures + " of " + checks + " checks failed");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static DiscoveryCache.Data entry(String distro, String fingerprint,
                                             String command) {
        DiscoveryCache.Data d = new DiscoveryCache.Data();
        d.distro = distro;
        d.fingerprint = fingerprint;
        d.condaRoot = "/home/u/miniconda3";
        d.envCount = 2;
        DiscoveryCache.CachedTool t = new DiscoveryCache.CachedTool();
        t.command = command;
        t.envName = "base";
        t.envPrefix = "/home/u/miniconda3";
        t.version = "1.0";
        t.schema = schemaWithOneOption();
        d.tools.add(t);
        return d;
    }

    private static Schema schemaWithOneOption() {
        Schema s = new Schema();
        s.parsed = true;
        s.rawHelp = "usage: x --opt";
        s.originalHelp = s.rawHelp;
        Schema.Option o = new Schema.Option();
        o.flag = "--opt";
        o.description = "an option";
        s.options.add(o);
        return s;
    }

    /** 手写一份 v2 单条目格式的文件，模拟升级前留下的缓存。 */
    private static void writeV2(File f, String distro, String command) throws Exception {
        FileWriter w = new FileWriter(f);
        try {
            w.write("formatVersion=2\n");
            w.write("fingerprint=fp-legacy\n");
            w.write("distro=" + distro + "\n");
            w.write("condaRoot=/home/u/miniconda3\n");
            w.write("envCount=1\n");
            w.write("toolCount=1\n");
            w.write("tool.0.command=" + command + "\n");
            w.write("tool.0.envName=base\n");
            w.write("tool.0.envPrefix=/home/u/miniconda3\n");
            w.write("tool.0.version=0.9\n");
        } finally {
            w.close();
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

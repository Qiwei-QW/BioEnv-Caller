package bioenv.test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import bioenv.ai.AiModelStore;
import bioenv.core.AtomicFiles;
import bioenv.core.ConfigWarnings;
import bioenv.core.ConnectionModeStore;
import bioenv.core.CorrectionStore;
import bioenv.core.CustomOptionStore;
import bioenv.core.DiscoveryCache;
import bioenv.core.Schema;
import bioenv.core.TargetMemory;
import bioenv.core.ToolNotesStore;
import bioenv.i18n.I18n;
import bioenv.i18n.Lang;
import bioenv.ui.UiTheme;

/**
 * 配置文件写盘的三条硬要求：不毁掉上一份、坏了留证据、失败要能听见。
 *
 * <h2>为什么值得单独测</h2>
 *
 * <p>这一组行为全部发生在"错误路径"上，正常使用时一条也不会触发；而它们的
 * 代价是用户唯一的那份笔记 / 参数修改 / 模型配置。所以每条都要求一个可复现的现场：
 *
 * <ul>
 *   <li>写入走临时文件：成功之后目录里不能留下 {@code .tmp}；</li>
 *   <li>文件在但读不出来：先有 {@code .bak}，再有整整一条提示（不是每条线程一条）；</li>
 *   <li>写失败：返回 false、上报、不抛——旧文件保持完整；</li>
 *   <li>文件里的数字不能当分配/循环上界：{@code count=50000000} 拒绝而不是 OOM；</li>
 *   <li>{@code TargetMemory} 读不出来时不许写：否则会把另一个记住的目标擦掉。</li>
 * </ul>
 *
 * <p>Run: {@code java -cp <classes> bioenv.test.AtomicFilesTest}
 */
public final class AtomicFilesTest {

    private static int checks;
    private static int failures;

    /** 本次用例收到的提示文本，供"报了几次"与"说的是哪个文件"这类断言使用。 */
    private static final List<String> warnings = new ArrayList<String>();

    private AtomicFilesTest() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== AtomicFilesTest ===");

        String oldHome = System.getProperty("user.home");
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "bioenv-atomic-" + System.nanoTime());
        dir.mkdirs();
        File home = new File(dir, "home");
        home.mkdirs();
        // 模型的默认路径、主题、连接方式都基于 user.home；指到临时目录，
        // 免得这个测试写到用户自己的配置。
        System.setProperty("user.home", home.getAbsolutePath());

        try {
            writeIsAtomic(dir);
            corruptFileIsBackedUpAndReported(dir);
            writeFailureIsReported(dir);
            aiModelBounds(home);
            cacheBounds(dir);
            targetMemoryDoesNotWipe(dir);
            everyStoreWritesAtomically(dir);
        } finally {
            if (oldHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", oldHome);
            }
            ConfigWarnings.resetForTest();
            deleteTree(dir);
        }

        line("");
        line("  checks: " + checks + "   failures: " + failures);
        if (failures > 0) {
            line("FAILURES PRESENT");
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------
    // 1. 原子写
    // ------------------------------------------------------------------

    private static void writeIsAtomic(File dir) throws Exception {
        line("");
        line("[a write is complete before it replaces the old file]");
        File f = new File(dir, "plain.txt");
        resetWarnings();

        check("write reports success",
                AtomicFiles.write(f, "hello\nworld\n", StandardCharsets.UTF_8));
        check("the file exists", f.isFile());
        check("the content is complete", "hello\nworld\n".equals(readText(f)));
        check("no .tmp is left behind", !tmpOf(f).exists());
        check("a successful write says nothing", warnings.isEmpty());

        check("an overwrite reports success",
                AtomicFiles.write(f, "second\n", StandardCharsets.UTF_8));
        check("the old content is fully replaced", "second\n".equals(readText(f)));
        check("still no .tmp", !tmpOf(f).exists());

        File nested = new File(new File(dir, "a" + File.separator + "b"), "c.txt");
        check("a missing parent directory is created",
                AtomicFiles.write(nested, "x", StandardCharsets.UTF_8) && nested.isFile());
        check("and that write left no .tmp", !tmpOf(nested).exists());
    }

    // ------------------------------------------------------------------
    // 2. 坏文件：先备份，再报一次
    // ------------------------------------------------------------------

    private static void corruptFileIsBackedUpAndReported(File dir) throws Exception {
        line("");
        line("[a file that cannot be parsed is preserved, then reported once]");
        File f = new File(dir, "tool-notes.properties");
        // Properties.store 把每个非 ASCII 字符写成反斜杠 u 加四位十六进制的转义，
        // 所以一次断电/截断很容易正好断在这个转义中间：这样的文件 Properties.load 会抛异常。
        String bad = "a=1\nb=" + (char) 92 + "u00zz\n";
        writeText(f, bad);
        resetWarnings();

        ToolNotesStore broken = new ToolNotesStore(f);
        check("the store continues with no notes",
                broken.size() == 0 && !broken.has("a"));
        check("the original bytes are kept in .bak",
                bakOf(f).isFile() && bad.equals(readText(bakOf(f))));
        check("exactly one warning is reported", warnings.size() == 1);
        check("the warning names the file",
                warnings.size() == 1 && warnings.get(0).contains("tool-notes.properties"));

        ToolNotesStore again = new ToolNotesStore(f);
        check("a second read still continues empty", again.size() == 0);
        check("and it does not warn a second time", warnings.size() == 1);
    }

    // ------------------------------------------------------------------
    // 3. 写失败：返回 false、上报、不抛
    // ------------------------------------------------------------------

    private static void writeFailureIsReported(File dir) throws Exception {
        line("");
        line("[a failed write returns false, reports, and does not throw]");
        File blocker = new File(dir, "blocker");
        writeText(blocker, "not a directory\n");
        // 父路径是普通文件 → 建目录与写临时文件都不可能成功。
        File impossible = new File(blocker, "x.properties");
        resetWarnings();

        boolean ok;
        try {
            ok = AtomicFiles.write(impossible, "data\n", StandardCharsets.UTF_8);
        } catch (Exception e) {
            ok = true;   // 抛了就是失败，交给下面那条断言
        }
        check("write reports failure instead of throwing", !ok);
        check("the failure is reported", warnings.size() >= 1);
        check("the blocking file is untouched", "not a directory\n".equals(readText(blocker)));

        // 经 store 调用也一样：内存里改了，盘上没有，但不会有异常穿到调用方。
        ToolNotesStore store = new ToolNotesStore(impossible);
        boolean changed;
        try {
            changed = store.set("k", "v");
        } catch (Exception e) {
            changed = false;
        }
        check("the store still accepts the edit in memory", changed);
        check("but nothing could be written", !impossible.isFile());
    }

    // ------------------------------------------------------------------
    // 4. 文件里的数字不能当分配/循环上界
    // ------------------------------------------------------------------

    private static void aiModelBounds(File home) throws Exception {
        line("");
        line("[a wild index/count in the model file is rejected, not allocated]");
        File aiDir = new File(home, ".TBtools" + File.separator + "BioEnvCaller");
        aiDir.mkdirs();
        File ai = new File(aiDir, "ai-models.properties");

        resetWarnings();
        writeText(ai, "count=50000000\nm50000000.name=x\n");
        long started = System.currentTimeMillis();
        AiModelStore big = AiModelStore.load();
        long elapsed = System.currentTimeMillis() - started;
        check("a wild count is rejected without OOM", big.models.isEmpty());
        check("rejection happens promptly", elapsed < 5000);
        check("the bad model file is backed up", bakOf(ai).isFile());
        check("the rejection is reported once", warnings.size() == 1);

        resetWarnings();
        writeText(ai, "count=1\nm50000000.name=x\n");
        check("a wild index is rejected", AiModelStore.load().models.isEmpty());
        check("and reported once", warnings.size() == 1);

        resetWarnings();
        AiModelStore store = new AiModelStore();
        AiModelStore.Model m = new AiModelStore.Model();
        m.name = "local";
        m.baseUrl = "http://127.0.0.1:11434/v1";
        m.model = "llama";
        m.apiKey = "sk-test";
        m.maxOut = 4096;
        m.maxIn = 32768;
        store.models.add(m);
        store.save();
        checkWritten("AiModelStore", ai);
        AiModelStore back = AiModelStore.load();
        check("a saved model comes back",
                back.models.size() == 1 && "local".equals(back.models.get(0).name));
        check("its key comes back",
                back.models.size() == 1 && "sk-test".equals(back.models.get(0).apiKey));
        check("its input limit comes back",
                back.models.size() == 1 && back.models.get(0).maxIn == 32768);
        check("a valid file raises no warning", warnings.isEmpty());
    }

    private static void cacheBounds(File dir) throws Exception {
        line("");
        line("[the discovery cache refuses counts it cannot trust, and keeps the bytes]");
        File cache = new File(dir, "cache.properties");

        resetWarnings();
        writeText(cache, "formatVersion=3\nentryCount=50000000\n");
        check("a wild entry count is rejected",
                new DiscoveryCache(cache).load() == null);
        check("its bytes are preserved in .bak", bakOf(cache).isFile());
        check("exactly one warning is reported", warnings.size() == 1);

        resetWarnings();
        writeText(cache, "formatVersion=3\nentryCount=1\ne0.toolCount=5000000\n");
        check("a wild tool count is rejected",
                new DiscoveryCache(cache).load() == null);
        check("and reported once", warnings.size() == 1);

        resetWarnings();
        writeText(cache, "formatVersion=999\n");
        check("an unknown format is still refused",
                new DiscoveryCache(cache).load() == null);
        check("but preserved in .bak",
                bakOf(cache).isFile() && "formatVersion=999\n".equals(readText(bakOf(cache))));
        check("exactly one warning is reported", warnings.size() == 1);

        // 用户改过的帮助原文只存在这个文件里：截断之后必须先留下它。
        DiscoveryCache.Data d = new DiscoveryCache.Data();
        d.distro = "Ubuntu";
        d.fingerprint = "fp";
        DiscoveryCache.CachedTool t = new DiscoveryCache.CachedTool();
        t.command = "samtools";
        t.envName = "base";
        t.envPrefix = "/envs/base";
        t.schema = new Schema();
        t.schema.parsed = true;
        t.schema.rawHelp = "EDITED HELP";
        t.schema.originalHelp = "ORIGINAL HELP";
        d.tools.add(t);
        new DiscoveryCache(cache).save(d);
        // 模拟写到一半：末尾多了一个 Properties 解析不过去的转义。
        appendText(cache, "x=" + (char) 92 + "u00zz\n");
        resetWarnings();
        check("a half-written cache is not trusted",
                new DiscoveryCache(cache).load() == null);
        check("the edited help text is preserved in .bak",
                bakOf(cache).isFile() && readText(bakOf(cache)).contains("EDITED HELP"));
        check("the corruption is reported once", warnings.size() == 1);

        resetWarnings();
        new DiscoveryCache(cache).save(d);
        DiscoveryCache.Data back = new DiscoveryCache(cache).load();
        check("a normal cache still round-trips",
                back != null && back.tools.size() == 1
                        && "samtools".equals(back.tools.get(0).command));
        check("its edited help survives the round trip",
                back != null && back.tools.get(0).schema != null
                        && "EDITED HELP".equals(back.tools.get(0).schema.rawHelp));
        checkWritten("DiscoveryCache", cache);
    }

    // ------------------------------------------------------------------
    // 5. TargetMemory 读不出来时不许写
    // ------------------------------------------------------------------

    private static void targetMemoryDoesNotWipe(File dir) throws Exception {
        line("");
        line("[an unreadable last-target file is not overwritten]");
        File mem = new File(dir, "last-target.properties");
        resetWarnings();
        TargetMemory m = new TargetMemory(mem);
        m.rememberWslDistro("Ubuntu-22.04");
        m.rememberVmProfile("vm-a");
        check("both targets are remembered",
                "Ubuntu-22.04".equals(m.wslDistro()) && "vm-a".equals(m.vmProfile()));
        checkWritten("TargetMemory", mem);

        String broken = "wslDistro=x\nvmProfile=" + (char) 92 + "u00zz\n";
        writeText(mem, broken);
        resetWarnings();
        m.rememberWslDistro("Debian");
        check("the unreadable file is not overwritten", broken.equals(readText(mem)));
        check("the original is preserved in .bak", bakOf(mem).isFile());
        check("the user is warned", warnings.size() >= 1);
        check("the other remembered target is still in the preserved bytes",
                readText(mem).contains("vmProfile="));
    }

    // ------------------------------------------------------------------
    // 6. 每个 store 都走同一条写盘路径
    // ------------------------------------------------------------------

    private static void everyStoreWritesAtomically(File dir) throws Exception {
        line("");
        line("[every store writes atomically and leaves no .tmp]");
        resetWarnings();

        File notes = new File(dir, "notes2.properties");
        ToolNotesStore ns = new ToolNotesStore(notes);
        check("a note is accepted", ns.set("trimal", "a note"));
        checkWritten("ToolNotesStore", notes);
        check("the note survives", "a note".equals(new ToolNotesStore(notes).get("trimal")));

        File corr = new File(dir, "corrections.properties");
        CorrectionStore cs = new CorrectionStore(corr);
        cs.remember("samtools", "-@", Schema.Option.Kind.NUMBER);
        checkWritten("CorrectionStore", corr);
        check("the type choice survives",
                new CorrectionStore(corr).lookup("samtools", "-@")
                        == Schema.Option.Kind.NUMBER);

        File custom = new File(dir, "custom.properties");
        CustomOptionStore co = new CustomOptionStore(custom);
        check("a custom option is accepted", co.add("bio fasta", "-x", "mine"));
        checkWritten("CustomOptionStore", custom);
        check("the custom option survives",
                new CustomOptionStore(custom).forScope("bio fasta").containsKey("-x"));

        ConnectionModeStore cms = new ConnectionModeStore();
        File mode = cms.getFile();
        cms.save(ConnectionModeStore.MODE_VM);
        checkWritten("ConnectionModeStore", mode);
        check("the mode survives",
                ConnectionModeStore.MODE_VM.equals(new ConnectionModeStore().load()));

        File lang = new File(dir, "language.txt");
        I18n.setFile(lang);
        I18n.set(Lang.EN);
        checkWritten("I18n", lang);
        check("the language survives", "en".equals(readText(lang).trim()));

        // 主题这一段要验的正是"它也会落盘"，而门禁跑回归测试时统一带着
        // -Dbioenv.theme.readonly=1（免得测试把用户存的主题写回去）。
        // 若直接照着门禁的环境跑，save() 会按设计早退，文件根本不会产生 ——
        // 断言就变成在考"只读开关有没有生效"，不是考原子写。
        // 所以这里临时摘掉开关，验完原样装回。
        String readonly = System.getProperty("bioenv.theme.readonly");
        System.clearProperty("bioenv.theme.readonly");
        try {
            UiTheme.set(UiTheme.DARK);
            File theme = UiTheme.defaultFile();
            checkWritten("UiTheme", theme);
            check("the theme survives", UiTheme.DARK.equals(readText(theme).trim()));
        } finally {
            if (readonly != null) {
                System.setProperty("bioenv.theme.readonly", readonly);
            }
        }

        check("no successful save produced a warning", warnings.isEmpty());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void checkWritten(String what, File f) {
        check(what + " wrote the file", f.isFile());
        check(what + " left no .tmp", !tmpOf(f).exists());
    }

    private static File tmpOf(File f) {
        return new File(f.getParentFile(), f.getName() + ".tmp");
    }

    private static File bakOf(File f) {
        return new File(f.getParentFile(), f.getName() + ".bak");
    }

    /** 重新装一个只记录文本的接收器，并清掉"已经说过"的记录。 */
    private static void resetWarnings() {
        ConfigWarnings.resetForTest();
        warnings.clear();
        ConfigWarnings.setSink(new ConfigWarnings.Sink() {
            @Override
            public void warn(String message) {
                warnings.add(message);
            }
        });
    }

    private static void writeText(File f, String text) throws Exception {
        OutputStream out = new FileOutputStream(f);
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
    }

    private static void appendText(File f, String text) throws Exception {
        OutputStream out = new FileOutputStream(f, true);
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
    }

    private static String readText(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] kids = f.listFiles();
        if (kids != null) {
            for (int i = 0; i < kids.length; i++) {
                deleteTree(kids[i]);
            }
        }
        f.delete();
    }

    private static void check(String label, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("  FAIL  " + label);
        }
    }

    private static void line(String s) {
        System.out.println(s);
    }
}

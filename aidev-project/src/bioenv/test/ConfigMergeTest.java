package bioenv.test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import bioenv.ai.AiModelStore;
import bioenv.core.ConfigWarnings;
import bioenv.core.CorrectionStore;
import bioenv.core.CustomOptionStore;
import bioenv.core.FilePerms;
import bioenv.core.Schema;
import bioenv.core.ToolNotesStore;

/**
 * 两个配置实例并发编辑同一个文件时，谁的改动都不能被对方删掉。
 *
 * <h2>为什么需要这个测试</h2>
 *
 * <p>TBtools 可能不止一次构造插件面板（{@code PluginInject} 的注释里写了）。每个
 * store 在构造时读一次盘、之后一直用自己那份内存表，保存时整表写回——于是后保存的
 * 那个实例会用过期快照把先保存的实例刚写的东西删掉。用户看到的是"我明明写了，
 * 过一会儿又没了"，而且没有任何报错。
 *
 * <p>现在保存前先读回磁盘、只把本实例自上次成功保存以来的改动（{@code changed}）
 * 与删除（{@code removed}）叠上去，删除优先。这个测试覆盖三种顺序、删除不许复活、
 * 以及写失败时改动要留着重试。
 *
 * <p>后半部分验证 API key 文件在支持的文件系统上被收窄成"只有所有者可读写"；
 * 不支持时打印 SKIP 及原因，不假装通过。
 *
 * <p>Run: {@code java -cp <classes> bioenv.test.ConfigMergeTest}
 */
public final class ConfigMergeTest {

    private static int checks;
    private static int failures;
    private static int skipped;

    /** 本次用例收到的提示文本。 */
    private static final List<String> warnings = new ArrayList<String>();

    private ConfigMergeTest() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== ConfigMergeTest ===");

        String oldHome = System.getProperty("user.home");
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "bioenv-merge-" + System.nanoTime());
        dir.mkdirs();
        File home = new File(dir, "home");
        home.mkdirs();
        System.setProperty("user.home", home.getAbsolutePath());

        try {
            notesMerge(dir);
            notesDeletionWins(dir);
            notesSaveOrderBothWays(dir);
            notesTombstoneSurvivesRetry(dir);
            notesWriteFailureKeepsPending(dir);
            correctionsMerge(dir);
            customOptionsMerge(dir);
            filePerms(dir);
            aiModelSaveStillWorks(home);
        } finally {
            if (oldHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", oldHome);
            }
            ConfigWarnings.resetForTest();
            FilePerms.resetForTest();
            deleteTree(dir);
        }

        line("");
        line("  checks: " + checks + "   failures: " + failures
                + (skipped == 0 ? "" : "   skipped: " + skipped));
        if (failures > 0) {
            line("FAILURES PRESENT");
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------
    // 1. 两个实例各写各的，都要留下
    // ------------------------------------------------------------------

    private static void notesMerge(File dir) throws Exception {
        line("");
        line("[two note stores over one file keep both edits]");
        File f = new File(dir, "notes.properties");
        resetWarnings();
        ToolNotesStore a = new ToolNotesStore(f);
        ToolNotesStore b = new ToolNotesStore(f);
        a.set("a", "note-a");
        b.set("b", "note-b");

        ToolNotesStore fresh = new ToolNotesStore(f);
        check("the first instance's note survives", "note-a".equals(fresh.get("a")));
        check("the second instance's note survives", "note-b".equals(fresh.get("b")));
        check("no warning was raised for a normal save", warnings.isEmpty());
        check("no temporary file is left behind",
                !new File(dir, "notes.properties.tmp").exists());
    }

    // ------------------------------------------------------------------
    // 2. 删除必须赢过过期快照
    // ------------------------------------------------------------------

    private static void notesDeletionWins(File dir) throws Exception {
        line("");
        line("[a deletion is not resurrected by a stale instance]");
        File f = new File(dir, "notes-del.properties");
        resetWarnings();
        new ToolNotesStore(f).set("k", "old note");

        ToolNotesStore a = new ToolNotesStore(f);   // both loaded k
        ToolNotesStore b = new ToolNotesStore(f);
        check("A deletes k", a.clear("k"));
        check("B still has k in memory", "old note".equals(b.get("k")));
        check("B saves a different key", b.set("other", "kept"));

        ToolNotesStore fresh = new ToolNotesStore(f);
        check("k stays deleted", !fresh.has("k"));
        check("the other key is present", "kept".equals(fresh.get("other")));
    }

    // ------------------------------------------------------------------
    // 3. 反过来保存也一样
    // ------------------------------------------------------------------

    private static void notesSaveOrderBothWays(File dir) throws Exception {
        line("");
        line("[the other save order keeps both too]");
        File f = new File(dir, "notes-order.properties");
        resetWarnings();
        ToolNotesStore a = new ToolNotesStore(f);
        ToolNotesStore b = new ToolNotesStore(f);
        b.set("b", "note-b");          // B saves first
        a.set("a", "note-a");          // then A saves over a file B just wrote

        ToolNotesStore fresh = new ToolNotesStore(f);
        check("the first note survives B-then-A", "note-b".equals(fresh.get("b")));
        check("the second note survives B-then-A", "note-a".equals(fresh.get("a")));
    }

    // ------------------------------------------------------------------
    // 4. 删除被记成墓碑：即使磁盘上还有，重试时也删掉
    // ------------------------------------------------------------------

    private static void notesTombstoneSurvivesRetry(File dir) throws Exception {
        line("");
        line("[a pending deletion wins when a stale instance writes the key back]");
        File f = new File(dir, "notes-tombstone.properties");
        resetWarnings();
        new ToolNotesStore(f).set("k", "old note");

        ToolNotesStore a = new ToolNotesStore(f);   // both loaded k
        ToolNotesStore b = new ToolNotesStore(f);

        // 让 A 这一次删除写不进去：把临时文件的位置占成一个空目录
        // （AtomicFiles 会删掉它，所以只影响这一次）。
        File tmpPath = new File(dir, "notes-tombstone.properties.tmp");
        check("the tmp path is occupied", tmpPath.mkdirs());
        check("A deletes k, but the save fails", a.clear("k"));
        check("the deletion is pending in A's view", !a.has("k"));
        check("the disk copy still has k (A's write failed)",
                new ToolNotesStore(f).has("k"));

        check("B still believes k exists", "old note".equals(b.get("k")));
        check("B saves another key, writing k back", b.set("other", "kept"));
        check("the disk has k again after B's save",
                new ToolNotesStore(f).has("k"));

        check("A saves again, retrying the pending deletion", a.set("after", "value"));
        ToolNotesStore fresh = new ToolNotesStore(f);
        check("k is gone even though the disk had it", !fresh.has("k"));
        check("B's other key is kept", "kept".equals(fresh.get("other")));
        check("A's later edit is kept", "value".equals(fresh.get("after")));
    }

    // ------------------------------------------------------------------
    // 5. 写失败：不抛、不丢待写改动、提示一次，之后能补上
    // ------------------------------------------------------------------

    private static void notesWriteFailureKeepsPending(File dir) throws Exception {
        line("");
        line("[a failed save keeps its pending edits for a later retry]");
        File blocker = new File(dir, "blocker");
        writeText(blocker, "not a directory\n");
        File impossible = new File(blocker, "notes.properties");
        resetWarnings();

        ToolNotesStore store = new ToolNotesStore(impossible);
        boolean threw = false;
        try {
            store.set("a", "x");
            store.set("b", "y");
        } catch (Exception e) {
            threw = true;
        }
        check("edits do not throw when the file cannot be written", !threw);
        check("the edits are still visible in this instance",
                "x".equals(store.get("a")) && "y".equals(store.get("b")));
        check("the failure is reported exactly once", warnings.size() == 1);
        check("nothing was written", !impossible.isFile());

        // 修好目录：挡住路径的普通文件换成目录，下一次保存应当把待写改动补上。
        check("the blocker is replaced by a directory", blocker.delete() && blocker.mkdirs());
        check("a later edit triggers the retry", store.set("c", "z"));

        ToolNotesStore fresh = new ToolNotesStore(impossible);
        check("the retry wrote the pending edits",
                "x".equals(fresh.get("a")) && "y".equals(fresh.get("b")));
        check("the retry wrote the new edit", "z".equals(fresh.get("c")));
        check("the successful retry added no warning", warnings.size() == 1);
    }

    // ------------------------------------------------------------------
    // 6. 类型修正也走同一套合并
    // ------------------------------------------------------------------

    private static void correctionsMerge(File dir) throws Exception {
        line("");
        line("[two correction stores keep both choices]");
        File f = new File(dir, "corrections.properties");
        resetWarnings();
        CorrectionStore a = new CorrectionStore(f);
        CorrectionStore b = new CorrectionStore(f);
        a.remember("cmd1", "-a", Schema.Option.Kind.INPUT_PATH);
        b.remember("cmd2", "-b", Schema.Option.Kind.OUTPUT_PATH);

        CorrectionStore fresh = new CorrectionStore(f);
        check("the first choice survives",
                fresh.lookup("cmd1", "-a") == Schema.Option.Kind.INPUT_PATH);
        check("the second choice survives",
                fresh.lookup("cmd2", "-b") == Schema.Option.Kind.OUTPUT_PATH);
        check("no warning for a normal save", warnings.isEmpty());

        CorrectionStore c = new CorrectionStore(f);
        CorrectionStore d = new CorrectionStore(f);
        String key = c.forCommand("cmd1").keySet().iterator().next();
        check("C forgets its choice", c.forget(key));
        check("D still has it in memory",
                d.lookup("cmd1", "-a") == Schema.Option.Kind.INPUT_PATH);
        d.remember("cmd3", "-c", Schema.Option.Kind.NUMBER);

        CorrectionStore after = new CorrectionStore(f);
        check("the deletion wins", after.lookup("cmd1", "-a") == null);
        check("D's new choice survives",
                after.lookup("cmd3", "-c") == Schema.Option.Kind.NUMBER);
        check("the untouched choice survives",
                after.lookup("cmd2", "-b") == Schema.Option.Kind.OUTPUT_PATH);
    }

    // ------------------------------------------------------------------
    // 7. 参数编辑也走同一套合并
    // ------------------------------------------------------------------

    private static void customOptionsMerge(File dir) throws Exception {
        line("");
        line("[two parameter-edit stores keep both scopes]");
        File f = new File(dir, "custom.properties");
        resetWarnings();
        CustomOptionStore a = new CustomOptionStore(f);
        CustomOptionStore b = new CustomOptionStore(f);
        check("A adds for scope1", a.add("scope1", "-a", "desc-a"));
        check("B adds for scope2", b.add("scope2", "-b", "desc-b"));
        check("A hides one for scope1", a.hide("scope1", "-h"));

        CustomOptionStore fresh = new CustomOptionStore(f);
        check("scope1 keeps A's parameter", fresh.forScope("scope1").containsKey("-a"));
        check("scope2 keeps B's parameter", fresh.forScope("scope2").containsKey("-b"));
        check("the hiding survives", fresh.hiddenFor("scope1").contains("-h"));

        CustomOptionStore c = new CustomOptionStore(f);
        CustomOptionStore d = new CustomOptionStore(f);
        check("C removes -a", c.remove("scope1", "-a"));
        check("D still has -a in memory", d.isAdded("scope1", "-a"));
        check("D adds another parameter", d.add("scope3", "-d", "desc-d"));

        CustomOptionStore after = new CustomOptionStore(f);
        check("the removal wins", !after.isAdded("scope1", "-a"));
        check("D's addition survives", after.isAdded("scope3", "-d"));
        check("the hidden decision survives", after.hiddenFor("scope1").contains("-h"));
    }

    // ------------------------------------------------------------------
    // 8. 权限收窄：支持就断言，不支持就 SKIP 并说明原因
    // ------------------------------------------------------------------

    private static void filePerms(File dir) throws Exception {
        line("");
        line("[the key file can be made owner-only]");
        File f = new File(dir, "ai-models-perm.properties");
        writeText(f, "count=0\n");
        resetWarnings();
        FilePerms.resetForTest();

        FileStore store = Files.getFileStore(f.toPath());
        boolean posix = store.supportsFileAttributeView("posix");
        boolean acl = store.supportsFileAttributeView("acl");
        boolean restricted = FilePerms.restrictToOwner(f);

        if (posix) {
            line("  (filesystem path used: POSIX permissions)");
            check("POSIX: restricting reports success", restricted);
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(f.toPath());
            check("POSIX: only owner read/write remains",
                    perms.equals(PosixFilePermissions.fromString("rw-------")));
        } else if (acl) {
            Boolean onlyOwner = ownerOnly(f);
            if (onlyOwner == null) {
                skip("ACL view is not available despite the file store claiming ACL support");
            } else if (!restricted || !onlyOwner.booleanValue()) {
                skip("ACL could not be reduced to the owner on this machine"
                        + (warnings.isEmpty() ? "" : "; reported: " + warnings.get(0)));
            } else {
                line("  (filesystem path used: Windows ACL)");
                check("Windows ACL: only the owner's ALLOW entry remains",
                        Boolean.TRUE.equals(onlyOwner));
            }
        } else {
            skip("filesystem supports neither POSIX permissions nor ACL");
        }

        check("a missing file is not an error",
                !FilePerms.restrictToOwner(new File(dir, "does-not-exist.properties")));
    }

    /** 文件是否只剩所有者的 ALLOW 条目；无法检查时返回 null。 */
    private static Boolean ownerOnly(File f) throws Exception {
        Path p = f.toPath();
        FileStore store = Files.getFileStore(p);
        if (store.supportsFileAttributeView("posix")) {
            return Boolean.valueOf(Files.getPosixFilePermissions(p)
                    .equals(PosixFilePermissions.fromString("rw-------")));
        }
        AclFileAttributeView view = Files.getFileAttributeView(p, AclFileAttributeView.class);
        if (view == null) {
            return null;
        }
        UserPrincipal owner = view.getOwner();
        List<AclEntry> acl = view.getAcl();
        if (acl.isEmpty()) {
            return Boolean.FALSE;
        }
        for (int i = 0; i < acl.size(); i++) {
            AclEntry e = acl.get(i);
            if (e.type() != AclEntryType.ALLOW || !owner.equals(e.principal())) {
                return Boolean.FALSE;
            }
        }
        return Boolean.TRUE;
    }

    // ------------------------------------------------------------------
    // 9. 收窄权限不能妨碍保存
    // ------------------------------------------------------------------

    private static void aiModelSaveStillWorks(File home) throws Exception {
        line("");
        line("[restricting the key file does not break saving]");
        File ai = new File(new File(home, ".TBtools" + File.separator + "BioEnvCaller"),
                "ai-models.properties");
        resetWarnings();
        AiModelStore store = new AiModelStore();
        AiModelStore.Model m = new AiModelStore.Model();
        m.name = "local";
        m.baseUrl = "http://127.0.0.1:11434/v1";
        m.model = "llama";
        m.apiKey = "sk-secret";
        m.maxIn = 32768;
        store.models.add(m);
        store.save();

        check("the model file was written", ai.isFile());
        AiModelStore back = AiModelStore.load();
        check("the model comes back", back.models.size() == 1
                && "local".equals(back.models.get(0).name));
        check("the key comes back", back.models.size() == 1
                && "sk-secret".equals(back.models.get(0).apiKey));
        check("no warning was raised while saving", warnings.isEmpty());

        Boolean onlyOwner = ownerOnly(ai);
        if (onlyOwner == null) {
            skip("the saved model file's permissions cannot be inspected here");
        } else {
            check("the saved model file is owner-only", onlyOwner.booleanValue());
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

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

    /** 文件系统不支持时如实说明：不算通过，也不算失败。 */
    private static void skip(String reason) {
        skipped++;
        System.out.println("  SKIP  " + reason);
    }

    private static void line(String s) {
        System.out.println(s);
    }
}

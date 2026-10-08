package vmadapt.test;

import vmadapt.core.RemoteFiles;
import vmadapt.core.ScpTransfer;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 上传计划与跳过逻辑测试。
 *
 * <p>两组关注点：
 *
 * <ol>
 *   <li><b>目录递归展开</b>：scp 的目录进度只报整体，逐文件回调无法实现，所以
 *       进度与总数统计都要先把目录展开成纯文件列表。展开必须保持输入顺序、
 *       去重、并且**不能静默吞掉不存在的路径**（那会把用户的输入错误藏起来）。</li>
 *   <li><b>跳过判定的方向性</b>：{@code needsUpload} 的语义是"需要上传"。
 *       这里最容易写反——远端已存在且大小一致时返回 {@code false}（跳过），
 *       任何不确定的情况（远端查不到、本地不是普通文件）都必须返回 {@code true}。
 *       宁可多传一次，也不能漏传：漏传的后果是工具读到旧数据而用户毫不知情。</li>
 * </ol>
 *
 * <p>不联网：需要一个 transport 的地方都传 null，让查询直接失败，
 * 从而只验证"不确定时倾向于上传"这一侧的行为。
 */
public final class UploadPlanTest {

    private static int checks = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== UploadPlanTest ===");

        Path root = Files.createTempDirectory("vmadapt-upload-test-");
        try {
            // -----------------------------------------------------------
            // 准备一棵目录树
            //   root/a.txt
            //   root/b.txt
            //   root/sub/c.txt
            //   root/sub/deep/d.txt
            // -----------------------------------------------------------
            Path a = root.resolve("a.txt");
            Path b = root.resolve("b.txt");
            Path sub = root.resolve("sub");
            Path c = sub.resolve("c.txt");
            Path deep = sub.resolve("deep");
            Path d = deep.resolve("d.txt");
            Files.createDirectories(deep);
            Files.write(a, "aaa".getBytes(StandardCharsets.UTF_8));
            Files.write(b, "bbbbb".getBytes(StandardCharsets.UTF_8));
            Files.write(c, "c".getBytes(StandardCharsets.UTF_8));
            Files.write(d, "dddd".getBytes(StandardCharsets.UTF_8));

            // -----------------------------------------------------------
            // 1. expandLocal：单文件
            // -----------------------------------------------------------
            List<String> one = new ArrayList<String>();
            one.add(a.toString());
            List<String> expandedOne = ScpTransfer.expandLocal(one);
            eq("单文件展开为 1 项", "1", String.valueOf(expandedOne.size()));
            eq("单文件路径正确", a.toString(), expandedOne.get(0));

            // -----------------------------------------------------------
            // 2. expandLocal：目录递归
            // -----------------------------------------------------------
            List<String> dirOnly = new ArrayList<String>();
            dirOnly.add(root.toString());
            List<String> expandedDir = ScpTransfer.expandLocal(dirOnly);
            eq("目录递归出 4 个文件", "4", String.valueOf(expandedDir.size()));
            check("含 a.txt", containsEnding(expandedDir, "a.txt"));
            check("含 b.txt", containsEnding(expandedDir, "b.txt"));
            check("含 c.txt", containsEnding(expandedDir, "c.txt"));
            check("含 d.txt（深层）", containsEnding(expandedDir, "d.txt"));
            check("不含目录本身", !expandedDir.contains(root.toString()));
            check("不含子目录路径", !expandedDir.contains(sub.toString()));

            // 结果的每一项都应当是真实存在的普通文件
            for (int i = 0; i < expandedDir.size(); i++) {
                check("展开项存在：" + expandedDir.get(i),
                        new File(expandedDir.get(i)).isFile());
            }

            // -----------------------------------------------------------
            // 3. 混入文件与目录，顺序与去重
            // -----------------------------------------------------------
            List<String> mixed = new ArrayList<String>();
            mixed.add(a.toString());
            mixed.add(sub.toString());  // 目录，展开出 c.txt 和 d.txt
            mixed.add(a.toString());    // 重复，应只保留一次
            mixed.add(b.toString());
            List<String> expandedMixed = ScpTransfer.expandLocal(mixed);
            eq("混合输入去重后 4 项", "4", String.valueOf(expandedMixed.size()));
            eq("第一项保持输入顺序", a.toString(), expandedMixed.get(0));
            check("重复项只出现一次", countOccurrences(expandedMixed, a.toString()) == 1);

            // -----------------------------------------------------------
            // 4. 空/ null 输入
            // -----------------------------------------------------------
            check("null 返回空列表", ScpTransfer.expandLocal(null).isEmpty());
            check("空列表返回空列表", ScpTransfer.expandLocal(new ArrayList<String>()).isEmpty());

            List<String> blanks = new ArrayList<String>();
            blanks.add(null);
            blanks.add("");
            blanks.add("   ");
            check("空白项被跳过", ScpTransfer.expandLocal(blanks).isEmpty());

            // -----------------------------------------------------------
            // 5. 不存在的路径必须保留（不能静默吞掉用户错误）
            // -----------------------------------------------------------
            String ghost = root.resolve("definitely-missing.fa").toString();
            List<String> ghosts = new ArrayList<String>();
            ghosts.add(ghost);
            List<String> expandedGhosts = ScpTransfer.expandLocal(ghosts);
            eq("不存在的路径被保留", "1", String.valueOf(expandedGhosts.size()));
            check("保留的是原路径", expandedGhosts.get(0).contains("definitely-missing.fa"));

            // -----------------------------------------------------------
            // 6. countFiles
            // -----------------------------------------------------------
            eq("countFiles 计数", "4", String.valueOf(ScpTransfer.countFiles(expandedDir)));
            eq("countFiles(null) 为 0", "0", String.valueOf(ScpTransfer.countFiles(null)));

            // -----------------------------------------------------------
            // 7. needsUpload：方向性（核心）
            // -----------------------------------------------------------
            // transport 为 null 时远端查询总是失败 → 必须倾向于上传
            RemoteFiles rf = new RemoteFiles(
                    new vmadapt.core.SshTarget("10.0.0.1", 22, "u", null), null);

            check("远端查不到时倾向于上传", rf.needsUpload(a.toString(), "/remote/a.txt"));
            check("transport 为 null 时也倾向于上传",
                    rf.needsUpload(c.toString(), "/remote/c.txt"));

            // 本地路径为 null / 空 → 倾向于上传（交给上层处理）
            check("本地 null 倾向于上传", rf.needsUpload(null, "/remote/x"));
            check("本地空串倾向于上传", rf.needsUpload("", "/remote/x"));
            check("本地纯空格倾向于上传", rf.needsUpload("   ", "/remote/x"));

            // 本地不是普通文件（目录或不存在）→ 倾向于上传
            check("本地是目录时倾向于上传", rf.needsUpload(sub.toString(), "/remote/sub"));
            check("本地不存在时倾向于上传", rf.needsUpload(ghost, "/remote/ghost.fa"));

            // -----------------------------------------------------------
            // 8. remoteSize 在无 transport 时返回 -1（不抛异常）
            // -----------------------------------------------------------
            eq("无 transport 时 remoteSize 为 -1", "-1",
                    String.valueOf(rf.remoteSize("/x.fa")));
            eq("remoteSize(null) 为 -1", "-1", String.valueOf(rf.remoteSize(null)));
            eq("remoteSize(\"\") 为 -1", "-1", String.valueOf(rf.remoteSize("")));

            check("无 transport 时 remoteExists 为假", !rf.remoteExists("/x.fa"));
            check("无 transport 时 remoteIsDir 为假", !rf.remoteIsDir("/x.fa"));

            // -----------------------------------------------------------
            // 9. sanitizeLabel 通过 createWorkDir 的间接行为验证
            //    （createWorkDir 需要 transport，这里只验证不抛异常并返回 null）
            // -----------------------------------------------------------
            eq("无 transport 时 createWorkDir 返回 null", null,
                    rf.createWorkDir("label"));
            eq("removeWorkDir 无 transport 返回 false", "false",
                    String.valueOf(rf.removeWorkDir("/x")));
            check("removeWorkDir(null) 返回 false", !rf.removeWorkDir(null));

            // -----------------------------------------------------------
            // 10. 进度行解析（scp 把进度写到 stderr）
            // -----------------------------------------------------------
            eq("解析普通进度行", "45",
                    String.valueOf(ScpTransfer.parsePercent(
                            "x.fa                        45%  1234KB  12.3MB/s   00:03")));
            eq("解析 100%", "100",
                    String.valueOf(ScpTransfer.parsePercent("x.fa  100%  5MB  5MB/s  00:00")));
            eq("解析 0%", "0",
                    String.valueOf(ScpTransfer.parsePercent("x.fa  0%  0  0.0KB/s  --:--")));
            eq("null 进度返回 -1", "-1", String.valueOf(ScpTransfer.parsePercent(null)));
            eq("空进度返回 -1", "-1", String.valueOf(ScpTransfer.parsePercent("")));
            eq("无百分号返回 -1", "-1",
                    String.valueOf(ScpTransfer.parsePercent("nothing here")));
            // 超过 100 的值被忽略
            eq("超范围百分比被忽略", "-1",
                    String.valueOf(ScpTransfer.parsePercent("x  200%  1  1  00:00")));
            // 文件名里带数字不受干扰：取最右的合法百分比
            eq("文件名含数字时取真实进度", "77",
                    String.valueOf(ScpTransfer.parsePercent(
                            "sample45.fa            77%  1MB  1MB/s  00:01")));

            // 从进度行里取文件名
            eq("解析文件名", "x.fa",
                    ScpTransfer.parseProgressFile("x.fa                        45%  1KB  1KB/s"));
            eq("解析含空格文件名", "my data.fa",
                    ScpTransfer.parseProgressFile("my data.fa   45%  1KB  1KB/s"));
            eq("解析含中文文件名", "基因.fa",
                    ScpTransfer.parseProgressFile("基因.fa   45%  1KB  1KB/s"));
            eq("无百分号返回 null", null, ScpTransfer.parseProgressFile("nothing"));
            eq("null 返回 null", null, ScpTransfer.parseProgressFile(null));

            // -----------------------------------------------------------
            // 11. humanBytes 显示
            // -----------------------------------------------------------
            check("0 字节可显示", ScpTransfer.humanBytes(0) != null);
            check("1KB 可显示", ScpTransfer.humanBytes(1024) != null);
            check("1MB 可显示", ScpTransfer.humanBytes(1024L * 1024L) != null);
            check("负数不抛异常", ScpTransfer.humanBytes(-1L) != null);

        } finally {
            deleteRecursively(root);
        }

        System.out.println("PASS " + checks + " checks");
    }

    private static boolean containsEnding(List<String> list, String suffix) {
        for (int i = 0; i < list.size(); i++) {
            String s = list.get(i);
            if (s != null && s.replace('\\', '/').endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static int countOccurrences(List<String> list, String want) {
        int n = 0;
        for (int i = 0; i < list.size(); i++) {
            if (want.equals(list.get(i))) {
                n++;
            }
        }
        return n;
    }

    private static void deleteRecursively(Path dir) {
        try {
            if (dir == null || !Files.exists(dir)) {
                return;
            }
            final List<Path> all = new ArrayList<Path>();
            java.util.stream.Stream<Path> walk = Files.walk(dir);
            try {
                walk.forEach(new java.util.function.Consumer<Path>() {
                    @Override
                    public void accept(Path p) {
                        all.add(p);
                    }
                });
            } finally {
                walk.close();
            }
            for (int i = all.size() - 1; i >= 0; i--) {
                try {
                    Files.deleteIfExists(all.get(i));
                } catch (Exception ignored) {
                    // 清理失败不影响结论
                }
            }
        } catch (Exception ignored) {
            // 同上
        }
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

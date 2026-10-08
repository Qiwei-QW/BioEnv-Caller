package vmadapt.test;

import vmadapt.core.ConnectionStore;
import vmadapt.core.SshTarget;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 连接配置持久化的往返测试。
 *
 * <p>本类最重要的一个用例是 <b>Windows 反斜杠路径的往返</b>。
 * 密钥路径形如 {@code C:\Users\14231\.ssh\id_rsa}，里面的每个 {@code \} 在 JSON
 * 里都必须写成 {@code \\}。少写一个，读回来就变成 {@code \U}、{@code \.} 这类
 * 非法转义——轻则路径错得莫名其妙，重则整个配置文件报废，用户所有的连接一起丢。
 * 而且这个错误**只在写盘再读回来时才暴露**，内存里怎么测都是对的，所以必须
 * 走真实的文件往返。
 *
 * <p>用 {@code Files.createTempDirectory} 建临时目录，结束清理，不碰用户的
 * 真实配置文件。
 */
public final class ConnectionStoreTest {

    private static int checks = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== ConnectionStoreTest ===");

        Path dir = Files.createTempDirectory("vmadapt-store-test-");
        try {
            // -----------------------------------------------------------
            // 1. 文件不存在 → 空列表，不抛异常
            // -----------------------------------------------------------
            Path missing = dir.resolve("nope.json");
            ConnectionStore empty = new ConnectionStore(missing);
            List<SshTarget> none = empty.list();
            check("不存在的文件返回空列表", none != null && none.isEmpty());
            check("不存在的文件不触发损坏标记", !empty.recoveredFromCorrupt());
            check("exists() 为假", !empty.exists());
            check("load 不存在返回 null", empty.load("whatever") == null);

            // -----------------------------------------------------------
            // 2. 核心用例：Windows 反斜杠路径往返
            // -----------------------------------------------------------
            Path file = dir.resolve("connections.json");
            ConnectionStore store = new ConnectionStore(file);

            String winKey = "C:\\Users\\14231\\.ssh\\id_rsa";
            SshTarget win = new SshTarget("192.168.190.128", 2222, "bio", winKey);
            win.label = "ubuntu-vm";
            win.timeoutSeconds = 20;
            check("保存成功", store.save(win));
            check("文件已生成", Files.isRegularFile(file));

            List<SshTarget> back = store.list();
            eq("读回一条记录", "1", String.valueOf(back.size()));
            SshTarget r0 = back.get(0);
            eq("反斜杠私钥路径原样读回", winKey, r0.keyPath);
            check("私钥路径里反斜杠数量未变",
                    countChar(r0.keyPath, '\\') == countChar(winKey, '\\'));
            // 注意：不能断言 keyPath 里"不含 \U"——正确的 Windows 路径
            // C:\Users\... 里的 \U 就来自 \Users，任何正确实现都必然包含它。
            // 真正要守的是"读回来和写进去完全一致"，上面两条已经覆盖。
            check("读回的私钥路径是完整路径",
                    r0.keyPath.startsWith("C:") && r0.keyPath.endsWith("id_rsa"));
            eq("host 读回", "192.168.190.128", r0.host);
            eq("user 读回", "bio", r0.user);
            eq("label 读回", "ubuntu-vm", r0.label);
            eq("port 读回", "2222", String.valueOf(r0.port));
            eq("timeoutSeconds 读回", "20", String.valueOf(r0.timeoutSeconds));

            // 磁盘上的 JSON 必须真的把反斜杠转义成 \\
            String raw = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            check("JSON 文本里出现双反斜杠转义", raw.indexOf("\\\\") >= 0);
            check("JSON 里私钥字段存在", raw.indexOf("\"keyPath\"") >= 0);
            check("JSON 里不含 password 字段", raw.indexOf("password") < 0);

            // -----------------------------------------------------------
            // 3. 中文标签与中文路径往返
            // -----------------------------------------------------------
            SshTarget cn = new SshTarget("10.0.0.9", 22, "用户", "D:\\密钥\\id_ed25519");
            cn.label = "测序分析机";
            check("保存中文连接", store.save(cn));

            List<SshTarget> two = store.list();
            eq("读回两条记录", "2", String.valueOf(two.size()));
            SshTarget cnBack = store.load("测序分析机");
            check("按中文别名能载入", cnBack != null);
            if (cnBack != null) {
                eq("中文别名读回", "测序分析机", cnBack.label);
                eq("中文用户名读回", "用户", cnBack.user);
                eq("中文路径读回", "D:\\密钥\\id_ed25519", cnBack.keyPath);
            }

            // 按地址载入也应当可用
            SshTarget byAddr = store.load("bio@192.168.190.128:2222");
            check("按地址能载入", byAddr != null);
            if (byAddr != null) {
                eq("按地址载入的 host 正确", "192.168.190.128", byAddr.host);
            }

            // -----------------------------------------------------------
            // 4. 覆盖保存：同名不产生重复记录
            // -----------------------------------------------------------
            SshTarget win2 = new SshTarget("192.168.190.128", 2222, "bio", winKey);
            win2.label = "ubuntu-vm";
            win2.timeoutSeconds = 33;
            check("覆盖保存成功", store.save(win2));
            List<SshTarget> after = store.list();
            eq("覆盖后总数不变", "2", String.valueOf(after.size()));
            SshTarget ov = store.load("ubuntu-vm");
            check("覆盖后的记录存在", ov != null);
            if (ov != null) {
                eq("覆盖后的超时已更新", "33", String.valueOf(ov.timeoutSeconds));
            }

            // -----------------------------------------------------------
            // 5. 删除
            // -----------------------------------------------------------
            check("按别名删除成功", store.remove("测序分析机"));
            eq("删除后剩一条", "1", String.valueOf(store.list().size()));
            check("删除不存在的返回 false", !store.remove("不存在的名字"));
            check("删除 null 返回 false", !store.remove(null));

            // -----------------------------------------------------------
            // 6. 损坏 JSON → 备份为 .bak，返回空列表，不抛异常
            // -----------------------------------------------------------
            Path corruptFile = dir.resolve("corrupt.json");
            Files.write(corruptFile, "{ this is not json at all ".getBytes(StandardCharsets.UTF_8));
            ConnectionStore corrupt = new ConnectionStore(corruptFile);
            List<SshTarget> corruptList = corrupt.list();
            check("损坏文件返回空列表", corruptList != null && corruptList.isEmpty());
            check("损坏文件被标记", corrupt.recoveredFromCorrupt());
            Path bak = dir.resolve("corrupt.json.bak");
            check("生成了 .bak 备份", Files.isRegularFile(bak));
            // 备份内容应与原文件一致
            String bakText = new String(Files.readAllBytes(bak), StandardCharsets.UTF_8);
            check("备份内容保留了原始文本", bakText.contains("not json at all"));
            // 原文件仍在（不能删用户数据）
            check("损坏文件本身未被删除", Files.isRegularFile(corruptFile));

            // 空文件也算损坏（没有 connections 数组）
            Path emptyFile = dir.resolve("empty.json");
            Files.write(emptyFile, "".getBytes(StandardCharsets.UTF_8));
            ConnectionStore emptyStore = new ConnectionStore(emptyFile);
            check("空文件返回空列表", emptyStore.list().isEmpty());

            // -----------------------------------------------------------
            // 6b. 回归：备份文件名必须由实例路径派生，不能写死
            //     （曾有一个真实 bug：备份名硬编码成 connections.json.bak，
            //      导致多个自定义路径的实例互相覆盖备份）
            // -----------------------------------------------------------
            Path custom = dir.resolve("my-custom-connections.json");
            Files.write(custom, "NOT JSON AT ALL {{{".getBytes(StandardCharsets.UTF_8));
            ConnectionStore customStore = new ConnectionStore(custom);
            check("自定义路径的损坏文件返回空列表", customStore.list().isEmpty());
            check("自定义路径的损坏被标记", customStore.recoveredFromCorrupt());

            Path customBak = dir.resolve("my-custom-connections.json.bak");
            check("备份名由实例路径派生（my-custom-connections.json.bak）",
                    Files.isRegularFile(customBak));
            // 绝不能生成写死的 connections.json.bak
            Path hardcodedBak = dir.resolve("connections.json.bak");
            check("未生成写死的 connections.json.bak", !Files.isRegularFile(hardcodedBak));
            String customBakText = new String(Files.readAllBytes(customBak),
                    StandardCharsets.UTF_8);
            check("自定义备份内容正确", customBakText.contains("NOT JSON AT ALL"));

            // 两个不同路径的实例各自的备份互不覆盖
            Path other = dir.resolve("other-connections.json");
            Files.write(other, "ANOTHER BROKEN ONE".getBytes(StandardCharsets.UTF_8));
            ConnectionStore otherStore = new ConnectionStore(other);
            check("另一个自定义路径也返回空列表", otherStore.list().isEmpty());
            Path otherBak = dir.resolve("other-connections.json.bak");
            check("另一个备份文件生成", Files.isRegularFile(otherBak));
            // 第一个备份必须仍然存在且内容未被改写
            check("第一个备份未被覆盖", Files.isRegularFile(customBak));
            eq("第一个备份内容未被改写", customBakText,
                    new String(Files.readAllBytes(customBak), StandardCharsets.UTF_8));

            // 临时文件也不应残留（写入走 .tmp 再原子替换）
            check("写入未残留 .tmp 文件",
                    !Files.isRegularFile(dir.resolve("connections.json.tmp")));

            // -----------------------------------------------------------
            // 7. 一条坏记录不拖垮其他记录
            // -----------------------------------------------------------
            Path mixed = dir.resolve("mixed.json");
            String json = "{\n"
                    + "  \"version\": 1,\n"
                    + "  \"connections\": [\n"
                    + "    {\"label\":\"good\",\"host\":\"10.0.0.1\",\"port\":22,"
                    + "\"user\":\"u\",\"keyPath\":\"C:\\\\k\",\"timeoutSeconds\":15},\n"
                    + "    {\"label\":\"nohost\",\"port\":22,\"user\":\"u\"},\n"
                    + "    {\"label\":\"good2\",\"host\":\"10.0.0.2\",\"port\":2222,"
                    + "\"user\":\"v\",\"keyPath\":\"D:\\\\k2\",\"timeoutSeconds\":15}\n"
                    + "  ]\n"
                    + "}\n";
            Files.write(mixed, json.getBytes(StandardCharsets.UTF_8));
            List<SshTarget> mixedList = new ConnectionStore(mixed).list();
            eq("坏记录被跳过、好记录保留", "2", String.valueOf(mixedList.size()));
            eq("第一条 host", "10.0.0.1", mixedList.get(0).host);
            eq("第二条 host", "10.0.0.2", mixedList.get(1).host);
            eq("第一条反斜杠路径", "C:\\k", mixedList.get(0).keyPath);
            eq("第二条反斜杠路径", "D:\\k2", mixedList.get(1).keyPath);

            // -----------------------------------------------------------
            // 8. saveAll 整体覆盖
            // -----------------------------------------------------------
            Path allFile = dir.resolve("all.json");
            ConnectionStore allStore = new ConnectionStore(allFile);
            java.util.List<SshTarget> many = new java.util.ArrayList<SshTarget>();
            many.add(new SshTarget("1.1.1.1", 22, "a", null));
            many.add(new SshTarget("2.2.2.2", 22, "b", null));
            check("saveAll 成功", allStore.saveAll(many));
            eq("saveAll 后两条", "2", String.valueOf(allStore.list().size()));
            check("saveAll(null) 等于清空", allStore.saveAll(null));
            check("清空后为空", allStore.list().isEmpty());

            // -----------------------------------------------------------
            // 9. save(null) 与 toJson
            // -----------------------------------------------------------
            Path nfile = dir.resolve("n.json");
            ConnectionStore ns = new ConnectionStore(nfile);
            check("save(null) 返回 false", !ns.save(null));
            String preview = ns.toJson(java.util.Collections.singletonList(win));
            check("toJson 含 connections", preview.contains("\"connections\""));
            check("toJson 含 version", preview.contains("\"version\""));
            check("toJson 含双反斜杠转义", preview.indexOf("\\\\") >= 0);
            check("toJson 不含 password", preview.indexOf("password") < 0);

            // -----------------------------------------------------------
            // 10. 默认路径不抛异常
            // -----------------------------------------------------------
            Path def = ConnectionStore.defaultFile();
            check("默认路径非空", def != null);
            check("默认路径以 connections.json 结尾",
                    def.toString().endsWith("connections.json"));
            // 只读取默认位置，绝不写入（避免污染用户真实配置）
            ConnectionStore defStore = new ConnectionStore();
            List<SshTarget> defList = defStore.list();
            check("默认位置可读且不抛异常", defList != null);

        } finally {
            deleteRecursively(dir);
        }

        System.out.println("PASS " + checks + " checks");
    }

    private static void deleteRecursively(Path dir) {
        try {
            if (dir == null || !Files.exists(dir)) {
                return;
            }
            final java.util.List<Path> all = new java.util.ArrayList<Path>();
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
            // 先删文件再删目录
            for (int i = all.size() - 1; i >= 0; i--) {
                try {
                    Files.deleteIfExists(all.get(i));
                } catch (Exception ignored) {
                    // 清理失败不影响测试结论
                }
            }
        } catch (Exception ignored) {
            // 同上
        }
    }

    private static int countChar(String s, char c) {
        if (s == null) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
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

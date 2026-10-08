package bioenv.test;

import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import bioenv.core.DiscoveryCache;
import bioenv.core.Schema;
import bioenv.core.Tool;
import bioenv.core.ToolRegistry;

/**
 * 「扫描不清空用户设置」的测试。
 *
 * <p>回归的是一个让用户白干活的缺陷：{@code scan()} 以前是推倒重建——
 * <pre>
 *   tools.clear();
 *   for (每个命令) tools.put(command, new Tool(...));   // schema = null
 *   saveCache();                                        // 把 schema 全空的列表写回缓存
 * </pre>
 * 后果有三层：所有工具变回「没探测过」，于是每个都要重新读一遍帮助；用户在工具里
 * 做过的修改（帮助原文的编辑、解析结果）随旧 schema 一起消失；而且扫完就把这个
 * 空版本写进缓存，扫完直接关插件的话缓存里的 schema 也一起没了。
 *
 * <p>现在改成增量合并：删掉的不留、新增的加上、没变的原封不动、
 * <b>换过版本的当新装的重新探测</b>。
 *
 * <p>这是纯逻辑测试，直接调 {@link ToolRegistry#mergeTools}，不需要 conda 环境。
 */
public final class IncrementalScanTest {

    private static int checks;
    private static int failures;

    private IncrementalScanTest() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== IncrementalScanTest ===");

        sematicsOfMerge();
        versionRule();
        cacheRoundTrip();

        System.out.println(failures == 0
                ? "PASS " + checks + " checks"
                : "FAIL: " + failures + " of " + checks + " checks failed");
        if (failures > 0) {
            System.exit(1);
        }
    }

    /** 增 / 删 / 留 三条基本语义。 */
    private static void sematicsOfMerge() {
        Map<String, Tool> existing = new LinkedHashMap<String, Tool>();
        existing.put("a", tool("a", "env1", "pkgA", "1.0", null));
        existing.put("b", tool("b", "env1", "pkgB", "2.0", editedSchema()));
        existing.put("c", tool("c", "env1", "pkgC", "3.0", null));

        // 这次枚举到 a、b（版本不变）、d（新增）；c 没了。
        Map<String, Tool> enumerated = new LinkedHashMap<String, Tool>();
        enumerated.put("a", tool("a", "env1", "pkgA", "1.0", null));
        enumerated.put("b", tool("b", "env1", "pkgB", "2.0", null));
        enumerated.put("d", tool("d", "env1", "pkgD", "4.0", null));

        ToolRegistry.Merge m = ToolRegistry.mergeTools(existing, enumerated);

        check("删掉的工具不在了", !m.merged.containsKey("c"));
        check("移除计数 = 1", m.removed == 1);
        check("新增的工具在表里", m.merged.containsKey("d"));
        check("新增计数 = 1", m.added == 1);
        check("新增的工具 schema 为空（等探测）", m.merged.get("d").schema == null);
        check("保留计数 = 2", m.preserved == 2);
        check("没有工具被当成换版本", m.relearned == 0);
        check("总数 = 3", m.merged.size() == 3);
        check("顺序按本次枚举", String.join(",", m.merged.keySet())
                .equals("a,b,d"));

        // 核心断言：没变的那个工具必须是「原来那个对象」，schema 与编辑逐字未动。
        Tool b = m.merged.get("b");
        check("没变的工具还是原来那个对象", b == existing.get("b"));
        check("它的 schema 还在", b.schema != null);
        check("帮助原文的编辑还在",
                b.schema != null && b.schema.rawHelp.contains("我改过的原文"));
        check("原始帮助也还在（可恢复）",
                b.schema != null && b.schema.originalHelp.contains("原始帮助"));
        check("解析出的参数没丢",
                b.schema != null && b.schema.options.size() == 2);

        // 环境归属变化时要更新指针，但不动 schema。
        Map<String, Tool> moved = new LinkedHashMap<String, Tool>();
        moved.put("b", tool("b", "env9", "pkgB", "2.0", null));
        ToolRegistry.Merge m2 = ToolRegistry.mergeTools(m.merged, moved);
        Tool b2 = m2.merged.get("b");
        check("换环境后指针被更新", b2 == b && "env9".equals(b2.envName));
        check("换环境不影响 schema", b2.schema != null
                && b2.schema.rawHelp.contains("我改过的原文"));
    }

    /** 版本规则：变了当新装；没变或不知道则保留。 */
    private static void versionRule() {
        // 升级
        Map<String, Tool> cur = new LinkedHashMap<String, Tool>();
        cur.put("x", tool("x", "e", "pkgX", "2.6.1", editedSchema()));
        Map<String, Tool> up = new LinkedHashMap<String, Tool>();
        up.put("x", tool("x", "e", "pkgX", "2.7.0", null));
        ToolRegistry.Merge m = ToolRegistry.mergeTools(cur, up);
        check("升级 → 当新装", m.relearned == 1 && m.preserved == 0);
        check("升级 → schema 被清空以便重新探测",
                m.merged.get("x").schema == null);
        check("升级 → 是新对象", m.merged.get("x") != cur.get("x"));

        // 降级同样对待
        ToolRegistry.Merge m2 = ToolRegistry.mergeTools(cur, up);
        Map<String, Tool> down = new LinkedHashMap<String, Tool>();
        down.put("x", tool("x", "e", "pkgX", "2.5.0", null));
        ToolRegistry.Merge m3 = ToolRegistry.mergeTools(cur, down);
        check("降级 → 同样当新装", m3.relearned == 1);

        // 版本未知 → 保留
        Map<String, Tool> unknown = new LinkedHashMap<String, Tool>();
        unknown.put("x", tool("x", "e", "pkgX", "", null));
        ToolRegistry.Merge m4 = ToolRegistry.mergeTools(cur, unknown);
        check("这次版本未知 → 保留", m4.preserved == 1 && m4.relearned == 0);
        check("版本未知时旧版本没被抹掉",
                "2.6.1".equals(m4.merged.get("x").packageVersion));
        check("保留时 schema 还在", m4.merged.get("x").schema != null);

        Map<String, Tool> oldUnknown = new LinkedHashMap<String, Tool>();
        oldUnknown.put("x", tool("x", "e", "pkgX", "", editedSchema()));
        Map<String, Tool> nowKnown = new LinkedHashMap<String, Tool>();
        nowKnown.put("x", tool("x", "e", "pkgX", "9.9", null));
        ToolRegistry.Merge m5 = ToolRegistry.mergeTools(oldUnknown, nowKnown);
        check("旧版本未知 → 不误判成换了版本", m5.preserved == 1);

        // 直接测判定函数本身
        check("版本不同 → true", ToolRegistry.versionChanged("1.0", "2.0"));
        check("版本相同 → false", !ToolRegistry.versionChanged("1.0", "1.0"));
        check("一侧为空 → false", !ToolRegistry.versionChanged("1.0", ""));
        check("都是空 → false", !ToolRegistry.versionChanged("", ""));
        check("null → false", !ToolRegistry.versionChanged(null, "1.0"));
        check("前后空格不影响", !ToolRegistry.versionChanged(" 1.0 ", "1.0"));
        check("m2 与 m3 计数独立",
                m2.relearned == 1 && m3.relearned == 1);
    }

    /** 包版本必须能跨重启存活，否则下次扫描判断不了版本变化。 */
    private static void cacheRoundTrip() throws Exception {
        File dir = Files.createTempDirectory("bioenv-incremental").toFile();
        File f = new File(dir, "cache.properties");

        DiscoveryCache.Data d = new DiscoveryCache.Data();
        d.distro = "Ubuntu-22.04";
        d.fingerprint = "fp";
        DiscoveryCache.CachedTool c = new DiscoveryCache.CachedTool();
        c.command = "seqkit";
        c.envName = "tools";
        c.envPrefix = "/opt/miniconda3/envs/tools";
        c.version = "2.6.1";
        c.packageName = "seqkit";
        c.packageVersion = "2.6.1";
        c.schema = editedSchema();
        d.tools.add(c);
        new DiscoveryCache(f).save(d);

        DiscoveryCache.CachedTool back =
                new DiscoveryCache(f).loadFor("Ubuntu-22.04").tools.get(0);
        check("包名跨重启保留", "seqkit".equals(back.packageName));
        check("包版本跨重启保留", "2.6.1".equals(back.packageVersion));
        check("schema 与编辑跨重启保留",
                back.schema != null && back.schema.rawHelp.contains("我改过的原文"));

        // 端到端：把读回来的当「已有」，用一个换了版本的枚举去合并。
        Map<String, Tool> existing = new LinkedHashMap<String, Tool>();
        Tool t = tool(back.command, back.envName, back.envPrefix,
                back.packageVersion, back.schema);
        existing.put(t.command, t);
        Map<String, Tool> enumerated = new LinkedHashMap<String, Tool>();
        enumerated.put("seqkit", tool("seqkit", "tools",
                "/opt/miniconda3/envs/tools", "2.8.0", null));
        ToolRegistry.Merge m = ToolRegistry.mergeTools(existing, enumerated);
        check("重启后仍能识别出版本变化", m.relearned == 1);

        deleteRecursively(dir);
    }

    private static Tool tool(String command, String envName, String pkg,
                             String pkgVersion, Schema schema) {
        Tool t = new Tool(command, envName, "/opt/miniconda3/envs/" + envName);
        t.packageName = pkg;
        t.packageVersion = pkgVersion;
        t.schema = schema;
        return t;
    }

    /** 一个「用户改过」的 schema：rawHelp 与 originalHelp 不同。 */
    private static Schema editedSchema() {
        Schema s = new Schema();
        s.parsed = true;
        s.originalHelp = "原始帮助：usage: x --a --b";
        s.rawHelp = "我改过的原文：usage: x --a --b";
        s.helpInvocation = "x --help";
        Schema.Option o1 = new Schema.Option();
        o1.flag = "--a";
        o1.description = "first";
        Schema.Option o2 = new Schema.Option();
        o2.flag = "--b";
        o2.description = "second";
        s.options.add(o1);
        s.options.add(o2);
        return s;
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

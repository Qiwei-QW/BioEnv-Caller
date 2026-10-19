package bioenv.test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import bioenv.ai.AiExport;

/**
 * 导出对话时那三件<b>错了也不会报错</b>的事。
 *
 * <h2>为什么要单独测</h2>
 *
 * <p>导出的每一步失败都是静默的：载荷拆错 → 正文从中间开始；文件名没洗 → 保存对话框
 * 点确定之后才弹一句系统错误；写了 BOM → 打开 Markdown 第一行是一串乱字符。
 * 这三种都<b>不会</b>在开发时出现 —— 开发时导出的都是英文短会话，名字里没有冒号。
 *
 * <p>所以这里专门喂中文、竖线、引号、换行、emoji，以及"工具名 · 时间"这个真实形状。
 *
 * <p>不需要显示器，也不碰真正的文件系统目录（用临时目录，跑完删掉）。
 */
public final class AiExportTest {

    private static int checks;
    private static int failures;

    private AiExportTest() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== AiExportTest ===");

        payloads();
        decoding();
        names();
        writing();

        System.out.println(failures == 0
                ? "PASS " + checks + " checks"
                : "FAIL " + failures + " of " + checks);
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------

    /** 页面把一条会话写成 localStorage 里的样子。 */
    private static void payloads() {
        String md = "# fastp · 09:30\n\n## 我\n\n**双端** 数据怎么处理？\n\n"
                + "```bash\nfastp -i a_1.fq -I a_2.fq | tee log.txt\n```\n";
        String raw = enc("fastp · 09:30") + "|" + enc(md);

        // 载荷里不该有这些东西 —— 有的话说明编码没做全，跨过 executeJavaScript
        // 就靠不住（返回值有时被加引号、有时转义）。
        check("载荷里没有双引号", raw.indexOf('"') < 0);
        check("载荷里没有反斜杠", raw.indexOf('\\') < 0);
        check("载荷里没有换行", raw.indexOf('\n') < 0);
        check("载荷里只有一个竖线", count(raw, '|') == 1);

        String[] part = AiExport.decode(raw);
        check("能拆出两段", part != null && part.length == 2);
        if (part == null) {
            return;
        }
        check("名字原样回来", "fastp · 09:30".equals(part[0]));
        check("正文原样回来", md.equals(part[1]));
        check("正文里的竖线没丢", part[1].indexOf("| tee") >= 0);
        check("正文里的加号没变成空格",
                "a+b".equals(decodeText("x|" + enc("a+b"))));
        check("正文里的百分号没丢", "100%".equals(decodeText("x|" + enc("100%"))));
    }

    private static void decoding() {
        check("null 返回 null", AiExport.decode(null) == null);
        check("空串返回 null", AiExport.decode("") == null);
        check("空白返回 null", AiExport.decode("   \n ") == null);
        check("没有正文返回 null", AiExport.decode(enc("名字") + "|") == null);

        // evalString 剥过引号，但引擎的行为不保证，这里兜一层。
        String[] wrapped = AiExport.decode("\"" + enc("n") + "|" + enc("body") + "\"");
        check("外层引号能剥掉", wrapped != null && "body".equals(wrapped[1]));
        check("外层引号剥掉后名字也对", wrapped != null && "n".equals(wrapped[0]));

        // 只有正文、没有名字：名字落到默认值，正文不能因此丢掉。
        String[] noName = AiExport.decode("|" + enc("body"));
        check("没有名字用默认名", noName != null && AiExport.FALLBACK.equals(noName[0]));
        check("没有名字正文还在", noName != null && "body".equals(noName[1]));

        // 干脆没有分隔符（万一哪天载荷格式变了）：整条当正文，不丢内容。
        String[] sole = AiExport.decode(enc("just text"));
        check("没有分隔符时整条当正文", sole != null && "just text".equals(sole[1]));
        check("没有分隔符时名字是默认名",
                sole != null && AiExport.FALLBACK.equals(sole[0]));
    }

    private static void names() {
        // 真实形状：titleStamp() 给的就是"工具名 + 冒号时间"。
        String base = AiExport.safeBase("fastp · 09:30");
        check("冒号被去掉", base.indexOf(':') < 0);
        check("去掉冒号后还剩内容", base.length() > 0);
        check("别的字都还在", base.startsWith("fastp") && base.indexOf("09") > 0);

        String nasty = AiExport.safeBase("a/b\\c:d*e?f\"g<h>i|j");
        boolean clean = true;
        for (int i = 0; i < nasty.length(); i++) {
            char c = nasty.charAt(i);
            if (c == '\\' || c == '/' || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == '|') {
                clean = false;
            }
        }
        check("一串非法字符全被去掉", clean);
        check("剩下的字还在", nasty.indexOf('a') >= 0 && nasty.indexOf('j') >= 0);

        check("空名字给默认名", AiExport.FALLBACK.equals(AiExport.safeBase("")));
        check("null 给默认名", AiExport.FALLBACK.equals(AiExport.safeBase(null)));
        check("全是非法字符也给默认名",
                AiExport.FALLBACK.equals(AiExport.safeBase("///:::")));
        // Windows 会悄悄吃掉名字结尾的点和空格，导出后再看名字就变了。
        check("结尾的点和空格被去掉",
                "name".equals(AiExport.safeBase("name. . ")));

        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            longName.append('x');
        }
        check("超长名字被截短", AiExport.safeBase(longName.toString()).length() <= 60);
        check("中文名字保留", "比对结果".equals(AiExport.safeBase("比对结果")));

        check("没有后缀就补 .md", AiExport.withMdSuffix(new File("x")).getName().equals("x.md"));
        check("已有 .md 不重复补",
                AiExport.withMdSuffix(new File("x.md")).getName().equals("x.md"));
        check("大写 .MD 也认", AiExport.withMdSuffix(new File("x.MD")).getName().equals("x.MD"));
        check("null 返回 null", AiExport.withMdSuffix(null) == null);
    }

    private static void writing() throws Exception {
        Path dir = Files.createTempDirectory("aiexport");
        try {
            // 故意不以换行结尾：写出去时要补上，别把两条断言同时测同一个东西。
            String text = "# 会话\n\n## 我\n\n**测序**结果怎么看？\n\n"
                    + "```bash\nfastp -i a_1.fq\n```\n\n- 一\n- 二 🧬";
            File f = new File(dir.toFile(), "out.md");
            AiExport.write(f, text);

            byte[] bytes = Files.readAllBytes(f.toPath());
            check("文件写出来了", f.isFile() && bytes.length > 0);
            // BOM 在 Markdown 里是正文开头的乱字符，不是"编码正确"的证明。
            check("没有 BOM", bytes.length < 3
                    || !(bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB
                         && bytes[2] == (byte) 0xBF));
            String back = new String(bytes, StandardCharsets.UTF_8);
            check("读回来和写进去一样", (text + "\n").equals(back));
            check("中文没乱码", back.indexOf("测序") >= 0);
            check("emoji 没乱码", back.indexOf("🧬") >= 0);
            check("以换行结尾", back.endsWith("\n"));

            // 已经以换行结尾的，不能变成两个空行。
            File g = new File(dir.toFile(), "out2.md");
            AiExport.write(g, "abc\n");
            check("不重复加换行", "abc\n".equals(
                    new String(Files.readAllBytes(g.toPath()), StandardCharsets.UTF_8)));

            // 目标目录不存在时要建出来 —— 用户在对话框里打了新目录名。
            File deep = new File(new File(dir.toFile(), "sub/dir"), "x.md");
            AiExport.write(deep, "hi");
            check("自动建出不存在的目录", deep.isFile());
        } finally {
            deleteTree(dir.toFile());
        }
    }

    // ------------------------------------------------------------------

    /** 和 {@code encodeURIComponent} 同口径的编码器：只留非保留字符。 */
    private static String enc(String s) {
        StringBuilder sb = new StringBuilder(s.length() * 3);
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xff;
            boolean keep = (b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z')
                    || (b >= '0' && b <= '9') || b == '-' || b == '_' || b == '.'
                    || b == '~';
            if (keep) {
                sb.append((char) b);
            } else {
                sb.append('%');
                sb.append(Character.toUpperCase(Character.forDigit(b >> 4, 16)));
                sb.append(Character.toUpperCase(Character.forDigit(b & 0xf, 16)));
            }
        }
        return sb.toString();
    }

    private static String decodeText(String raw) {
        String[] part = AiExport.decode(raw);
        return part == null ? "" : part[1];
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (int i = 0; i < kids.length; i++) {
                deleteTree(kids[i]);
            }
        }
        f.delete();
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("  FAIL  " + what);
        }
    }

    private static void check(String what, boolean ok, String detail) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("  FAIL  " + what + "  [" + detail + "]");
        }
    }
}

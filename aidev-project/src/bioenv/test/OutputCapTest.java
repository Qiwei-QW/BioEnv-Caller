package bioenv.test;

import java.util.List;

import bioenv.core.OutputCap;

/**
 * 保留输出的上限测试。
 *
 * <p>回归的是「一个工具打印几个 GB，结果被整份留在内存里」：以前每一行都进
 * {@code Result.stdout}，界面那边也无上限地追加，最后是 OutOfMemoryError 或界面卡死。
 * 上限本身是纯函数，所以不需要 WSL、不需要真实工具，直接喂行就能钉住：
 *
 * <ul>
 *   <li>行上界与字符上界先到哪个算哪个；</li>
 *   <li>丢弃的行数要如实计数，并且保留列表末尾要有标记（否则被截断的结果会
 *       看起来像完整的）；</li>
 *   <li>没超限时不得凭空多出一行标记。</li>
 * </ul>
 *
 * <p>Run: {@code java -cp <classes> bioenv.test.OutputCapTest}
 */
public final class OutputCapTest {

    /** 喂 30 万行：比默认行上界（20 万）多，保证上界会被碰到。 */
    private static final int FEEDS = 300000;

    private static int checks;
    private static int failures;

    private OutputCapTest() {
    }

    public static void main(String[] args) {
        System.out.println("=== OutputCapTest ===");

        lineBound();
        charBound();
        smallOutputHasNoMarker();
        singleOversizedLine();
        defaultsAreTheAgreedNumbers();

        System.out.println(failures == 0
                ? "PASS " + checks + " checks"
                : "FAIL: " + failures + " of " + checks + " checks failed");
        if (failures > 0) {
            System.exit(1);
        }
    }

    /** 行数先到上界：保留 20 万行，其余只计数。 */
    private static void lineBound() {
        OutputCap cap = new OutputCap();
        for (int i = 0; i < FEEDS; i++) {
            cap.add("line-" + i);
        }
        List<String> kept = cap.lines();
        int keptLines = cap.keptCount();

        check("保留行数不超过上界", keptLines <= OutputCap.DEFAULT_MAX_LINES);
        check("行上界确实被碰到（保留正好等于上界）",
                keptLines == OutputCap.DEFAULT_MAX_LINES);
        check("丢弃行数 = 喂入 - 保留",
                cap.dropped() == FEEDS - keptLines);
        check("保留列表比保留行数多一行（标记）",
                kept.size() == keptLines + 1);
        check("末行是丢弃标记",
                !kept.isEmpty() && kept.get(kept.size() - 1).contains("已丢弃"));
        check("标记里的数字与 dropped() 一致",
                kept.get(kept.size() - 1).contains(String.valueOf(cap.dropped())));
        check("开头没有被挪动", "line-0".equals(kept.get(0)));
        check("最后一条保留下来的行是对的",
                ("line-" + (keptLines - 1)).equals(kept.get(keptLines - 1)));
    }

    /** 字符数先到上界：每行 10 字符、上限 1000 字符，只能留 100 行。 */
    private static void charBound() {
        OutputCap cap = new OutputCap(OutputCap.DEFAULT_MAX_LINES, 1000);
        for (int i = 0; i < 500; i++) {
            cap.add("0123456789");
        }
        check("字符上界生效（<= 1000）", cap.chars() <= 1000);
        check("字符上界留下 100 行", cap.keptCount() == 100);
        check("字符上界下丢弃计数正确", cap.dropped() == 400);
        List<String> kept = cap.lines();
        check("字符上界也有丢弃标记",
                kept.get(kept.size() - 1).contains("已丢弃 400 行"));
    }

    /** 没超限时不能多出标记行，dropped() 必须是 0。 */
    private static void smallOutputHasNoMarker() {
        OutputCap cap = new OutputCap();
        cap.add("alpha");
        cap.add("beta");
        List<String> kept = cap.lines();
        check("小输出原样保留两行", kept.size() == 2);
        check("小输出没有标记", !kept.get(1).contains("已丢弃"));
        check("小输出 dropped() 为 0", cap.dropped() == 0);
        check("小输出字符数正确", cap.chars() == "alpha".length() + "beta".length());
    }

    /** 单行本身超过字符上界：不保留，但要计入丢弃，标记里也要有数。 */
    private static void singleOversizedLine() {
        OutputCap cap = new OutputCap(10, 5);
        cap.add("0123456789");
        check("单行超长不被保留", cap.keptCount() == 0);
        check("单行超长计入丢弃", cap.dropped() == 1);
        List<String> kept = cap.lines();
        check("只剩标记一行", kept.size() == 1 && kept.get(0).contains("已丢弃 1 行"));
    }

    /** 两个默认值是约定好的数字，防止有人顺手改成派生值。 */
    private static void defaultsAreTheAgreedNumbers() {
        check("默认行上界是 200000", OutputCap.DEFAULT_MAX_LINES == 200000);
        check("默认字符上界是 8000000", OutputCap.DEFAULT_MAX_CHARS == 8000000);
    }

    private static void check(String label, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("  FAIL: " + label);
        }
    }
}

package bioenv.test;

import bioenv.ai.AiModelStore;

/**
 * 模型设置里那两个长度框：填进去的、存下来的、再显示出来的，是不是同一个数。
 *
 * <h2>为什么值得单独测</h2>
 *
 * <p>这两个字段已经骗过人一次：填了非法内容，旧代码静默当 0 存下去，下次打开是空的
 * —— 用户只能得出"我填的东西没了"，而那不是报错，是几天后才会被当成"界面不好用"。
 *
 * <p>现在盯三件事：
 *
 * <ol>
 *   <li><b>非法输入必须返回 -1</b>，让界面拒绝保存，而不是悄悄变成 0（不限）；</li>
 *   <li><b>K 和 M 都是二进制的</b>：1024K 和 1M 是同一个数。十进制的话两者差 2.4%，
 *       用户会以为自己算错；</li>
 *   <li><b>存进去再读出来要还原成人能认的样子</b>：填了 1M 的人，下次打开该看到 1M，
 *       而不是要他数一遍零的 1048576。</li>
 * </ol>
 *
 * <p>不需要显示器：这里只有纯函数。
 */
public final class AiModelLenTest {

    private static int checks;
    private static int failures;

    private AiModelLenTest() {
    }

    public static void main(String[] args) {
        System.out.println("=== AiModelLenTest ===");

        blanks();
        units();
        rejections();
        display();
        roundTrip();

        System.out.println(failures == 0
                ? "PASS " + checks + " checks"
                : "FAIL " + failures + " of " + checks);
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------

    /** 留空 = 不限（0），不是"填错了"。 */
    private static void blanks() {
        check("空串是不限", AiModelStore.parseLen("") == 0);
        check("null 是不限", AiModelStore.parseLen(null) == 0);
        check("全空白是不限", AiModelStore.parseLen("   \t ") == 0);
    }

    private static void units() {
        check("纯数字原样", AiModelStore.parseLen("4096") == 4096);
        check("1024K 是一兆", AiModelStore.parseLen("1024K") == 1048576);
        check("1M 是一兆", AiModelStore.parseLen("1M") == 1048576);
        check("1024K 和 1M 是同一个数",
                AiModelStore.parseLen("1024K") == AiModelStore.parseLen("1M"));
        check("256K", AiModelStore.parseLen("256K") == 262144);
        check("小写 k 也认", AiModelStore.parseLen("2k") == 2048);
        check("小写 m 也认", AiModelStore.parseLen("1m") == 1048576);
        check("K 前后有空格也认", AiModelStore.parseLen(" 512 K ") == 524288);
        // 十进制会把 1024K 算成 1024000 —— 比 1M 小的那个错值。
        check("不是十进制", AiModelStore.parseLen("1024K") != 1024000);
    }

    /** 填错必须能被界面发现，而不是变成一个"看起来合理"的值。 */
    private static void rejections() {
        check("文字返回 -1", AiModelStore.parseLen("abc") == -1);
        check("只有单位返回 -1", AiModelStore.parseLen("K") == -1);
        check("只有 M 返回 -1", AiModelStore.parseLen("M") == -1);
        check("小数返回 -1", AiModelStore.parseLen("12.5K") == -1);
        check("负数返回 -1", AiModelStore.parseLen("-5") == -1);
        check("负数带单位也返回 -1", AiModelStore.parseLen("-1M") == -1);
        // 旧代码把负数夹成 0，也就是"不限" —— 那等于把一个填错当成了想要无限。
        check("负数不会悄悄变成不限", AiModelStore.parseLen("-5") != 0);
        check("天文数字不会溢出成别的值", AiModelStore.parseLen("99999999999999M") == -1
                || AiModelStore.parseLen("99999999999999M") == Integer.MAX_VALUE);
        check("超出 int 的上限被夹住",
                AiModelStore.parseLen("999999M") == Integer.MAX_VALUE);
    }

    private static void display() {
        check("0 显示成空（不限）", "".equals(AiModelStore.formatLen(0)));
        check("负数显示成空", "".equals(AiModelStore.formatLen(-1)));
        check("一兆显示成 1M", "1M".equals(AiModelStore.formatLen(1048576)));
        check("两兆显示成 2M", "2M".equals(AiModelStore.formatLen(2097152)));
        check("256K 显示成 256K", "256K".equals(AiModelStore.formatLen(262144)));
        check("2K 显示成 2K", "2K".equals(AiModelStore.formatLen(2048)));
        check("整不了的不硬凑单位", "1500".equals(AiModelStore.formatLen(1500)));
        check("4096 显示成 4K", "4K".equals(AiModelStore.formatLen(4096)));
    }

    /** 填进去 → 存下来 → 再打开，看到的应该还是自己填的那个写法。 */
    private static void roundTrip() {
        String[] forms = {"1M", "256K", "4096", "2K", "1024K"};
        for (int i = 0; i < forms.length; i++) {
            String form = forms[i];
            int v = AiModelStore.parseLen(form);
            String back = AiModelStore.formatLen(v);
            check("填 " + form + " 存下来是正数", v > 0);
            check("填 " + form + " 再打开还有显示", back.length() > 0);
            // 显示的写法可能更短（1024K → 1M），但值必须一模一样 ——
            // 否则用户什么都没改，保存一次就把数字改了。
            check("填 " + form + " 的值没变（显示成 " + back + "）",
                    AiModelStore.parseLen(back) == v);
        }
        // 1024K 存下来是 1M —— 同一个数，显示成更短的那个写法是故意的。
        check("1024K 显示成 1M",
                "1M".equals(AiModelStore.formatLen(AiModelStore.parseLen("1024K"))));
    }

    // ------------------------------------------------------------------

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("  FAIL  " + what);
        }
    }
}

package bioenv.i18n;

/**
 * 界面语言。
 *
 * <p>为什么用枚举而不是字符串常量（{@code "zh"} / {@code "en"}）：
 * 语言值要经过「界面 → 磁盘 → 界面」的往返，任何一处写错字符串（{@code "ZH"}、
 * {@code "cn"}、{@code "zh_CN"}）都会静默退化成默认语言，而这种 bug 不会崩、
 * 只会让用户每次重启都发现语言被重置，极难定位。枚举把它变成编译期错误。
 *
 * <p>枚举名就是持久化到磁盘的写法，见 {@link #fromCode(String)}。
 */
public enum Lang {

    /**
     * 中文。用户明确要求<b>默认必须是它</b>，所以它在枚举里排第一，
     * 且 {@link #fromCode(String)} 的兜底值也是它。
     */
    ZH("中文", "zh"),

    /**
     * 英文。第二个参数是「按钮上显示什么」——注意显示的是<b>目标语言</b>的
     * 自身名称，而不是当前语言的译名。
     */
    EN("English", "en");

    /** 该语言在切换按钮上显示的文字，即用该语言写成的自身名称。 */
    private final String displayName;

    /** 磁盘上保存的稳定代码；与枚举名分开，是为了让磁盘格式不受重命名影响。 */
    private final String code;

    Lang(String displayName, String code) {
        this.displayName = displayName;
        this.code = code;
    }

    /**
     * 用该语言书写的语言自身名称，例如 ZH 返回「中文」、EN 返回「English」。
     *
     * <p><b>不是</b>「当前语言下这个语言的译名」。这样做的原因见
     * {@link I18n#toggleText()}：切换按钮上要显示的是用户<b>点击后会去到</b>的
     * 那个语言，用目标语言自己写最不容易误解——中文用户看到 {@code English}
     * 知道点了变英文；英文用户看到 {@code 中文} 知道点了变中文。
     * 如果反过来翻译（中文界面显示「英文」），英文用户在自己的界面里会看到
     * 一个不认识的中文词，就不知道该不该点。
     */
    public String displayName() {
        return displayName;
    }

    /** 磁盘上的稳定代码，{@code "zh"} 或 {@code "en"}。 */
    public String code() {
        return code;
    }

    /**
     * 另一种语言，用于「点一下切过去」。
     *
     * <p>目前只有两种语言，所以它就是「另一个」；写成方法而不是
     * {@code lang == ZH ? EN : ZH} 散落在各处，将来加第三种语言时
     * 只需要改这里（届时应改成轮转，并把这个方法的 Javadoc 一并更新）。
     */
    public Lang other() {
        return this == ZH ? EN : ZH;
    }

    /**
     * 从磁盘上的代码还原语言，<b>无法识别时回退到 {@link #ZH}</b>。
     *
     * <p>为什么兜底是中文而不是抛异常或按系统 locale 猜：文件损坏、被手工改成
     * 乱码、或是未来版本写了现在不认识的代码，这三种情况都不该让插件起不来，
     * 也不该让用户看到自己没选过的语言。回退到默认语言是最不意外、
     * 也最符合「默认为中文」这条需求的处理。
     *
     * <p>大小写和首尾空白都做归一化，因为文件可能被人手工编辑过。
     *
     * @param code 磁盘上读到的值，可为 null
     * @return 对应的语言；无法识别时为 {@link #ZH}
     */
    public static Lang fromCode(String code) {
        if (code != null) {
            String c = code.trim();
            for (Lang l : values()) {
                if (l.code.equalsIgnoreCase(c) || l.name().equalsIgnoreCase(c)) {
                    return l;
                }
            }
        }
        return ZH;
    }
}

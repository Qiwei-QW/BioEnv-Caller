package vmadapt.ui;

/**
 * 界面语言提供者（由宿主注入）。
 *
 * <h2>为什么要有这个接口</h2>
 *
 * <p>vmadapt 是一个<b>独立模块</b>：它能被 {@code 虚拟机适配\build.ps1} 单独编译，
 * 也能脱离 TBtools 单独运行。所以它<b>不能</b>直接引用主插件的
 * {@code bioenv.i18n.I18n}——那样一来 vmadapt 就被绑死在 BioEnv Caller 上，
 * 单独编译时会直接找不到类。
 *
 * <p>解决办法就是这里：vmadapt 只声明「我需要知道当前语言和一批文案」，
 * 至于这些文案从哪来、语言怎么切换，由宿主决定。
 * 宿主（merged 侧）写一个十几行的适配器把 {@code bioenv.i18n.I18n} 包起来传进来即可；
 * 不传（或者说传 null）时，界面使用 {@code Text} 的**默认语言（中文）**，
 * vmadapt 单独跑照样能用。
 *
 * <h2>实现者需要注意</h2>
 * <ul>
 *   <li>{@link #t(String)} 可以返回 {@code null} 或空串，表示「这一条我不管，
 *       用内置双语表」。{@code Text.get()} 会对 null/空串回退到内置表，所以
 *       <b>返回 null 是安全的</b>：不必为了保证不返回 null 而去编造文案。
 *       但如果你返回了内容，它会被直接塞进 {@code JLabel}，因此不要返回
 *       带首尾空白的占位字符串（例如 {@code " "}）——那会显示成一块空白。</li>
 *   <li>{@link #addListener(Runnable)} 注册的回调应当被派发到 EDT 上执行，
 *       因为它会去改 Swing 组件。</li>
 * </ul>
 */
public interface LangProvider {

    /**
     * 当前语言代码，约定为 {@code "zh"} 或 {@code "en"}。
     *
     * <p>只有 {@code "zh"} 被识别为中文；其它值一律按英文处理。
     * 返回 {@code null} 表示「我没有意见」，此时 {@code Text} 会采用它的默认语言
     * （{@link UiText#ZH 中文}），与不传 provider 时的行为一致。
     *
     * @return 语言代码；可为 null（采用 Text 的默认语言，即中文）
     */
    String langCode();

    /**
     * 取当前语言下的文案。
     *
     * <p>本接口的 key 用的是 vmadapt 自己的键名（见 {@link UiText} 里的
     * {@code static final String} 常量，例如 {@code UiText.LBL_HOST}），
     * 不是 bioenv 的键名；适配器负责转换。
     *
     * @param key 键名；为 null 时返回空串
     * @return 文案；<b>可以为 null 或空串</b>，表示回退到内置双语表。
     *         返回内容时请保证它不是纯空白。
     */
    String t(String key);

    /**
     * 注册一个「语言变了，请刷新界面」的回调。
     *
     * <p>回调会被派发到 EDT 上。同一个 Runnable 不应重复注册。
     *
     * @param r 回调；null 被忽略
     */
    void addListener(Runnable r);
}
